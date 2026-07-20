package com.chat.talkMe.config;

import com.chat.talkMe.security.WebSocketChannelInterceptor;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final WebSocketChannelInterceptor channelInterceptor;
    private final RabbitDestinationInterceptor rabbitDestinationInterceptor;

    @Value("${app.cors.allowed-origins}")
    private String allowedOrigins;

    @Value("${app.broker.relay-enabled:false}")
    private boolean relayEnabled;

    @Value("${app.broker.relay-host:localhost}")
    private String relayHost;

    @Value("${app.broker.relay-port:61613}")
    private int relayPort;

    @Value("${app.broker.relay-login:talkme}")
    private String relayLogin;

    @Value("${app.broker.relay-passcode:talkme_dev_pass}")
    private String relayPasscode;

    @Override
    public void configureMessageBroker(@NonNull MessageBrokerRegistry config) {
        if (relayEnabled) {
            // PRIMARY: relay /topic and /queue through RabbitMQ's STOMP plugin, so
            // every broadcast (messages, presence, typing, calls, notifications)
            // fans out across ALL app instances — the prerequisite for horizontal
            // scale. RabbitMQ handles client heartbeats natively.
            config.enableStompBrokerRelay("/topic", "/queue")
                    .setRelayHost(relayHost)
                    .setRelayPort(relayPort)
                    .setClientLogin(relayLogin)
                    .setClientPasscode(relayPasscode)
                    .setSystemLogin(relayLogin)
                    .setSystemPasscode(relayPasscode)
                    // Multi-instance user-destination resolution: an instance that
                    // doesn't hold a user's session rebroadcasts /user/** sends so
                    // the owning instance can deliver them.
                    .setUserDestinationBroadcast("/topic/unresolved-user-dest")
                    .setUserRegistryBroadcast("/topic/user-registry");
        } else {
            // FALLBACK (single instance): in-memory broker. A TaskScheduler is
            // REQUIRED for the simple broker to emit/enforce heartbeats; without it
            // the negotiated heartbeat collapses to 0 and dead connections are only
            // detected on TCP timeout (minutes). With a 25s heartbeat a stale
            // session is torn down within ~2 missed beats, firing the disconnect
            // listener that marks the user OFFLINE and stamps lastSeen.
            config.enableSimpleBroker("/topic", "/queue")
                    .setHeartbeatValue(new long[]{25000, 25000})
                    .setTaskScheduler(heartbeatScheduler());
        }
        config.setApplicationDestinationPrefixes("/app");
        config.setUserDestinationPrefix("/user");
    }

    private ThreadPoolTaskScheduler heartbeatScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("ws-heartbeat-");
        scheduler.initialize();
        return scheduler;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        String[] origins = allowedOrigins != null ?
                java.util.Arrays.stream(allowedOrigins.split(",")).map(String::trim).toArray(String[]::new) :
                new String[]{"*"};
        registry.addEndpoint("/ws", "/api/v1/ws")
                .setAllowedOriginPatterns(origins)
                .withSockJS();

        // Also register raw websocket endpoint without SockJS fallback
        registry.addEndpoint("/ws", "/api/v1/ws")
                .setAllowedOriginPatterns(origins);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // JWT auth first, then rewrite client SUBSCRIBE/SEND destinations for RabbitMQ.
        registration.interceptors(channelInterceptor, rabbitDestinationInterceptor);
    }
}
