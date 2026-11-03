package com.videostudio.config;

import com.videostudio.web.RealtimeProxyHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/** 注册 /ws/realtime 帧转发端点 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final RealtimeProperties realtime;

    public WebSocketConfig(RealtimeProperties realtime) {
        this.realtime = realtime;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new RealtimeProxyHandler(realtime), "/ws/realtime")
                .setAllowedOriginPatterns("*");
    }
}
