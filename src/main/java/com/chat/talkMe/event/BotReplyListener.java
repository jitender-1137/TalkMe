package com.chat.talkMe.event;

import com.chat.talkMe.service.BotConversationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Triggers AI-bot replies. Runs AFTER a message commits (same signal as
 * {@link MessageBroadcastListener}) but on the isolated {@code botExecutor} pool, so a
 * slow AI call + the deliberate "typing…" delay never compete with real message fan-out.
 *
 * <p>The bot's OWN reply also commits and re-fires this listener; {@link
 * BotConversationService#maybeReply} then no-ops because the sender is a bot — so bots
 * never reply to each other and there is no loop.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BotReplyListener {

    private final BotConversationService botConversationService;

    @Async("botExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMessageSent(MessageSentEvent event) {
        try {
            botConversationService.maybeReply(event);
        } catch (Exception e) {
            // A bot failing to reply must never surface anywhere — just log and move on.
            log.warn("[bot] reply listener error for chat {}: {}", event.getChatUuid(), e.getMessage());
        }
    }
}
