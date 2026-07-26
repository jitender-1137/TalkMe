package com.chat.talkMe.repository;

import com.chat.talkMe.domain.Chat;
import com.chat.talkMe.enums.RoomMode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Read-only lookups over {@link Chat} for the Sleep / Listening room feature (#26/#27).
 *
 * <p>Deliberately a SEPARATE Spring Data repository for the {@code Chat} entity (Spring Data
 * happily supports more than one repository per entity) so this feature can query by
 * {@code roomMode} without editing the shared {@code ChatRepository}. Mutations still go through
 * the shared {@code ChatRepository.save(...)} on a Chat loaded there.
 */
@Repository
public interface SleepRoomChatRepository extends JpaRepository<Chat, Long> {

    /** Active (non-deleted) rooms in a given behavioural mode, most-recently-active first. */
    @Query("SELECT c FROM Chat c WHERE c.roomMode = :mode AND c.isDeleted = false ORDER BY c.updatedAt DESC")
    List<Chat> findActiveByRoomMode(@Param("mode") RoomMode mode);
}
