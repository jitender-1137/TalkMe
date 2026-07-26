package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.BucketList;
import com.chat.talkMe.domain.BucketListItem;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.BucketListResponse;
import com.chat.talkMe.exception.BadRequestException;
import com.chat.talkMe.exception.ForbiddenException;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.repository.BucketListItemRepository;
import com.chat.talkMe.repository.BucketListRepository;
import com.chat.talkMe.repository.ChatMemberRepository;
import com.chat.talkMe.repository.ChatRepository;
import com.chat.talkMe.service.BucketListService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class BucketListServiceImpl implements BucketListService {

    private final BucketListRepository bucketListRepository;
    private final BucketListItemRepository bucketListItemRepository;
    private final ChatRepository chatRepository;
    private final ChatMemberRepository chatMemberRepository;
    private final SimpMessagingTemplate messagingTemplate;
    /** Self-proxy so the lazy list-create runs in its OWN transaction (see getOrCreateList). */
    private final org.springframework.beans.factory.ObjectProvider<BucketListServiceImpl> self;

    /** IDOR guard: the caller must be a member of the chat the list belongs to. */
    private void requireChatMember(User user, String chatId) {
        boolean member;
        try {
            member = chatRepository.findByUuid(UUID.fromString(chatId))
                    .flatMap(c -> chatMemberRepository.findByChatAndUser(c, user))
                    .isPresent();
        } catch (IllegalArgumentException badUuid) {
            throw new BadRequestException("Invalid chat id", "TM_400");
        }
        if (!member) {
            throw new ForbiddenException("You are not a member of this chat", "TM_103");
        }
    }

    @Override
    public BucketListResponse getList(User user, String chatId) {
        requireChatMember(user, chatId);
        BucketList list = getOrCreateList(chatId);
        return buildResponse(list);
    }

    @Override
    public BucketListResponse addItem(User user, String chatId, String text) {
        requireChatMember(user, chatId);
        if (text == null || text.isBlank()) {
            throw new BadRequestException("Item text is required", "TM_810");
        }
        BucketList list = getOrCreateList(chatId);

        // Append at the end: next index = current count.
        int nextIndex = bucketListItemRepository.findByBucketListOrderByOrderIndexAsc(list).size();
        BucketListItem item = BucketListItem.builder()
                .bucketList(list)
                .text(text.trim())
                .completed(false)
                .createdByUserId(user.getId())
                .orderIndex(nextIndex)
                .build();
        bucketListItemRepository.save(item);

        return broadcastAndBuild(chatId, list);
    }

    @Override
    public BucketListResponse toggleItem(User user, String chatId, String itemUuid) {
        requireChatMember(user, chatId);
        BucketList list = getOrCreateList(chatId);
        BucketListItem item = loadItem(list, itemUuid);

        boolean nowCompleted = !item.isCompleted();
        item.setCompleted(nowCompleted);
        if (nowCompleted) {
            item.setCompletedByUserId(user.getId());
            item.setCompletedAt(Instant.now());
        } else {
            item.setCompletedByUserId(null);
            item.setCompletedAt(null);
        }
        bucketListItemRepository.save(item);

        return broadcastAndBuild(chatId, list);
    }

    @Override
    public BucketListResponse removeItem(User user, String chatId, String itemUuid) {
        requireChatMember(user, chatId);
        BucketList list = getOrCreateList(chatId);
        BucketListItem item = loadItem(list, itemUuid);
        bucketListItemRepository.delete(item);

        return broadcastAndBuild(chatId, list);
    }

    /**
     * Fetch the chat's list, creating the single row lazily on first use. Race-safe on Postgres:
     * the INSERT runs in its OWN (REQUIRES_NEW) transaction via the self-proxy, so a losing
     * unique-constraint violation rolls back only that inner tx and never poisons the caller's
     * mutation transaction (a catch-then-reread in the SAME failed tx would itself fail on PG).
     */
    private BucketList getOrCreateList(String chatUuid) {
        BucketList existing = bucketListRepository.findByChatUuid(chatUuid).orElse(null);
        if (existing != null) {
            return existing;
        }
        try {
            return self.getObject().createListInNewTx(chatUuid);
        } catch (DataIntegrityViolationException raced) {
            // Another request created it concurrently — its row is committed; re-read the winner.
            return bucketListRepository.findByChatUuid(chatUuid).orElseThrow(() -> raced);
        }
    }

    /** Insert a fresh list row in an isolated transaction (see getOrCreateList). */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public BucketList createListInNewTx(String chatUuid) {
        return bucketListRepository.save(BucketList.builder().chatUuid(chatUuid).build());
    }

    private BucketListItem loadItem(BucketList list, String itemUuid) {
        UUID uuid;
        try {
            uuid = UUID.fromString(itemUuid);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Invalid item id", "TM_811");
        }
        return bucketListItemRepository.findByBucketListAndUuid(list, uuid)
                .orElseThrow(() -> new NotFoundException("Bucket list item not found", "TM_812"));
    }

    private BucketListResponse buildResponse(BucketList list) {
        List<BucketListItem> items = bucketListItemRepository.findByBucketListOrderByOrderIndexAsc(list);
        return BucketListResponse.from(list.getChatUuid(), items);
    }

    /** Build the fresh full list and fan it out live to the owning chat (fail-open). */
    private BucketListResponse broadcastAndBuild(String chatId, BucketList list) {
        BucketListResponse response = buildResponse(list);
        try {
            messagingTemplate.convertAndSend(
                    "/topic/chat/" + chatId + "/bucket-list",
                    (Object) Map.of("event", "bucketlist_updated", "payload", response));
        } catch (Exception e) {
            log.debug("[bucket-list] live broadcast failed for {}: {}", chatId, e.getMessage());
        }
        return response;
    }
}
