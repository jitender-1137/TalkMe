package com.chat.talkMe.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Hardens the Lettuce Redis client for a REMOTE Redis reached over the public
 * internet (local dev + deployments point at a server, not localhost). Over a WAN,
 * the OS defaults let a dropped/half-open socket (laptop sleep, NAT idle-timeout,
 * network switch) linger for up to ~2 hours — during which every command just times
 * out. That is the recurring root cause of the reaper timeout storms.
 *
 * <p>This customizer is picked up automatically by Spring Boot's auto-configured
 * LettuceConnectionFactory (there is no custom factory bean) and applies to every
 * profile. It:
 * <ul>
 *   <li>bounds connection establishment ({@code connectTimeout});</li>
 *   <li>enables TCP keep-alive so a dead peer is detected in ~30s, not ~2h, and the
 *       client reconnects (fine-grained timing needs a netty native transport —
 *       epoll/kqueue — otherwise the OS default keep-alive interval is used);</li>
 *   <li>sets TCP_USER_TIMEOUT so unacked writes fail fast on a broken link (Linux);</li>
 *   <li>validates a (re)connection with PING before use ({@code pingBeforeActivateConnection});</li>
 *   <li>auto-reconnects in the background;</li>
 *   <li>enforces the command timeout from {@code spring.data.redis.timeout}.</li>
 * </ul>
 * Net effect: a WAN blip self-heals in seconds and commands fail fast instead of
 * piling into timeout storms.
 */
@Configuration
public class RedisResilienceConfig {

    @Bean
    public LettuceClientConfigurationBuilderCustomizer redisResilienceCustomizer() {
        SocketOptions socketOptions = SocketOptions.builder()
                .connectTimeout(Duration.ofSeconds(10))
                .keepAlive(SocketOptions.KeepAliveOptions.builder()
                        .enable()
                        .idle(Duration.ofSeconds(15))
                        .interval(Duration.ofSeconds(5))
                        .count(3)
                        .build())
                .tcpUserTimeout(SocketOptions.TcpUserTimeoutOptions.builder()
                        .enable()
                        .tcpUserTimeout(Duration.ofSeconds(30))
                        .build())
                .build();

        ClientOptions clientOptions = ClientOptions.builder()
                .autoReconnect(true)
                // Validate the socket with a PING before handing it to callers, so a
                // stale/half-open connection is replaced instead of failing commands.
                .pingBeforeActivateConnection(true)
                .socketOptions(socketOptions)
                // Enforce the configured command timeout (spring.data.redis.timeout).
                .timeoutOptions(TimeoutOptions.enabled())
                .build();

        return builder -> builder.clientOptions(clientOptions);
    }
}
