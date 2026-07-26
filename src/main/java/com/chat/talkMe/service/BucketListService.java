package com.chat.talkMe.service;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.BucketListResponse;

/**
 * Shared Bucket List engine (feature #18, BUCKET_LIST). One list per chat, created
 * lazily on first read. Every mutation broadcasts the full refreshed list over WS to
 * {@code /topic/chat/{chatId}/bucket-list}. All operations are membership-guarded.
 */
public interface BucketListService {

    /** The full list for a chat, auto-creating the list row on first use. */
    BucketListResponse getList(User user, String chatId);

    /** Append a new (open) entry to the list. */
    BucketListResponse addItem(User user, String chatId, String text);

    /** Flip an entry's completed flag, stamping/clearing who checked it off and when. */
    BucketListResponse toggleItem(User user, String chatId, String itemUuid);

    /** Remove an entry from the list. */
    BucketListResponse removeItem(User user, String chatId, String itemUuid);
}
