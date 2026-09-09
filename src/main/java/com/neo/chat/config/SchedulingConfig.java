package com.neo.chat.config;

import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Dedicated {@code taskScheduler} for the application's {@code @Scheduled} jobs (reapers,
 * outbox redrive, digests, nightly aggregations).
 * <p>
 * Why this exists: {@code @EnableWebSocketMessageBroker} registers its own
 * {@code messageBrokerTaskScheduler} ({@code MessageBroker-*} threads, sized to the CPU count) to
 * drive STOMP heartbeats and broker relay housekeeping. Because a {@link
 * org.springframework.scheduling.TaskScheduler} bean already exists, Spring Boot's
 * {@code TaskSchedulingAutoConfiguration} backs off and does NOT create its {@code taskScheduler},
 * so every {@code @Scheduled} method silently ran on the WebSocket broker pool — a slow reaper
 * could delay heartbeats, and {@code spring.task.scheduling.*} had no effect.
 * <p>
 * {@code ScheduledAnnotationBeanPostProcessor} resolves the scheduler by the conventional bean
 * name {@code taskScheduler} when more than one {@link org.springframework.scheduling.TaskScheduler}
 * is present, so naming this bean restores the standard behaviour. Pool size, thread prefix and
 * shutdown handling come from {@code spring.task.scheduling.*} in application.yml via Boot's
 * {@link ThreadPoolTaskSchedulerBuilder} (still auto-configured; only the scheduler bean backed off).
 */
@Configuration(proxyBeanMethods = false)
public class SchedulingConfig {

    /**
     * Builds the application {@code taskScheduler} from Boot's {@code spring.task.scheduling.*}
     * properties (pool size, {@code sched-} thread prefix, graceful shutdown).
     *
     * @param builder Boot's auto-configured scheduler builder.
     * @return the initialised scheduler used for all {@code @Scheduled} methods.
     */
    @Bean(name = "taskScheduler")
    public ThreadPoolTaskScheduler taskScheduler(ThreadPoolTaskSchedulerBuilder builder) {
        return builder.build();
    }
}
