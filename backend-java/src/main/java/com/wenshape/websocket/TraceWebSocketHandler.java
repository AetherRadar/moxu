package com.wenshape.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArraySet;

@Slf4j
@Component
public class TraceWebSocketHandler extends TextWebSocketHandler {

    private static final int MAX_RECENT_MESSAGES = 100;

    private final ObjectMapper objectMapper;
    private final Set<WebSocketSession> sessions = new CopyOnWriteArraySet<>();
    private final Deque<Map<String, Object>> recentMessages = new ConcurrentLinkedDeque<>();

    public TraceWebSocketHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sessions.add(session);
        
        Map<String, Object> message = Map.of(
            "type", "connected",
            "message", "Connected to MoXu Trace System"
        );
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(message)));
        for (Map<String, Object> recent : snapshotRecentMessages()) {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(recent)));
        }
        log.info("Trace WebSocket connected: sessionId={}", session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
        log.info("Trace WebSocket disconnected: sessionId={}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        if ("ping".equals(message.getPayload())) {
            session.sendMessage(new TextMessage("pong"));
        }
    }

    /**
     * 向所有 trace 连接广播消息
     */
    public void broadcast(Map<String, Object> message) {
        remember(message);

        try {
            String json = objectMapper.writeValueAsString(message);
            TextMessage textMessage = new TextMessage(json);
            
            for (WebSocketSession session : sessions) {
                if (session.isOpen()) {
                    try {
                        session.sendMessage(textMessage);
                    } catch (IOException e) {
                        log.warn("Failed to send trace message: {}", e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to broadcast trace message: {}", e.getMessage());
        }
    }

    public void publish(String type, Map<String, Object> payload) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", type);
        message.put("payload", payload != null ? payload : Map.of());
        broadcast(message);
    }

    private void remember(Map<String, Object> message) {
        if (message == null || message.isEmpty()) {
            return;
        }
        recentMessages.addLast(new LinkedHashMap<>(message));
        while (recentMessages.size() > MAX_RECENT_MESSAGES) {
            recentMessages.pollFirst();
        }
    }

    private List<Map<String, Object>> snapshotRecentMessages() {
        return new ArrayList<>(recentMessages);
    }
}
