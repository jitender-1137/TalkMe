package com.neo.chat.service;

import com.neo.chat.domain.User;
import com.neo.chat.dto.response.SleepRoomResponse;

import java.util.List;

/**
 * Calm, wind-down "sleep companion" rooms (features #26/#27). Spins up a public ROOM in
 * SLEEP_COMPANION mode — low-stimulation, ambient, and non-recorded (enforced server-side in the
 * message send path).
 */
public interface SleepRoomService {

    /**
     * Create a SLEEP_COMPANION room owned by the current user. {@code name} is optional.
     */
    SleepRoomResponse createSleepRoom(User user, String name);

    /**
     * List active sleep-companion rooms, most-recently-active first.
     */
    List<SleepRoomResponse> listSleepRooms();
}
