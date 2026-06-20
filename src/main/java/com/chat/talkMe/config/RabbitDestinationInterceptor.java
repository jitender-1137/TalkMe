package com.chat.talkMe.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

/**
 * Makes the STOMP broker relay compatible with RabbitMQ's STOMP plugin.
 *
 * <p>RabbitMQ rejects topic destinations whose routing key contains "/" (e.g.
 * {@code /topic/presence/alice}, {@code /topic/chat/<uuid>/messages}) with
 * "Invalid destination", which closes the client connection. RabbitMQ DOES accept
 * dot-delimited routing keys ({@code /topic/presence.alice}) and routes them
 * correctly (verified).
 *
 * <p>This interceptor rewrites only the segment AFTER {@code /topic/}, converting
 * "/" to "." on every frame headed to the broker — both client SUBSCRIBE/SEND
 * (clientInboundChannel) and server broadcasts (brokerChannel). The same transform
 * on subscribe and publish keeps routing consistent. App/frontend code keeps using
 * slash destinations; the client matches MESSAGE frames by subscription id, not
 * destination, so the rewrite is invisible.
 *
 * <p>IMPORTANT: the message headers must remain MUTABLE after this interceptor,
 * because {@code StompBrokerRelayMessageHandler} sets the session id on them
 * downstream. So we mutate the existing accessor in place when it is mutable, and
 * only fall back to rebuilding the message (with {@code setLeaveMutable(true)}) when
 * it is not. Calling {@code getMessageHeaders()} without leaveMutable seals the
 * headers and makes the relay fail with "Already immutable".
 *
 * <p>No-op unless the relay is enabled.
 */
@Component
public class RabbitDestinationInterceptor implements ChannelInterceptor {

    private static final String TOPIC_PREFIX = "/topic/";

    private final boolean relayEnabled;

    public RabbitDestinationInterceptor(@Value("${app.broker.relay-enabled:false}") boolean relayEnabled) {
        this.relayEnabled = relayEnabled;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        if (!relayEnabled) {
            return message;
        }
        String destination = SimpMessageHeaderAccessor.getDestination(message.getHeaders());
        if (destination == null || !destination.startsWith(TOPIC_PREFIX)) {
            return message;
        }
        String routingKey = destination.substring(TOPIC_PREFIX.length());
        if (routingKey.indexOf('/') < 0) {
            return message; // single-segment topic — already RabbitMQ-compatible
        }
        String rewritten = TOPIC_PREFIX + routingKey.replace('/', '.');

        // Preferred: mutate the existing accessor in place so the headers stay
        // mutable for the relay (which sets the session id afterwards).
        SimpMessageHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, SimpMessageHeaderAccessor.class);
        if (accessor != null && accessor.isMutable()) {
            accessor.setDestination(rewritten);
            return message;
        }

        // Fallback: wrap into a fresh accessor, rewrite, and KEEP it mutable.
        SimpMessageHeaderAccessor mutable = SimpMessageHeaderAccessor.wrap(message);
        mutable.setDestination(rewritten);
        mutable.setLeaveMutable(true);
        return MessageBuilder.createMessage(message.getPayload(), mutable.getMessageHeaders());
    }
}
