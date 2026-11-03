package com.videostudio.web;

import com.videostudio.config.RealtimeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 实时音视频直通：浏览器 ⇄ 本服务 ⇄ 云端 realtime 模型。
 * <p>为什么要中转：浏览器 WebSocket 不能自定义 Authorization 头，而云端 realtime 接口要求 Bearer 鉴权。
 * 本类只做二进制/文本帧的双向转发，不解析、不存储音视频，隐私边界清晰。
 */
public class RealtimeProxyHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(RealtimeProxyHandler.class);

    private final RealtimeProperties props;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private final Map<String, Upstream> upstreams = new ConcurrentHashMap<>();

    public RealtimeProxyHandler(RealtimeProperties props) {
        this.props = props;
    }

    private record Upstream(WebSocket socket, WebSocketSession session) {
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        if (!props.ready()) {
            session.close(CloseStatus.SERVICE_OVERLOAD.withReason("云端实时模型未配置"));
            return;
        }
        String url = props.getUrl();
        if (url.contains("model=")) {
            url = url + "&token=" + props.getApiKey();
        } else {
            url = url + (url.contains("?") ? "&" : "?") + "token=" + props.getApiKey();
        }
        WebSocket.Builder builder = http.newWebSocketBuilder()
                .header("Authorization", "Bearer " + props.getApiKey())
                .header("OpenAI-Beta", "realtime=v1")
                .connectTimeout(Duration.ofSeconds(20));

        WebSocket socket = builder.buildAsync(URI.create(url), new WebSocket.Listener() {
            private final StringBuilder buffer = new StringBuilder();

            @Override
            public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                buffer.append(data);
                if (last) {
                    String payload = buffer.toString();
                    buffer.setLength(0);
                    try {
                        if (session.isOpen()) {
                            session.sendMessage(new TextMessage(payload));
                        }
                    } catch (Exception e) {
                        log.debug("转发云端消息失败: {}", e.getMessage());
                    }
                }
                ws.request(1);
                return null;
            }

            @Override
            public CompletionStage<?> onBinary(WebSocket ws, java.nio.ByteBuffer data, boolean last) {
                byte[] bytes = new byte[data.remaining()];
                data.get(bytes);
                try {
                    if (session.isOpen()) {
                        session.sendMessage(new org.springframework.web.socket.BinaryMessage(bytes));
                    }
                } catch (Exception e) {
                    log.debug("转发云端二进制帧失败: {}", e.getMessage());
                }
                ws.request(1);
                return null;
            }

            @Override
            public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
                try {
                    if (session.isOpen()) {
                        session.close(CloseStatus.NORMAL.withReason(
                                reason == null || reason.isBlank() ? "upstream closed" : reason));
                    }
                } catch (Exception ignored) {
                    // 连接已断开，无需处理
                }
                return null;
            }

            @Override
            public void onError(WebSocket ws, Throwable error) {
                log.warn("云端实时连接异常: {}", error.getMessage());
                try {
                    if (session.isOpen()) {
                        session.close(CloseStatus.SERVER_ERROR.withReason("upstream error"));
                    }
                } catch (Exception ignored) {
                    // 连接已断开，无需处理
                }
            }
        }).join();

        upstreams.put(session.getId(), new Upstream(socket, session));
        log.info("实时通道已建立 session={} model={}", session.getId(), props.getModel());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Upstream upstream = upstreams.get(session.getId());
        if (upstream == null) {
            return;
        }
        // 浏览器端可能只发 base64 音频；云端协议两种都接受，这里统一转文本帧
        upstream.socket().sendText(message.getPayload(), true).join();
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, org.springframework.web.socket.BinaryMessage message) {
        Upstream upstream = upstreams.get(session.getId());
        if (upstream == null) {
            return;
        }
        upstream.socket().sendBinary(message.getPayload().duplicate(), true).join();
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Upstream upstream = upstreams.remove(session.getId());
        if (upstream != null) {
            upstream.socket().sendClose(WebSocket.NORMAL_CLOSURE, "client closed").join();
        }
    }
}
