package com.admin.config;


import com.admin.common.utils.JwtUtil;
import com.admin.entity.Node;
import com.admin.service.NodeService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.support.HttpSessionHandshakeInterceptor;

import javax.annotation.Resource;
import java.util.Map;
import java.util.Objects;


@Configuration
@Slf4j
public class WebSocketInterceptor extends HttpSessionHandshakeInterceptor {

    @Resource
    NodeService nodeService;

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler, Exception ex) {

    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler, Map<String, Object> attributes) throws Exception {
        ServletServerHttpRequest serverHttpRequest = (ServletServerHttpRequest) request;
        String secret = serverHttpRequest.getServletRequest().getParameter("secret");
        String type = serverHttpRequest.getServletRequest().getParameter("type");
        String version = serverHttpRequest.getServletRequest().getParameter("version");
        if (Objects.equals(type, "1")) {
            Node node = nodeService.getOne(new QueryWrapper<Node>().eq("secret", secret));
            if (node == null) {
                log.warn("节点验证失败：未找到匹配的secret");
                return false;
            }
            attributes.put("id", node.getId());
            attributes.put("nodeSecret", secret);
            attributes.put("nodeVersion", version);
            log.info("节点 {} 通过验证，版本: {}", node.getId(), version);
            // 不在这里更新状态，等到连接建立后再统一更新
        }else {
            boolean b = JwtUtil.validateToken(secret);
            if (!b) return false;
            attributes.put("id", JwtUtil.getUserIdFromToken(secret));
        }
        attributes.put("type", type);
        return true;
    }


}
