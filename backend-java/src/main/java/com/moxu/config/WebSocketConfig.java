package com.moxu.config;

import com.moxu.websocket.SessionWebSocketHandler;
import com.moxu.websocket.TraceWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final SessionWebSocketHandler sessionWebSocketHandler;
    private final TraceWebSocketHandler traceWebSocketHandler;

    public WebSocketConfig(SessionWebSocketHandler sessionWebSocketHandler,
                          TraceWebSocketHandler traceWebSocketHandler) {
        this.sessionWebSocketHandler = sessionWebSocketHandler;
        this.traceWebSocketHandler = traceWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(sessionWebSocketHandler, "/ws/{projectId}/session")
                .setAllowedOriginPatterns(
                        "http://localhost:*",
                        "http://127.0.0.1:*",
                        "https://localhost:*",
                        "https://127.0.0.1:*"
                );
        
        registry.addHandler(traceWebSocketHandler, "/ws/trace")
                .setAllowedOriginPatterns(
                        "http://localhost:*",
                        "http://127.0.0.1:*",
                        "https://localhost:*",
                        "https://127.0.0.1:*"
                );
    }
}
