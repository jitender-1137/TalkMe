package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.Chat;
import com.chat.talkMe.domain.ChatMember;
import com.chat.talkMe.domain.Message;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.enums.ChatType;
import com.chat.talkMe.enums.MessageType;
import com.chat.talkMe.event.MessageSentEvent;
import com.chat.talkMe.repository.BlockUserRepository;
import com.chat.talkMe.repository.ChatMemberRepository;
import com.chat.talkMe.repository.ChatRepository;
import com.chat.talkMe.repository.MessageRepository;
import com.chat.talkMe.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Loads the context a bot needs to reply — in a single read transaction, so lazy
 * associations ({@code chat.members}) resolve safely. Kept as its OWN bean (not a
 * method on {@link BotConversationServiceImpl}) so Spring's transactional proxy
 * actually applies: a self-invoked {@code @Transactional} method would run with no
 * transaction and blow up on lazy access.
 */
@Component
@RequiredArgsConstructor
public class BotTurnResolver {

    private final ChatRepository chatRepository;
    private final ChatMemberRepository chatMemberRepository;
    private final MessageRepository messageRepository;
    private final UserRepository userRepository;
    private final BlockUserRepository blockUserRepository;

    @Value("${app.bot.context-messages:12}")
    private int contextMessages;

    /** Detached holder handed to the (untransacted) reply step. Only simple fields of {@code bot} are read afterwards. */
    public record BotTurn(User bot, List<String[]> turns) {}

    /**
     * @return the bot + chronological chat turns to reply to, or {@code null} when this
     *         event must NOT trigger a bot reply (not a bot chat, blocked, bot's turn, …).
     */
    @Transactional(readOnly = true)
    public BotTurn resolve(MessageSentEvent event) {
        // GUARD: only human-sent messages trigger a reply → bots never talk to each other.
        User sender = event.getSenderUserId() == null ? null
                : userRepository.findById(event.getSenderUserId()).orElse(null);
        if (sender == null || sender.isBot()) return null;

        Chat chat = chatRepository.findByUuid(UUID.fromString(event.getChatUuid())).orElse(null);
        if (chat == null || chat.getChatType() != ChatType.PRIVATE) return null;

        // The other member must be a bot (a 1:1 chat has exactly one other member).
        User bot = chat.getMembers().stream()
                .map(ChatMember::getUser)
                .filter(u -> u != null && u.isBot() && !u.getId().equals(sender.getId()))
                .findFirst()
                .orElse(null);
        if (bot == null) return null;

        // Respect blocking in either direction.
        if (blockUserRepository.existsByUserAndBlocked(sender, bot)
                || blockUserRepository.existsByUserAndBlocked(bot, sender)) {
            return null;
        }

        ChatMember botMember = chatMemberRepository.findByChatAndUser(chat, bot).orElse(null);
        if (botMember == null) return null;

        // Recent conversation from the bot's perspective (visibility-filtered), newest first.
        List<Message> recent = messageRepository.findMessagesBeforeCursor(
                chat, bot.getId(), botMember.getClearedAt(), null,
                PageRequest.of(0, Math.max(2, contextMessages)));

        // Chronological [role, text] turns. The bot's own past messages are the OpenAI
        // "assistant" role; everyone else is "user".
        List<String[]> turns = new ArrayList<>();
        for (int i = recent.size() - 1; i >= 0; i--) {
            Message m = recent.get(i);
            if (m.getMessageType() != MessageType.TEXT) continue;
            String text = m.getContent() == null ? "" : m.getContent().replaceAll("\\s+", " ").trim();
            if (text.isEmpty()) continue;
            if (text.length() > 1000) text = text.substring(0, 1000);
            boolean fromBot = m.getSender() != null && m.getSender().getId().equals(bot.getId());
            turns.add(new String[]{fromBot ? "assistant" : "user", text});
        }

        // Nothing to reply to, or the bot already had the last word → stay quiet.
        if (turns.isEmpty() || "assistant".equals(turns.get(turns.size() - 1)[0])) return null;

        return new BotTurn(bot, turns);
    }
}
