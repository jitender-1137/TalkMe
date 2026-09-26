package com.neo.chat.config;

import com.neo.chat.security.WebSocketChannelInterceptor;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * STOMP-over-WebSocket messaging configuration. In relay mode
 * ({@code app.broker.relay-enabled=true}) {@code /topic} and {@code /queue} are relayed
 * through RabbitMQ's STOMP plugin (enabling multi-instance fan-out and user-destination
 * resolution); otherwise a single-instance in-memory simple broker is used with a 25s
 * heartbeat backed by a dedicated scheduler. Application sends are prefixed {@code /app}
 * and user destinations {@code /user}. Registers the {@code /ws} (and {@code /api/v1/ws})
 * endpoints with and without SockJS, restricting origins to {@code app.cors.allowed-origins}.
 * The client inbound channel runs JWT auth then RabbitMQ destination rewriting, and transport
 * limits cap inbound frame size, send time, and outbound buffer to resist slow/malicious clients.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /** Localhost dev origins (any port) always allowed outside the prod profile. */
    private static final List<String> DEV_ORIGIN_PATTERNS =
            List.of("http://localhost:[*]", "http://127.0.0.1:[*]");

    private final WebSocketChannelInterceptor channelInterceptor;
    private final RabbitDestinationInterceptor rabbitDestinationInterceptor;
    private final String allowedOrigins;
    private final boolean devLike;
    private final boolean relayEnabled;
    private final String relayHost;
    private final int relayPort;
    private final String relayLogin;
    private final String relayPasscode;

    /**
     * Constructor-injects the inbound-channel interceptors and the broker/origin settings.
     *
     * @param channelInterceptor           JWT auth interceptor for STOMP CONNECT frames
     * @param rabbitDestinationInterceptor RabbitMQ destination rewriting (relay mode)
     * @param allowedOrigins               {@code app.cors.allowed-origins} (comma-separated)
     * @param relayEnabled                 {@code app.broker.relay-enabled} (default false)
     * @param relayHost                    {@code app.broker.relay-host} (default localhost)
     * @param relayPort                    {@code app.broker.relay-port} (default 61613)
     * @param relayLogin                   {@code app.broker.relay-login}
     * @param relayPasscode                {@code app.broker.relay-passcode}
     */
    public WebSocketConfig(WebSocketChannelInterceptor channelInterceptor,
                           RabbitDestinationInterceptor rabbitDestinationInterceptor,
                           @Value("${app.cors.allowed-origins:}") String allowedOrigins,
                           @Value("${spring.profiles.active:}") String activeProfiles,
                           @Value("${app.broker.relay-enabled:false}") boolean relayEnabled,
                           @Value("${app.broker.relay-host:localhost}") String relayHost,
                           @Value("${app.broker.relay-port:61613}") int relayPort,
                           @Value("${app.broker.relay-login:talkme}") String relayLogin,
                           @Value("${app.broker.relay-passcode:talkme_dev_pass}") String relayPasscode) {
        this.channelInterceptor = channelInterceptor;
        this.rabbitDestinationInterceptor = rabbitDestinationInterceptor;
        this.allowedOrigins = allowedOrigins;
        // Any non-prod run auto-allows the Next.js dev server (localhost) for the WS handshake.
        this.devLike = !activeProfiles.toLowerCase().contains("prod");
        this.relayEnabled = relayEnabled;
        this.relayHost = relayHost;
        this.relayPort = relayPort;
        this.relayLogin = relayLogin;
        this.relayPasscode = relayPasscode;
    }

    /**
     * Configures the message broker: RabbitMQ STOMP relay when the relay is enabled (with
     * client/system credentials and user-destination/registry broadcast topics), else an
     * in-memory simple broker with a 25s/25s heartbeat and dedicated scheduler. Sets the
     * {@code /app} application prefix and {@code /user} user-destination prefix.
     *
     * @param config the {@link MessageBrokerRegistry} to configure.
     */
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

    /**
     * Single-thread scheduler ({@code ws-heartbeat-*}) that drives the simple broker's
     * STOMP heartbeats. Required — without it the negotiated heartbeat collapses to 0.
     *
     * @return an initialized {@link ThreadPoolTaskScheduler}.
     */
    private ThreadPoolTaskScheduler heartbeatScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("ws-heartbeat-");
        scheduler.initialize();
        return scheduler;
    }

    /**
     * Registers the STOMP handshake endpoints {@code /ws} and {@code /api/v1/ws}, both with a
     * SockJS fallback and as raw WebSocket. Allowed origins are the trimmed
     * {@code app.cors.allowed-origins} list, plus {@code http://localhost:*} /
     * {@code http://127.0.0.1:*} outside the prod profile so the Next.js dev server can connect;
     * falls back to {@code "*"} only when nothing is configured and the profile is non-prod.
     *
     * @param registry the {@link StompEndpointRegistry} to register endpoints on.
     */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        Set<String> patterns = new LinkedHashSet<>();
        if (allowedOrigins != null && !allowedOrigins.isBlank()) {
            Arrays.stream(allowedOrigins.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .forEach(patterns::add);
        }
        if (devLike) {
            patterns.addAll(DEV_ORIGIN_PATTERNS);
        }
        if (patterns.isEmpty()) {
            patterns.add("*");
        }
        String[] origins = new ArrayList<>(patterns).toArray(new String[0]);
        registry.addEndpoint("/ws", "/api/v1/ws")
                .setAllowedOriginPatterns(origins)
                .withSockJS();

        // Also register raw websocket endpoint without SockJS fallback
        registry.addEndpoint("/ws", "/api/v1/ws")
                .setAllowedOriginPatterns(origins);
    }

    /**
     * Registers, in order, the JWT auth interceptor and the RabbitMQ destination-rewriting
     * interceptor on the client inbound channel (auth before rewrite).
     *
     * @param registration the client-inbound {@link ChannelRegistration}.
     */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // JWT auth first, then rewrite client SUBSCRIBE/SEND destinations for RabbitMQ.
        registration.interceptors(channelInterceptor, rabbitDestinationInterceptor);
    }

    /**
     * Bounds per-connection resource use to resist memory-exhaustion DoS: 64 KB max inbound
     * STOMP message, 20s send time limit, and 512 KB outbound buffer per session.
     *
     * @param registration the {@link WebSocketTransportRegistration} to configure.
     */
    @Override
    public void configureWebSocketTransport(@NonNull WebSocketTransportRegistration registration) {
        // Bound per-connection resource use to prevent memory-exhaustion DoS from a
        // malicious/slow client: cap inbound STOMP frame size, the time a single send
        // may block, and the outbound buffer that backs up behind a stuck consumer.
        registration
                .setMessageSizeLimit(64 * 1024)        // 64 KB max inbound STOMP message
                .setSendTimeLimit(20 * 1000)           // 20s to flush a send before abort
                .setSendBufferSizeLimit(512 * 1024);   // 512 KB outbound buffer per session
    }
}
