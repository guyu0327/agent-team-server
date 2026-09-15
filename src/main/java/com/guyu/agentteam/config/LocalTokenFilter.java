package com.guyu.agentteam.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 本地访问令牌：桌面壳生成随机 token 传入（app.security.token），防止本机其他进程
 * 或浏览器中恶意网页读写 localhost API。未配置时（裸跑开发）不校验。
 */
@Component
public class LocalTokenFilter extends OncePerRequestFilter {

    private final String token;

    public LocalTokenFilter(@Value("${app.security.token:}") String token) {
        this.token = token == null ? "" : token.trim();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // CORS 预检请求不携带自定义头，必须放行
        return token.isEmpty() || "OPTIONS".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String given = request.getHeader("X-AT-Token");
        if (given == null || given.isBlank()) {
            given = request.getParameter("token");
        }
        boolean ok = given != null && MessageDigest.isEqual(
                given.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
        if (!ok) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"message\":\"本地访问令牌无效\",\"code\":401}");
            return;
        }
        chain.doFilter(request, response);
    }
}
