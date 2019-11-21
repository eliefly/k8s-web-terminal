package com.kube.config;

import com.kube.controller.ws.ShellWebSocketHandler;
import com.kube.controller.ws.SpringWebSocketHandlerInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    @Autowired
    private ShellWebSocketHandler shellWebSocketHandler;

    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(shellWebSocketHandler, "/ws/container/terminal")
                .setAllowedOrigins()
                .addInterceptors(new SpringWebSocketHandlerInterceptor())
                // 添加允许跨域访问
                .setAllowedOrigins("*");
        //  .withSockJS();
    }

}