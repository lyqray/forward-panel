package com.admin.common.interceptor;


import com.admin.common.exception.UnauthorizedException;
import com.admin.common.utils.JwtUtil;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;


/**
 * JWT拦截器，验证用户是否登录
 */
public class JwtInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String token = request.getHeader("Authorization");

        if (!StringUtils.hasText(token)) {
            throw new UnauthorizedException("未登录或token已过期");
        }


        if (!JwtUtil.validateToken(token)) {
            throw new UnauthorizedException("无效的token或token已过期");
        }

        
        return true;
    }
} 