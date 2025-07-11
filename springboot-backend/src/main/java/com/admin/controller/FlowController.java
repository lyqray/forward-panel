package com.admin.controller;

import com.admin.common.aop.LogAnnotation;
import com.admin.common.dto.FlowDto;
import com.admin.common.dto.GostConfigDto;
import com.admin.common.lang.R;
import com.admin.common.task.CheckGostConfigAsync;
import com.admin.common.utils.AESCrypto;
import com.admin.common.utils.GostUtil;
import com.admin.entity.*;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.web.bind.annotation.*;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 流量上报控制器
 * 处理节点上报的流量数据，更新用户和隧道的流量统计
 * <p>
 * 主要功能：
 * 1. 接收并处理节点上报的流量数据
 * 2. 更新转发、用户和隧道的流量统计
 * 3. 检查用户总流量限制，超限时暂停所有服务
 * 4. 检查隧道流量限制，超限时暂停对应服务
 * 5. 检查用户到期时间，到期时暂停所有服务
 * 6. 检查隧道权限到期时间，到期时暂停对应服务
 * 7. 检查用户状态，状态不为1时暂停所有服务
 * 8. 检查转发状态，状态不为1时暂停对应转发
 * 9. 检查用户隧道权限状态，状态不为1时暂停对应转发
 * <p>
 * 并发安全解决方案：
 * 1. 使用UpdateWrapper进行数据库层面的原子更新操作，避免读取-修改-写入的竞态条件
 * 2. 使用synchronized锁确保同一用户/隧道的流量更新串行执行
 * 3. 这样可以避免相同用户相同隧道不同转发同时上报时流量统计丢失的问题
 */
@RestController
@RequestMapping("/flow")
@CrossOrigin
@Slf4j
public class FlowController extends BaseController {

    // 常量定义
    private static final String SUCCESS_RESPONSE = "ok";
    private static final String DEFAULT_USER_TUNNEL_ID = "0";
    private static final int FLOW_TYPE_UPLOAD_ONLY = 1;
    private static final int FLOW_TYPE_BIDIRECTIONAL = 2;
    private static final long BYTES_TO_GB = 1024L * 1024L * 1024L;

    // 用于同步相同用户和隧道的流量更新操作
    private static final ConcurrentHashMap<String, Object> USER_LOCKS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Object> TUNNEL_LOCKS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Object> FORWARD_LOCKS = new ConcurrentHashMap<>();

    // 缓存加密器实例，避免重复创建
    private static final ConcurrentHashMap<String, AESCrypto> CRYPTO_CACHE = new ConcurrentHashMap<>();

    @Resource
    CheckGostConfigAsync checkGostConfigAsync;

    /**
     * 加密消息包装器
     */
    public static class EncryptedMessage {
        private boolean encrypted;
        private String data;
        private Long timestamp;

        // getters and setters
        public boolean isEncrypted() { return encrypted; }
        public void setEncrypted(boolean encrypted) { this.encrypted = encrypted; }
        public String getData() { return data; }
        public void setData(String data) { this.data = data; }
        public Long getTimestamp() { return timestamp; }
        public void setTimestamp(Long timestamp) { this.timestamp = timestamp; }
    }

    @PostMapping("/config")
    @LogAnnotation
    public String config(@RequestBody String rawData, String secret) {
        Node node = nodeService.getOne(new QueryWrapper<Node>().eq("secret", secret));
        if (node == null) return SUCCESS_RESPONSE;
        
        try {
            // 尝试解密数据
            String decryptedData = decryptIfNeeded(rawData, secret);

            // 解析为GostConfigDto
            GostConfigDto gostConfigDto = JSON.parseObject(decryptedData, GostConfigDto.class);
            checkGostConfigAsync.cleanNodeConfigs(node.getId().toString(), gostConfigDto);
            
            log.info("🔓 节点 {} 配置数据接收成功{}", node.getId(),  isEncryptedMessage(rawData) ? "（已解密）" : "");
                    
        } catch (Exception e) {
            log.error("处理节点 {} 配置数据失败: {}", node.getId(), e.getMessage());
        }
        
        return SUCCESS_RESPONSE;
    }

    @RequestMapping("/test")
    @LogAnnotation
    public String test() {
        return "test";
    }

    /**
     * 处理流量数据上报
     *
     * @param rawData 原始数据（可能是加密的）
     * @param secret  节点密钥
     * @return 处理结果
     */
    @RequestMapping("/upload")
    @LogAnnotation
    public String uploadFlowData(@RequestBody String rawData, String secret) {
        // 1. 验证节点权限
        if (!isValidNode(secret)) {
            return SUCCESS_RESPONSE;
        }

        try {
            // 2. 尝试解密数据
            String decryptedData = decryptIfNeeded(rawData, secret);
            
            // 3. 解析为FlowDto列表
            List<FlowDto> flowDataList = JSON.parseArray(decryptedData, FlowDto.class);
            
            if (flowDataList.isEmpty()) {
                return SUCCESS_RESPONSE;
            }
            if (Objects.equals(flowDataList.get(0).getN(), "web_api")) {
                return SUCCESS_RESPONSE;
            }

            // 记录日志
            log.debug("🔓 节点流量数据接收成功{}", 
                     isEncryptedMessage(rawData) ? "（已解密）" : "");

            // 4. 处理流量数据
            return processFlowData(flowDataList);
            
        } catch (Exception e) {
            log.error("处理流量数据失败: {}", e.getMessage(), e);
            return SUCCESS_RESPONSE;
        }
    }

    /**
     * 检测消息是否为加密格式
     */
    private boolean isEncryptedMessage(String data) {
        try {
            JSONObject json = JSON.parseObject(data);
            return json.getBooleanValue("encrypted");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 根据需要解密数据
     */
    private String decryptIfNeeded(String rawData, String secret) {
        if (rawData == null || rawData.trim().isEmpty()) {
            throw new IllegalArgumentException("数据不能为空");
        }

        try {
            // 尝试解析为加密消息格式
            EncryptedMessage encryptedMessage = JSON.parseObject(rawData, EncryptedMessage.class);
            
            if (encryptedMessage.isEncrypted() && encryptedMessage.getData() != null) {
                // 获取或创建加密器
                AESCrypto crypto = getOrCreateCrypto(secret);
                if (crypto == null) {
                    log.warn("⚠️ 收到加密消息但无法创建解密器，使用原始数据");
                    return rawData;
                }
                
                // 解密数据
                String decryptedData = crypto.decryptString(encryptedMessage.getData());
                log.debug("🔓 数据解密成功");
                return decryptedData;
            }
        } catch (Exception e) {
            // 解析失败，可能是非加密格式，直接返回原始数据
            log.debug("数据未加密或解密失败，使用原始数据: {}", e.getMessage());
        }
        
        return rawData;
    }

    /**
     * 获取或创建加密器实例
     */
    private AESCrypto getOrCreateCrypto(String secret) {
        return CRYPTO_CACHE.computeIfAbsent(secret, AESCrypto::create);
    }

    /**
     * 处理流量数据的核心逻辑
     */
    private String processFlowData(List<FlowDto> flowDataList) {
        // 2. 解析服务名称获取ID信息
        String[] serviceIds = parseServiceName(flowDataList.get(0).getN());
        String forwardId = serviceIds[0];
        String userId = serviceIds[1];
        String userTunnelId = serviceIds[2];

        // 3. 一次性查询相关实体，避免后续重复查询
        Forward forward = forwardService.getById(forwardId);
        User user = userService.getById(userId);
        UserTunnel userTunnel = null;
        if (!Objects.equals(userTunnelId, DEFAULT_USER_TUNNEL_ID)) {
            userTunnel = userTunnelService.getById(userTunnelId);
        }
        
        // 4. 处理流量倍率
        List<FlowDto> validFlowData = filterFlowData(flowDataList, forward);

        // 5. 计算总流量
        FlowStatistics flowStats = calculateTotalFlow(validFlowData);

        // 6. 获取流量计费类型
        int flowType = getFlowType(forward);

        // 7. 先更新所有流量统计 - 确保流量数据的一致性
        // 7.1 更新转发流量
        if (forward != null) {
            updateForwardFlow(forwardId, flowStats);
        }

        // 7.2 更新用户流量
        if (user != null) {
            updateUserFlow(userId, flowStats, flowType);
        }

        // 7.3 更新隧道权限流量
        if (userTunnel != null) {
            updateUserTunnelFlow(userTunnelId, flowStats);
        }

        // 8. 流量更新完成后，再进行各种检查和服务暂停操作
        // 8.1 用户相关检查
        if (user != null) {
            checkUserRelatedLimits(user, userTunnelId);
        }

        // 8.2 隧道权限相关检查
        if (userTunnel != null) {
            checkUserTunnelRelatedLimits(userTunnel, forwardId, userId, userTunnelId, forward, flowType);
        }

        // 8.3 转发状态检查
        if (forward != null) {
            checkForwardStatus(forward, userId, userTunnelId);
        }

        return SUCCESS_RESPONSE;
    }



    private List<FlowDto> filterFlowData(List<FlowDto> flowDataList, Forward forward) {
        if (forward != null) {
            Tunnel tunnel = tunnelService.getById(forward.getTunnelId());
            if (tunnel != null){
                BigDecimal trafficRatio = tunnel.getTrafficRatio();
                for (FlowDto flowDto : flowDataList) {
                    BigDecimal originalD = BigDecimal.valueOf(flowDto.getD());
                    BigDecimal originalU = BigDecimal.valueOf(flowDto.getU());

                    BigDecimal newD = originalD.multiply(trafficRatio);
                    BigDecimal newU = originalU.multiply(trafficRatio);

                    flowDto.setD(newD.longValue());
                    flowDto.setU(newU.longValue());
                }
            }
        }
        return flowDataList;
    }

    /**
     * 验证节点是否有效
     */
    private boolean isValidNode(String secret) {
        int nodeCount = nodeService.count(new QueryWrapper<Node>().eq("secret", secret));
        return nodeCount > 0;
    }


    /**
     * 解析服务名称获取ID信息
     */
    private String[] parseServiceName(String serviceName) {
        return serviceName.split("_");
    }

    /**
     * 计算总流量统计
     */
    private FlowStatistics calculateTotalFlow(List<FlowDto> validFlowData) {
        long totalUpload = 0L;
        long totalDownload = 0L;

        for (FlowDto flow : validFlowData) {
            totalUpload += flow.getU();
            totalDownload += flow.getD();
        }

        return new FlowStatistics(totalUpload, totalDownload);
    }

    /**
     * 获取流量计费类型 - 优化版本，使用传入的Forward实体
     */
    private int getFlowType(Forward forward) {
        int defaultFlowType = FLOW_TYPE_BIDIRECTIONAL;

        if (forward != null) {
            Tunnel tunnel = tunnelService.getById(forward.getTunnelId());
            if (tunnel != null) {
                return tunnel.getFlow();
            }
        }

        return defaultFlowType;
    }

    /**
     * 更新转发流量统计 - 使用原子操作避免并发问题
     */
    private boolean updateForwardFlow(String forwardId, FlowStatistics flowStats) {
        // 对相同转发的流量更新进行同步，避免并发覆盖
        synchronized (getForwardLock(forwardId)) {
            UpdateWrapper<Forward> updateWrapper = new UpdateWrapper<>();
            updateWrapper.eq("id", forwardId);
            updateWrapper.setSql("in_flow = in_flow + " + flowStats.getDownload());
            updateWrapper.setSql("out_flow = out_flow + " + flowStats.getUpload());

            return forwardService.update(null, updateWrapper);
        }
    }

    /**
     * 更新用户流量统计 - 使用原子操作避免并发问题
     */
    private boolean updateUserFlow(String userId, FlowStatistics flowStats, int flowType) {
        // 对相同用户的流量更新进行同步，避免并发覆盖
        synchronized (getUserLock(userId)) {
            UpdateWrapper<User> updateWrapper = new UpdateWrapper<>();
            updateWrapper.eq("id", userId);

            // 使用SQL的原子更新操作，避免读取-修改-写入的并发问题
            if (flowType == FLOW_TYPE_BIDIRECTIONAL) {
                // 双向计费：同时更新上传和下载流量
                updateWrapper.setSql("in_flow = in_flow + " + flowStats.getDownload());
                updateWrapper.setSql("out_flow = out_flow + " + flowStats.getUpload());
            } else {
                // 仅上传计费：只更新上传流量
                updateWrapper.setSql("out_flow = out_flow + " + flowStats.getUpload());
            }

            return userService.update(null, updateWrapper);
        }
    }

    /**
     * 更新用户隧道流量统计 - 优化版本，仅负责流量更新
     */
    private boolean updateUserTunnelFlow(String userTunnelId, FlowStatistics flowStats) {
        if (Objects.equals(userTunnelId, DEFAULT_USER_TUNNEL_ID)) {
            return true; // 默认隧道不需要更新，返回成功
        }

        // 对相同用户隧道的流量更新进行同步，避免并发覆盖
        synchronized (getTunnelLock(userTunnelId)) {
            UpdateWrapper<UserTunnel> updateWrapper = new UpdateWrapper<>();
            updateWrapper.eq("id", userTunnelId);
            updateWrapper.setSql("in_flow = in_flow + " + flowStats.getDownload());
            updateWrapper.setSql("out_flow = out_flow + " + flowStats.getUpload());

            return userTunnelService.update(null, updateWrapper);
        }
    }

    /**
     * 检查用户隧道流量限制 - 优化版本，使用传入的UserTunnel实体
     */
    private void checkUserTunnelFlowLimit(UserTunnel userTunnel, int flowType,
                                          String forwardId, String userId, String userTunnelId) {
        long currentFlow = (flowType == FLOW_TYPE_UPLOAD_ONLY) ?
                userTunnel.getOutFlow() :
                userTunnel.getInFlow() + userTunnel.getOutFlow();

        long flowLimit = userTunnel.getFlow() * BYTES_TO_GB;

        if (flowLimit < currentFlow) {
            pauseServiceDueToTunnelLimit(userTunnel.getTunnelId(), forwardId, userId, userTunnelId);
        }
    }

    /**
     * 因隧道流量超限暂停服务
     */
    private void pauseServiceDueToTunnelLimit(Integer tunnelId, String forwardId,
                                              String userId, String userTunnelId) {
        // 先检查转发状态，如果已经是暂停状态就不需要调用暂停服务
        Forward currentForward = forwardService.getById(forwardId);
        if (currentForward == null || currentForward.getStatus() == 0) {
            return; // 转发不存在或已经暂停，无需处理
        }

        Tunnel tunnel = tunnelService.getById(tunnelId);
        if (tunnel != null) {
            Node node = nodeService.getNodeById(tunnel.getInNodeId());
            if (node != null) {
                String serviceName = buildServiceName(forwardId, userId, userTunnelId);
                GostUtil.PauseService(node.getId(), serviceName);

                // 隧道转发需要同时暂停远端服务
                if (tunnel.getType() == 2) { // TUNNEL_TYPE_TUNNEL_FORWARD
                    Node outNode = nodeService.getNodeById(tunnel.getOutNodeId());
                    if (outNode != null) {
                        GostUtil.PauseRemoteService(outNode.getId(), serviceName);
                    }
                }
            }
        }

        // 更新转发状态为暂停（只更新状态字段，避免覆盖流量数据）
        UpdateWrapper<Forward> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq("id", forwardId);
        updateWrapper.set("status", 0);
        forwardService.update(null, updateWrapper);
    }

    /**
     * 因隧道权限到期暂停服务
     */
    private void pauseServiceDueToTunnelExpiration(Integer tunnelId, String forwardId,
                                                   String userId, String userTunnelId) {
        // 先检查转发状态，如果已经是暂停状态就不需要调用暂停服务
        Forward currentForward = forwardService.getById(forwardId);
        if (currentForward == null || currentForward.getStatus() == 0) {
            return; // 转发不存在或已经暂停，无需处理
        }

        Tunnel tunnel = tunnelService.getById(tunnelId);
        if (tunnel != null) {
            Node node = nodeService.getNodeById(tunnel.getInNodeId());
            if (node != null) {
                String serviceName = buildServiceName(forwardId, userId, userTunnelId);
                GostUtil.PauseService(node.getId(), serviceName);

                // 隧道转发需要同时暂停远端服务
                if (tunnel.getType() == 2) { // TUNNEL_TYPE_TUNNEL_FORWARD
                    Node outNode = nodeService.getNodeById(tunnel.getOutNodeId());
                    if (outNode != null) {
                        GostUtil.PauseRemoteService(outNode.getId(), serviceName);
                    }
                }
            }
        }

        // 更新转发状态为暂停（只更新状态字段，避免覆盖流量数据）
        UpdateWrapper<Forward> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq("id", forwardId);
        updateWrapper.set("status", 0);
        forwardService.update(null, updateWrapper);
    }

    /**
     * 检查用户相关的所有限制 - 用户存在时统一处理
     */
    private void checkUserRelatedLimits(User user, String userTunnelId) {
        // 重新查询用户以获取最新的流量数据
        User updatedUser = userService.getById(user.getId());
        if (updatedUser == null) {
            return;
        }

        // 检查用户总流量限制
        long userFlowLimit = updatedUser.getFlow() * BYTES_TO_GB;
        long userCurrentFlow = updatedUser.getInFlow() + updatedUser.getOutFlow();
        if (userFlowLimit < userCurrentFlow) {
            pauseAllUserServices(updatedUser.getId().toString(), userTunnelId);
            return; // 用户流量超限，直接返回，不需要再检查其他项
        }

        // 检查用户到期时间
        if (updatedUser.getExpTime() != null && updatedUser.getExpTime() <= System.currentTimeMillis()) {
            pauseAllUserServices(updatedUser.getId().toString(), userTunnelId);
            return; // 用户到期，直接返回
        }

        // 检查用户状态
        if (updatedUser.getStatus() != 1) {
            pauseAllUserServices(updatedUser.getId().toString(), userTunnelId);
        }
    }

    /**
     * 检查用户隧道权限相关的所有限制 - 隧道权限存在时统一处理
     */
    private void checkUserTunnelRelatedLimits(UserTunnel userTunnel, String forwardId, String userId, String userTunnelId, Forward forward, int flowType) {
        // 重新查询用户隧道权限以获取最新的流量数据
        UserTunnel updatedUserTunnel = userTunnelService.getById(userTunnel.getId());
        if (updatedUserTunnel == null) {
            return;
        }

        // 检查隧道权限流量限制
        checkUserTunnelFlowLimit(updatedUserTunnel, flowType, forwardId, userId, userTunnelId);

        // 检查隧道权限到期时间
        if (updatedUserTunnel.getExpTime() != null && updatedUserTunnel.getExpTime() <= System.currentTimeMillis()) {
            pauseServiceDueToTunnelExpiration(updatedUserTunnel.getTunnelId(), forwardId, userId, userTunnelId);
            return; // 隧道权限到期，直接返回
        }

        // 检查用户隧道权限状态
        if (updatedUserTunnel.getStatus() != 1) {
            if (forward != null) {
                pauseSpecificForward(forward, userId, userTunnelId);
            }
        }
    }

    /**
     * 检查转发状态 - 优化版本，使用传入的Forward实体
     */
    private void checkForwardStatus(Forward forward, String userId, String userTunnelId) {
        // 检查转发状态是否为正常（1），如果不正常且不是暂停状态，才需要暂停
        if (forward.getStatus() != 1 && forward.getStatus() != 0) {
            pauseSpecificForward(forward, userId, userTunnelId);
        }
    }

    /**
     * 暂停指定的转发服务
     */
    private void pauseSpecificForward(Forward forward, String userId, String userTunnelId) {
        // 先检查转发状态，如果已经是暂停状态就不需要调用暂停服务
        if (forward.getStatus() == 0) {
            return; // 已经暂停，无需处理
        }

        Tunnel tunnel = tunnelService.getById(forward.getTunnelId());
        if (tunnel != null) {
            Node node = nodeService.getNodeById(tunnel.getInNodeId());
            if (node != null) {
                String serviceName = buildServiceName(String.valueOf(forward.getId()), userId, userTunnelId);
                GostUtil.PauseService(node.getId(), serviceName);

                // 隧道转发需要同时暂停远端服务
                if (tunnel.getType() == 2) { // TUNNEL_TYPE_TUNNEL_FORWARD
                    Node outNode = nodeService.getNodeById(tunnel.getOutNodeId());
                    if (outNode != null) {
                        GostUtil.PauseRemoteService(outNode.getId(), serviceName);
                    }
                }
            }
        }

        // 更新转发状态为暂停（只更新状态字段，避免覆盖流量数据）
        UpdateWrapper<Forward> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq("id", forward.getId());
        updateWrapper.set("status", 0);
        forwardService.update(null, updateWrapper);
    }

    /**
     * 暂停用户所有服务
     */
    private void pauseAllUserServices(String userId, String userTunnelId) {
        List<Forward> userForwards = forwardService.list(new QueryWrapper<Forward>().eq("user_id", userId));

        for (Forward forward : userForwards) {
            // 先检查转发状态，如果已经是暂停状态就跳过
            if (forward.getStatus() == 0) {
                continue; // 已经暂停，跳过此转发
            }

            Tunnel tunnel = tunnelService.getById(forward.getTunnelId());
            if (tunnel != null) {
                Node node = nodeService.getNodeById(tunnel.getInNodeId());
                if (node != null) {
                    // 查找该转发对应的正确userTunnelId
                    String actualUserTunnelId = findActualUserTunnelId(userId, forward.getTunnelId().toString());
                    String serviceName = buildServiceName(String.valueOf(forward.getId()), userId, actualUserTunnelId);
                    GostUtil.PauseService(node.getId(), serviceName);

                    // 隧道转发需要同时暂停远端服务
                    if (tunnel.getType() == 2) { // TUNNEL_TYPE_TUNNEL_FORWARD
                        Node outNode = nodeService.getNodeById(tunnel.getOutNodeId());
                        if (outNode != null) {
                            GostUtil.PauseRemoteService(outNode.getId(), serviceName);
                        }
                    }
                }
            }

            // 更新转发状态为暂停（只更新状态字段，避免覆盖流量数据）
            UpdateWrapper<Forward> updateWrapper = new UpdateWrapper<>();
            updateWrapper.eq("id", forward.getId());
            updateWrapper.set("status", 0);
            forwardService.update(null, updateWrapper);
        }
    }

    /**
     * 查找用户在指定隧道的实际userTunnelId
     */
    private String findActualUserTunnelId(String userId, String tunnelId) {
        UserTunnel userTunnel = userTunnelService.getOne(
                new QueryWrapper<UserTunnel>()
                        .eq("user_id", userId)
                        .eq("tunnel_id", tunnelId)
        );

        return userTunnel != null ? String.valueOf(userTunnel.getId()) : DEFAULT_USER_TUNNEL_ID;
    }

    /**
     * 构建服务名称
     */
    private String buildServiceName(String forwardId, String userId, String userTunnelId) {
        return forwardId + "_" + userId + "_" + userTunnelId;
    }

    /**
     * 获取用户锁对象
     */
    private Object getUserLock(String userId) {
        return USER_LOCKS.computeIfAbsent(userId, k -> new Object());
    }

    /**
     * 获取隧道锁对象
     */
    private Object getTunnelLock(String userTunnelId) {
        return TUNNEL_LOCKS.computeIfAbsent(userTunnelId, k -> new Object());
    }

    /**
     * 获取转发锁对象
     */
    private Object getForwardLock(String forwardId) {
        return FORWARD_LOCKS.computeIfAbsent(forwardId, k -> new Object());
    }

    /**
     * 流量统计数据类
     */
    private static class FlowStatistics {
        private final long upload;
        private final long download;

        public FlowStatistics(long upload, long download) {
            this.upload = upload;
            this.download = download;
        }

        public long getUpload() {
            return upload;
        }

        public long getDownload() {
            return download;
        }
    }
}
