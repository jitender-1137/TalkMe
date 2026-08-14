package com.neo.chat.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Dedicated thread pool for post-commit async work: WebSocket broadcast,
 * RabbitMQ publish, and Redis cache updates. Isolated from Spring's default
 * SimpleAsyncTaskExecutor so message fan-out never competes with scheduled jobs
 * or other @Async tasks.
 * <p>
 * Sizing: 8 core / 32 max / 10 000 queue — enough to absorb bursts of concurrent
 * sends without shedding load. CallerRunsPolicy is the backstop: under extreme
 * saturation the HTTP thread does the broadcast itself (same latency as the old
 * synchronous path) rather than dropping work.
 */
@Configuration
public class AsyncConfig {

    /**
     * The {@code broadcastExecutor} thread pool for post-commit fan-out (WebSocket/RabbitMQ/
     * Redis). Sized 8 core / 32 max / 10,000 queue with a {@code CallerRunsPolicy} backstop,
     * threads named {@code broadcast-*}. On shutdown, it waits up to 10s for in-flight tasks so
     * already-committed messages are not dropped mid-delivery.
     *
     * @return the initialized broadcast {@link Executor}.
     */
    @Bean(name = "broadcastExecutor")
    public Executor broadcastExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(32);
        executor.setQueueCapacity(10_000);
        executor.setThreadNamePrefix("broadcast-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // Wait up to 10s for in-flight broadcasts to finish on graceful shutdown
        // so messages already committed to DB are not silently dropped mid-delivery.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }
}
