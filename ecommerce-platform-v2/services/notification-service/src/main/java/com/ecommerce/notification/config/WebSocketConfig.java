package com.ecommerce.notification.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.*;

/**
 * STOMP/WebSocket configuration.
 * Client connects to ws://host/ws, subscribes to /user/{id}/queue/notifications.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry reg) {
        reg.enableSimpleBroker("/queue", "/topic");
        reg.setApplicationDestinationPrefixes("/app");
        reg.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry reg) {
        reg.addEndpoint("/ws").setAllowedOriginPatterns("*").withSockJS();
    }
}
