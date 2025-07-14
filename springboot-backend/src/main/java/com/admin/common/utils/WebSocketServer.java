package com.admin.common.utils;


import com.admin.common.dto.GostConfigDto;
import com.admin.common.dto.GostDto;
import com.admin.common.task.CheckGostConfigAsync;
import com.admin.entity.Node;
import com.admin.service.NodeService;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import javax.annotation.Resource;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.UUID;


@Slf4j
public class WebSocketServer extends TextWebSocketHandler {

    @Resource
    NodeService nodeService;

    // 存储所有活跃的 WebSocket 连接（
    private static final CopyOnWriteArraySet<WebSocketSession> activeSessions = new CopyOnWriteArraySet<>();
    
    // 存储节点ID和对应的WebSocket session映射
    private static final ConcurrentHashMap<Long, WebSocketSession> nodeSessions = new ConcurrentHashMap<>();
    
    // 为每个session提供锁对象，防止并发发送消息
    private static final ConcurrentHashMap<String, Object> sessionLocks = new ConcurrentHashMap<>();
    
    // 存储等待响应的请求，key为requestId，value为CompletableFuture
    private static final ConcurrentHashMap<String, CompletableFuture<GostDto>> pendingRequests = new ConcurrentHashMap<>();
    
    // 缓存加密器实例，避免重复创建
    private static final ConcurrentHashMap<String, AESCrypto> cryptoCache = new ConcurrentHashMap<>();

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

    //接受客户端消息
    @Override
    public void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            if (StringUtils.isNoneBlank(message.getPayload())) {
                
                String id = session.getAttributes().get("id").toString();
                String type = session.getAttributes().get("type").toString();
                String nodeSecret = (String) session.getAttributes().get("nodeSecret");

                // 尝试解密消息
                String decryptedPayload = decryptMessageIfNeeded(message.getPayload(), nodeSecret);

                if (decryptedPayload.contains("memory_usage")){
                    // 先发送确认消息
                    sendToUser(session, "{\"type\":\"call\"}", nodeSecret);
                }else if (decryptedPayload.contains("requestId")) {
                    log.info("收到消息: {}", decryptedPayload);
                    // 处理命令响应消息
                    try {
                        JSONObject responseJson = JSONObject.parseObject(decryptedPayload);
                        String requestId = responseJson.getString("requestId");
                        String responseMessage = responseJson.getString("message");
                        String responseType = responseJson.getString("type");
                        JSONObject responseData = responseJson.getJSONObject("data");
                        
                        if (requestId != null) {
                            CompletableFuture<GostDto> future = pendingRequests.remove(requestId);

                            if (future != null) {
                                GostDto result = new GostDto();
                                
                                // 根据响应类型处理不同的数据
                                if ("PingResponse".equals(responseType) && responseData != null) {
                                    // 特殊处理ping响应，将完整的响应数据返回
                                    result.setMsg(responseMessage != null ? responseMessage : "OK");
                                    result.setData(responseData); // 保存ping详细结果
                                } else {
                                    // 其他类型的响应
                                    result.setMsg(responseMessage != null ? responseMessage : "无响应消息");
                                    if (responseData != null) {
                                        result.setData(responseData);
                                    }
                                }
                                
                                future.complete(result);
                            }
                        }
                    } catch (Exception e) {
                        log.error("处理响应消息失败: {}", e.getMessage(), e);
                    }
                } else {
                    log.info("收到消息: {}", decryptedPayload);
                }

                // 如果是节点类型，转发消息给其他会话
                if (Objects.equals(type, "1")) {
                    JSONObject jsonObject = new JSONObject();
                    jsonObject.put("id", id);
                    jsonObject.put("type", "info");
                    jsonObject.put("data", decryptedPayload);
                    String broadcastMessage = jsonObject.toJSONString();
                    
                    // 异步处理广播消息，避免阻塞当前线程
                    for (WebSocketSession targetSession : activeSessions) {
                        if (targetSession != null && targetSession.isOpen() && !targetSession.equals(session)) {
                            sendToUser(targetSession, broadcastMessage, null);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("处理WebSocket消息时发生异常: {}", e.getMessage(), e);
        }
    }

    /**
     * 尝试解密消息（如果需要）
     */
    private String decryptMessageIfNeeded(String payload, String nodeSecret) {
        if (payload == null || payload.trim().isEmpty()) {
            return payload;
        }

        try {
            // 尝试解析为加密消息格式
            EncryptedMessage encryptedMessage = JSON.parseObject(payload, EncryptedMessage.class);
            
            if (encryptedMessage.isEncrypted() && encryptedMessage.getData() != null) {
                // 获取或创建加密器
                AESCrypto crypto = getOrCreateCrypto(nodeSecret);
                if (crypto == null) {
                    log.warn("⚠️ 收到加密消息但无法创建解密器，使用原始数据");
                    return payload;
                }
                
                // 解密数据
                String decryptedData = crypto.decryptString(encryptedMessage.getData());
                log.debug("🔓 WebSocket消息解密成功");
                return decryptedData;
            }
        } catch (Exception e) {
            // 解析失败，可能是非加密格式，直接返回原始数据
            log.debug("WebSocket消息未加密或解密失败，使用原始数据: {}", e.getMessage());
        }
        
        return payload;
    }

    /**
     * 加密消息（如果可能）
     */
    private static String encryptMessageIfPossible(String message, String nodeSecret) {
        if (message == null || nodeSecret == null) {
            return message;
        }

        try {
            AESCrypto crypto = getOrCreateCrypto(nodeSecret);
            if (crypto != null) {
                String encryptedData = crypto.encrypt(message);
                
                // 创建加密消息包装器
                JSONObject encryptedMessage = new JSONObject();
                encryptedMessage.put("encrypted", true);
                encryptedMessage.put("data", encryptedData);
                encryptedMessage.put("timestamp", System.currentTimeMillis());
                
                log.debug("🔐 WebSocket消息加密成功");
                return encryptedMessage.toJSONString();
            }
        } catch (Exception e) {
            log.warn("⚠️ WebSocket消息加密失败，发送原始数据: {}", e.getMessage());
        }

        return message;
    }

    /**
     * 获取或创建加密器实例
     */
    private static AESCrypto getOrCreateCrypto(String secret) {
        if (secret == null || secret.isEmpty()) {
            return null;
        }
        return cryptoCache.computeIfAbsent(secret, AESCrypto::create);
    }

    // 建立连接
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        try {
            String id = session.getAttributes().get("id").toString();
            String type = session.getAttributes().get("type").toString();
            
            if (!Objects.equals(type, "1")) {
                // 网页管理员连接
                activeSessions.add(session);
                log.info("管理员连接建立，sessionId: {}", session.getId());
            } else {
                // 客户端节点连接
                Long nodeId = Long.valueOf(id);
                String version = (String) session.getAttributes().get("nodeVersion");
                
                log.info("节点 {} 尝试连接，开始处理连接逻辑", nodeId);
                
                // 检查是否已有该节点的连接，如果有则记录日志但直接覆盖
                WebSocketSession existingSession = nodeSessions.get(nodeId);
                if (existingSession != null && existingSession.isOpen()) {
                    log.warn("节点 {} 已有连接存在: {}，新连接将覆盖旧连接", nodeId, existingSession.getId());
                    // 清理旧连接的锁对象
                    sessionLocks.remove(existingSession.getId());
                }
                
                // 直接覆盖会话映射（不主动关闭旧连接，让它自然断开）
                nodeSessions.put(nodeId, session);
                
                // 如果有旧连接，在覆盖映射后主动关闭它
                if (existingSession != null && existingSession.isOpen()) {
                    try {
                        log.info("主动关闭节点 {} 的旧连接: {}", nodeId, existingSession.getId());
                        existingSession.close();
                    } catch (Exception e) {
                        log.error("关闭节点 {} 旧连接失败: {}", nodeId, e.getMessage());
                    }
                }
                
                // 更新节点状态为在线
                Node node = nodeService.getById(nodeId);
                if (node != null) {
                    // 更新状态和版本信息
                    node.setStatus(1);
                    if (version != null) {
                        node.setVersion(version);
                    }
                    boolean updateResult = nodeService.updateById(node);
                    
                    if (updateResult) {
                        log.info("节点 {} 连接建立成功，状态更新为在线，版本: {}", nodeId, version);
                        
                        // 广播节点上线状态给所有管理员
                        JSONObject res = new JSONObject();
                        res.put("id", id);
                        res.put("type", "status");
                        res.put("data", 1);
                        broadcastMessage(res.toJSONString());
                    } else {
                        log.error("节点 {} 状态更新失败", nodeId);
                    }
                } else {
                    log.error("节点 {} 不存在，无法更新状态", nodeId);
                    // 移除无效的会话
                    nodeSessions.remove(nodeId);
                }
            }

        } catch (Exception e) {
            log.error("建立连接时发生异常: {}", e.getMessage(), e);
            // 异常情况下，确保清理会话
            try {
                String id = session.getAttributes().get("id").toString();
                String type = session.getAttributes().get("type").toString();
                if (Objects.equals(type, "1")) {
                    Long nodeId = Long.valueOf(id);
                    nodeSessions.remove(nodeId);
                    log.warn("由于异常，移除节点 {} 的会话", nodeId);
                }
            } catch (Exception cleanupException) {
                log.error("清理异常会话时出错: {}", cleanupException.getMessage());
            }
        }
    }

    // 连接关闭后
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        try {
            String id = session.getAttributes().get("id").toString();
            String type = session.getAttributes().get("type").toString();
            String sessionId = session.getId();
            
            log.info("连接关闭，ID: {}, 类型: {}, 状态: {}", id, type, status);
            
            if (!Objects.equals(type, "1")) {
                // 管理员连接关闭
                boolean removed = activeSessions.remove(session);
                log.info("管理员连接关闭，sessionId: {}, 移除结果: {}", sessionId, removed);
            } else {
                // 客户端节点连接关闭
                Long nodeId = Long.valueOf(id);
                
                // 验证当前会话是否还是活跃会话（关键：这里会自动过滤掉被覆盖的旧连接）
                WebSocketSession currentSession = nodeSessions.get(nodeId);
                if (currentSession == null || !currentSession.equals(session)) {
                    log.info("节点 {} 连接关闭，但已有新连接或会话不匹配，跳过状态更新", nodeId);
                    sessionLocks.remove(sessionId);
                    return;
                }
                
                log.info("节点 {} 当前活跃连接关闭，开始验证并更新状态", nodeId);
                
                    nodeSessions.remove(nodeId);
                    
                    // 更新节点状态为离线
                    Node node = nodeService.getById(nodeId);
                    if (node != null) {
                        node.setStatus(0);
                        boolean updateResult = nodeService.updateById(node);
                        
                        if (updateResult) {
                            log.info("节点 {} 状态更新为离线成功", nodeId);
                            
                            JSONObject res = new JSONObject();
                            res.put("id", id);
                            res.put("type", "status");
                            res.put("data", 0);
                            broadcastMessage(res.toJSONString());
                        } else {
                            log.error("节点 {} 状态更新为离线失败", nodeId);
                        }
                    } else {
                        log.warn("节点 {} 不存在，无法更新离线状态", nodeId);
                    }
            }
            
            // 清理session锁对象
            sessionLocks.remove(sessionId);

        } catch (Exception e) {
            log.error("关闭连接时发生异常: {}", e.getMessage(), e);
        }
    }

    // 点对点发送消息
    @SneakyThrows
    public static void sendToUser(WebSocketSession socketSession, String message) {
        sendToUser(socketSession, message, null);
    }

    // 点对点发送消息（支持加密）
    @SneakyThrows
    public static void sendToUser(WebSocketSession socketSession, String message, String nodeSecret) {
        if (socketSession != null && socketSession.isOpen()) {
            String sessionId = socketSession.getId();
            Object lock = sessionLocks.computeIfAbsent(sessionId, k -> new Object());
            
            synchronized (lock) {
                try {
                    if (socketSession.isOpen()) {
                        // 如果是节点连接且有密钥，尝试加密消息
                        String finalMessage = message;
                        if (nodeSecret != null && !nodeSecret.isEmpty()) {
                            String type = (String) socketSession.getAttributes().get("type");
                            if ("1".equals(type)) { // 节点连接
                                finalMessage = encryptMessageIfPossible(message, nodeSecret);
                            }
                        }
                        socketSession.sendMessage(new TextMessage(finalMessage));
                    }
                } catch (Exception e) {
                    log.error("发送WebSocket消息失败 [sessionId={}]: {}", sessionId, e.getMessage());
                    cleanupSession(socketSession);
                }
            }
        } else {
            cleanupSession(socketSession);
        }
    }
    
    /**
     * 清理失效的session，自动识别是节点session还是管理员session
     */
    private static void cleanupSession(WebSocketSession session) {
        if (session == null) return;
        
        String sessionId = session.getId();
        
        // 清理session锁
        sessionLocks.remove(sessionId);
        
        boolean removedFromAdmin = activeSessions.remove(session);
        
        if (!removedFromAdmin) {
            nodeSessions.entrySet().removeIf(entry -> {
                if (entry.getValue() == session) {
                    return true;
                }
                return false;
            });
        }
    }

    // 广播消息
    public static void broadcastMessage(String message) {
        for (WebSocketSession session : activeSessions) {
            sendToUser(session, message);
        }
    }



    public static GostDto send_msg(Long node_id, Object msg, String type) {
        WebSocketSession nodeSession = nodeSessions.get(node_id);

        if (nodeSession == null) {
            log.warn("发送消息失败：节点 {} 不在线或会话不存在", node_id);
            GostDto result = new GostDto();
            result.setMsg("节点不在线");
            return result;
        }

        if (!nodeSession.isOpen()) {
            log.warn("发送消息失败：节点 {} 连接已断开，清理会话", node_id);
            nodeSessions.remove(node_id);
            sessionLocks.remove(nodeSession.getId());
            GostDto result = new GostDto();
            result.setMsg("节点连接已断开");
            return result;
        }

        // 生成唯一的请求ID
        String requestId = UUID.randomUUID().toString();
        
        // 创建CompletableFuture用于等待响应
        CompletableFuture<GostDto> future = new CompletableFuture<>();
        pendingRequests.put(requestId, future);
        
        // 获取节点密钥用于加密
        String nodeSecret = (String) nodeSession.getAttributes().get("nodeSecret");

        try {
            JSONObject data = new JSONObject();
            data.put("type", type);
            data.put("data", msg);
            data.put("requestId", requestId);
            sendToUser(nodeSession, data.toJSONString(), nodeSecret);
            GostDto result = future.get(10, TimeUnit.SECONDS);
            
            log.debug("成功发送消息到节点 {} 并收到响应: {}", node_id, result.getMsg());
            return result;
            
        } catch (Exception e) {
            // 清理请求和映射关系
            pendingRequests.remove(requestId);

            GostDto result = new GostDto();
            if (e instanceof java.util.concurrent.TimeoutException) {
                result.setMsg("等待响应超时");
                log.warn("节点 {} 响应超时，可能存在连接问题", node_id);
            } else {
                result.setMsg("发送消息失败: " + e.getMessage());
                log.error("发送消息到节点 {} 失败: {}", node_id, e.getMessage(), e);
            }
            return result;
        }
    }

    
}
