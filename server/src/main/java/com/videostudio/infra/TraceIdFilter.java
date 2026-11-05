package com.videostudio.infra;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 给每个请求一个 traceId，写进日志与响应头。
 * <p>没有它，一个任务失败时只能在几千行日志里靠时间戳猜哪几行属于同一次调用；
 * 有了它就能 {@code grep traceId} 直接捞出完整链路。
 * <p>客户端可以通过请求头 {@code X-Request-Id} 自带 id（便于和前端/网关对齐），
 * 不传则生成一个 8 位短 id —— 完整 UUID 在日志里太占地方，8 位足够区分同一天的请求。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String traceId = incoming == null || incoming.isBlank()
                ? UUID.randomUUID().toString().substring(0, 8)
                : incoming.strip().substring(0, Math.min(32, incoming.strip().length()));
        MDC.put(MDC_KEY, traceId);
        response.setHeader(HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            // 必须清理：Tomcat 线程是复用的，不清理会把 traceId 泄漏给下一个请求
            MDC.remove(MDC_KEY);
        }
    }
}
