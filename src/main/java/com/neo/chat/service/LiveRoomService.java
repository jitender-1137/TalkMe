package com.neo.chat.service;

import com.neo.chat.domain.User;
import com.neo.chat.dto.request.TranslateRequest;
import com.neo.chat.dto.response.LiveRoomResponse;
import com.neo.chat.dto.response.TranslateResponse;

import java.util.List;

/**
 * Live Rooms (Connect Wave-2): "third place" topic rooms ({@link com.neo.chat.enums.RoomMode#TOPIC})
 * and language-practice rooms ({@link com.neo.chat.enums.RoomMode#LANGUAGE_PRACTICE}).
 *
 * <p>A room is an ordinary public ROOM (built via {@link GroupService#createGroup}) flipped into
 * the relevant {@link com.neo.chat.enums.RoomMode}. Live "who's here now" presence is a per-room
 * Redis set intersected with the global online set (fail-open). Language rooms additionally expose
 * inline translation and conversation-starter suggestions.
 */
public interface LiveRoomService {

    /**
     * Create a public "third place" TOPIC room owned by the caller.
     *
     * @param user     the owner/creator
     * @param name     room name
     * @param category free-form "third place" category (coffee/gaming/study/…)
     * @param tags     optional interest tags (enum names of {@code Interest}); may be null
     */
    LiveRoomResponse createTopicRoom(User user, String name, String category, List<String> tags);

    /**
     * Create a public LANGUAGE_PRACTICE room owned by the caller. The two languages are encoded
     * into the room category (e.g. {@code "lang:HI>EN"}).
     *
     * @param user           the owner/creator
     * @param name           optional room name; blank/null ⇒ language-derived default
     * @param targetLanguage the language being practiced
     * @param nativeLanguage the native/anchor language
     */
    LiveRoomResponse createLanguageRoom(User user, String name, String targetLanguage, String nativeLanguage);

    /**
     * List active TOPIC rooms as cards.
     */
    List<LiveRoomResponse> listTopicRooms();

    /**
     * List active LANGUAGE_PRACTICE rooms as cards.
     */
    List<LiveRoomResponse> listLanguageRooms();

    /**
     * Join a live room (delegates to {@link GroupService#joinChat}). Returns the room card.
     */
    LiveRoomResponse joinRoom(User user, String roomUuid);

    /**
     * Mark the user present in a room's live roster (Redis set) and broadcast {@code user_joined}.
     * Returns the refreshed room card.
     */
    LiveRoomResponse enterRoom(User user, String roomUuid);

    /**
     * Mark the user absent from a room's live roster (Redis set) and broadcast {@code user_left}.
     */
    void leaveRoom(User user, String roomUuid);

    /**
     * Translate plaintext inside a LANGUAGE_PRACTICE room (delegates to
     * {@link TranslationService#translate}). Rejects non-language rooms.
     */
    TranslateResponse translateInRoom(User user, String roomUuid, TranslateRequest req);

    /**
     * A few conversation-starter prompts (from {@link GamePromptBank}) for a room.
     */
    List<String> suggestTopics(String roomUuid);
}
