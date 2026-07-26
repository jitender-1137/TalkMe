package com.chat.talkMe.repository;

import com.chat.talkMe.domain.Chat;
import com.chat.talkMe.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ChatRepository extends JpaRepository<Chat, Long> {

    // ── Admin analytics ──────────────────────────────────────────────────────
    @Query("SELECT c.chatType, COUNT(c) FROM Chat c WHERE c.isDeleted = false GROUP BY c.chatType")
    java.util.List<Object[]> countGroupedByType();

    /**
     * Admin: page ALL chats, optionally filtered by type / search (chat name OR any
     * member's username or name). {@code q} must be a pre-lowered {@code %like%} or null.
     */
    @Query(value = "SELECT DISTINCT c FROM Chat c LEFT JOIN c.members m LEFT JOIN m.user u " +
            "WHERE (:type IS NULL OR c.chatType = :type) " +
            "AND (:includeDeleted = true OR c.isDeleted = false) " +
            "AND (:q IS NULL OR LOWER(c.name) LIKE :q OR LOWER(u.username) LIKE :q OR LOWER(u.name) LIKE :q)",
        countQuery = "SELECT COUNT(DISTINCT c) FROM Chat c LEFT JOIN c.members m LEFT JOIN m.user u " +
            "WHERE (:type IS NULL OR c.chatType = :type) " +
            "AND (:includeDeleted = true OR c.isDeleted = false) " +
            "AND (:q IS NULL OR LOWER(c.name) LIKE :q OR LOWER(u.username) LIKE :q OR LOWER(u.name) LIKE :q)")
    org.springframework.data.domain.Page<Chat> findForAdmin(
            @Param("type") com.chat.talkMe.enums.ChatType type,
            @Param("q") String q,
            @Param("includeDeleted") boolean includeDeleted,
            org.springframework.data.domain.Pageable pageable);

    Optional<Chat> findByUuid(UUID uuid);

    @Query("SELECT c FROM Chat c LEFT JOIN FETCH c.members m LEFT JOIN FETCH m.user WHERE c.uuid = :uuid")
    Optional<Chat> findByUuidWithMembers(@Param("uuid") UUID uuid);

    @Query("SELECT c FROM Chat c JOIN c.members m WHERE m.user = :user AND c.isDeleted = false AND m.isDeleted = false ORDER BY c.updatedAt DESC")
    List<Chat> findChatsByUser(User user);

    /** Admin: EVERY chat a user is/was a member of, including soft-deleted chats & memberships. */
    @Query("SELECT DISTINCT c FROM Chat c JOIN c.members m WHERE m.user = :user ORDER BY c.updatedAt DESC")
    List<Chat> findAllChatsByUserForAdmin(User user);

    @Query("SELECT c FROM Chat c JOIN c.members m1 JOIN c.members m2 WHERE c.chatType IN (com.chat.talkMe.enums.ChatType.PRIVATE, com.chat.talkMe.enums.ChatType.STRANGER) AND m1.user.id = :user1Id AND m2.user.id = :user2Id")
    List<Chat> findPrivateChatBetweenUsers(Long user1Id, Long user2Id);

    /**
     * Discovery: public channels/rooms of the given types, optional name search and
     * interest tag. Newest-active first.
     */
    // :pattern is a pre-lowercased "%term%" (or null). Using it directly in LIKE
    // keeps the bind type unambiguously text — concatenating a nullable param
    // inside SQL made Postgres infer bytea (lower(bytea) does not exist).
    @Query("SELECT DISTINCT c FROM Chat c LEFT JOIN c.tags t " +
           "WHERE c.visibility = com.chat.talkMe.enums.ChatVisibility.PUBLIC AND c.isDeleted = false " +
           "AND c.chatType IN :types " +
           "AND (:pattern IS NULL OR LOWER(c.name) LIKE :pattern OR LOWER(c.slug) LIKE :pattern) " +
           "AND (:tag IS NULL OR t = :tag) " +
           "ORDER BY c.updatedAt DESC")
    List<Chat> findPublicForDiscovery(@Param("types") List<com.chat.talkMe.enums.ChatType> types,
                                      @Param("pattern") String pattern,
                                      @Param("tag") com.chat.talkMe.enums.Interest tag,
                                      org.springframework.data.domain.Pageable pageable);

    /**
     * Trending public ROOMs for the Night Owl rail (feature #23): editorially-curated rooms
     * first, then most-recently-active. Bounded by {@code Pageable}.
     */
    @Query("SELECT c FROM Chat c WHERE c.isDeleted = false " +
           "AND c.chatType = com.chat.talkMe.enums.ChatType.ROOM " +
           "AND c.visibility = com.chat.talkMe.enums.ChatVisibility.PUBLIC " +
           "ORDER BY c.roomCurated DESC, c.updatedAt DESC")
    List<Chat> findTrendingRooms(org.springframework.data.domain.Pageable pageable);

    /**
     * Public ROOMs seeded into a Virtual Night City district (feature #25), newest-active first.
     */
    @Query("SELECT c FROM Chat c WHERE c.isDeleted = false " +
           "AND c.chatType = com.chat.talkMe.enums.ChatType.ROOM " +
           "AND c.cityLocation = :loc ORDER BY c.updatedAt DESC")
    List<Chat> findByCityLocation(@Param("loc") com.chat.talkMe.enums.CityLocation loc);

    /** Whether a curated room already exists for a district — idempotent seeding guard. */
    boolean existsByCityLocationAndRoomCuratedTrue(com.chat.talkMe.enums.CityLocation cityLocation);

    /**
     * Bump a chat's sort timestamp without touching the @Version column, so concurrent
     * sends to the same chat don't collide on optimistic locking. Single-column UPDATE by id.
     */
    // NOTE: no clearAutomatically — sendMessage keeps using the managed chat/message/user
    // entities (mapper + WS broadcast) after this call, so clearing the persistence context
    // here would detach them and cause LazyInitializationException.
    @Modifying
    @Query("UPDATE Chat c SET c.updatedAt = :now WHERE c.id = :chatId")
    void touchUpdatedAt(@Param("chatId") Long chatId, @Param("now") Instant now);
}
