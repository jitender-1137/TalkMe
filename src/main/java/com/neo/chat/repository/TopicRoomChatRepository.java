package com.neo.chat.repository;

import com.neo.chat.domain.Chat;
import com.neo.chat.enums.RoomMode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/**
 * Read-only lookups over {@link Chat} for the Live Rooms cluster (Connect Wave-2): "third place"
 * topic rooms ({@link RoomMode#TOPIC}) and language-practice rooms
 * ({@link RoomMode#LANGUAGE_PRACTICE}).
 *
 * <p>Deliberately a SEPARATE Spring Data repository for the {@code Chat} entity (Spring Data
 * happily supports more than one repository per entity) so this feature can query by
 * {@code roomMode} without editing the shared {@code ChatRepository}. Mutations still go through
 * the shared {@code ChatRepository.save(...)} on a Chat loaded there. Mirrors
 * {@code SleepRoomChatRepository}.
 */
@Repository
public interface TopicRoomChatRepository extends JpaRepository<Chat, Long> {

    /**
     * Active (non-deleted) rooms whose {@code roomMode} is one of the given modes, most-recently
     * active first. Used to list TOPIC rooms and LANGUAGE_PRACTICE rooms independently.
     */
    @Query("SELECT c FROM Chat c WHERE c.roomMode IN :modes AND c.isDeleted = false ORDER BY c.updatedAt DESC")
    List<Chat> findActiveByRoomModeIn(@Param("modes") Collection<RoomMode> modes);
}
