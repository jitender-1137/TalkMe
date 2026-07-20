package com.chat.talkMe.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.support.AbstractSubscribableChannel;

/**
 * Adds the {@link RabbitDestinationInterceptor} to the {@code brokerChannel} (the
 * application → broker path used by {@code SimpMessagingTemplate.convertAndSend}).
 *
 * <p>{@code WebSocketMessageBrokerConfigurer} exposes the client channels but not
 * the broker channel, so it's customized here by autowiring the bean. The
 * interceptor is a no-op unless the relay is enabled, so registering it
 * unconditionally is harmless.
 */
@Configuration
public class BrokerChannelConfig {

    @Autowired
    public void registerBrokerInterceptor(
            @Qualifier("brokerChannel") AbstractSubscribableChannel brokerChannel,
            RabbitDestinationInterceptor rabbitDestinationInterceptor) {
        brokerChannel.addInterceptor(rabbitDestinationInterceptor);
    }
}
