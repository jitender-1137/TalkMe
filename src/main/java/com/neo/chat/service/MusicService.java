package com.neo.chat.service;

import com.neo.chat.dto.response.MusicTrackResponse;

import java.util.List;

public interface MusicService {
    /**
     * Search the free iTunes preview catalog. Returns an empty list on any failure.
     */
    List<MusicTrackResponse> search(String query, int limit);
}
