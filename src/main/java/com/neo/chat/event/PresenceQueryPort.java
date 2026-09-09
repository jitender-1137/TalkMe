package com.neo.chat.event;

import com.neo.chat.domain.User;

/**
 * Port owned by the {@code event} package for the presence query the status fan-out needs.
 * Declared here (and implemented by the concrete service in {@code com.neo.chat.service.impl})
 * so that {@code event} depends only on this local interface, avoiding an
 * {@code event → service} package cycle.
 */
public interface PresenceQueryPort {

    /**
     * Whether the user has Ghost mode on (suppresses outbound delivered/seen receipts).
     */
    boolean isGhost(User user);
}
