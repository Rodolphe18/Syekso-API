package dev.rodolphe.accesscontrol.config;

import dev.rodolphe.accesscontrol.signaling.SignalingWebSocketHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/** Publishes the signaling endpoint at {@code /ws}, where the Kotlin server had {@code webSocket("/ws")}. */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final SignalingWebSocketHandler handler;

    public WebSocketConfig(SignalingWebSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws")
                // The clients are native Android apps: they send no Origin header, and Spring's
                // same-origin default would be checking something that does not exist. The handshake
                // is guarded by a JWT or the intercom key instead, which is where it belongs.
                .setAllowedOrigins("*");
    }

    /**
     * Two container settings that would each cause a mysterious failure if left at their defaults.
     *
     * <p><strong>Buffer size.</strong> A WebRTC offer carries the full SDP — codecs, ICE candidates,
     * fingerprints — and routinely runs past the 8 KB the servlet container allows by default. Beyond
     * that limit the frame is not truncated, the connection is closed, and the call dies during
     * negotiation with nothing in the logs to explain it.
     *
     * <p><strong>Idle timeout.</strong> A resident's socket sits idle for hours between two visitors.
     * If the container closed it, the doorbell would simply stop working — and the Android client has
     * no reconnection logic, so nothing would bring it back until the app was reopened. Zero means
     * never time out.
     *
     * <p><strong>Why the profile.</strong> This bean tunes the <em>servlet container's</em> WebSocket
     * support, so it demands a real one: it reads the {@code jakarta.websocket.server.ServerContainer}
     * attribute Tomcat publishes at startup. A {@code @SpringBootTest} runs with a mock servlet
     * context that has no such attribute, and the bean would fail the whole context — every MVC
     * integration test with it, over a setting none of them exercise.
     */
    @Bean
    @Profile("!test")
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        var container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(64 * 1024);
        container.setMaxSessionIdleTimeout(0L);
        return container;
    }
}
