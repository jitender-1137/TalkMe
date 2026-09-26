package com.neo.chat.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import io.netty.channel.epoll.Epoll;
import lombok.extern.slf4j.Slf4j;
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
 * <p>This customizer is picked up automatically by Spring Boot's autoconfigured
 * LettuceConnectionFactory (there is no custom factory bean) and applies to every
 * profile. It:
 * <ul>
 *   <li>bounds connection establishment ({@code connectTimeout});</li>
 *   <li>enables TCP keep-alive so a dead peer is detected quickly and the client
 *       reconnects — fine-grained timing (idle/interval/count) only when a Netty
 *       native transport is present, otherwise basic {@code SO_KEEPALIVE};</li>
 *   <li>sets TCP_USER_TIMEOUT so unacked writes fail fast on a broken link — Linux
 *       (epoll/io_uring) only, since the option does not exist elsewhere;</li>
 *   <li>validates a (re)connection with PING before use ({@code pingBeforeActivateConnection});</li>
 *   <li>auto-reconnects in the background;</li>
 *   <li>enforces the command timeout from {@code spring.data.redis.timeout}.</li>
 * </ul>
 * Net effect: a WAN blip self-heals in seconds and commands fail fast instead of
 * piling into timeout storms.
 *
 * <p><b>Transport gating.</b> Lettuce 7 asserts at connection-build time that the
 * fine-grained socket options are actually supported by the selected Netty transport,
 * throwing {@code IllegalStateException} otherwise. TCP_USER_TIMEOUT requires a Linux
 * native transport (epoll/io_uring); the extended keep-alive options require any native
 * transport (epoll/io_uring/kqueue). On the default NIO transport (e.g. a macOS dev box
 * with no matching native library) neither can be applied, so we degrade gracefully to
 * plain keep-alive instead of failing every Redis connection. Production runs on Linux
 * with epoll, so it still gets the full hardening.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class RedisResilienceConfig {

    /**
     * Lettuce client customizer applied to the autoconfigured connection factory. Sets a
     * 10s connect timeout, enables TCP keep-alive (fine-grained 15s idle / 5s interval /
     * 3 probes when a native transport is available), a 30s TCP_USER_TIMEOUT on Linux,
     * auto-reconnect and PING-before-activate validation, and enforces the command timeout
     * from {@code spring.data.redis.timeout}.
     *
     * @return a {@link LettuceClientConfigurationBuilderCustomizer} hardening the Redis client.
     */
    @Bean
    public LettuceClientConfigurationBuilderCustomizer redisResilienceCustomizer() {
        // Epoll/io_uring (Linux) support TCP_USER_TIMEOUT; epoll and kqueue (macOS/BSD) support
        // the extended keep-alive options. Plain NIO supports neither — only basic SO_KEEPALIVE.
        boolean linuxNative = Epoll.isAvailable();          // io_uring not on the classpath
        boolean anyNative = linuxNative || isKqueueAvailable();

        SocketOptions.Builder socketBuilder = SocketOptions.builder()
                .connectTimeout(Duration.ofSeconds(10));

        if (anyNative) {
            socketBuilder.keepAlive(SocketOptions.KeepAliveOptions.builder()
                    .enable()
                    .idle(Duration.ofSeconds(15))
                    .interval(Duration.ofSeconds(5))
                    .count(3)
                    .build());
        } else {
            // No native transport: extended keep-alive would throw, so use basic SO_KEEPALIVE
            // (OS-default idle/interval) — still detects a dead peer, just less aggressively.
            socketBuilder.keepAlive(true);
        }

        if (linuxNative) {
            socketBuilder.tcpUserTimeout(SocketOptions.TcpUserTimeoutOptions.builder()
                    .enable()
                    .tcpUserTimeout(Duration.ofSeconds(30))
                    .build());
        }

        if (!linuxNative) {
            log.info("[redis] No Linux native transport (epoll/io_uring) — TCP_USER_TIMEOUT disabled; "
                    + "keep-alive: {}. Full hardening applies on Linux/epoll (production).",
                    anyNative ? "fine-grained (native)" : "basic SO_KEEPALIVE (NIO)");
        }

        ClientOptions clientOptions = ClientOptions.builder()
                .autoReconnect(true)
                // Validate the socket with a PING before handing it to callers, so a
                // stale/half-open connection is replaced instead of failing commands.
                .pingBeforeActivateConnection(true)
                .socketOptions(socketBuilder.build())
                // Enforce the configured command timeout (spring.data.redis.timeout).
                .timeoutOptions(TimeoutOptions.enabled())
                .build();

        return builder -> builder.clientOptions(clientOptions);
    }

    /**
     * Whether Netty's kqueue (macOS/BSD) native transport is loadable, checked reflectively so
     * the kqueue classes (a runtime-only, platform-specific artifact absent from the compile
     * classpath) are not a hard dependency. Returns {@code false} when the class or native
     * library is missing (e.g. an Apple-Silicon box without the arm64 kqueue library, or Linux).
     *
     * @return {@code true} if kqueue is available at runtime
     */
    private static boolean isKqueueAvailable() {
        try {
            Class<?> kqueue = Class.forName("io.netty.channel.kqueue.KQueue");
            return (Boolean) kqueue.getMethod("isAvailable").invoke(null);
        } catch (Throwable ignored) {
            return false;
        }
    }
}
