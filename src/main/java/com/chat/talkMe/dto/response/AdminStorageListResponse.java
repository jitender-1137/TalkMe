package com.chat.talkMe.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * A page of {@link AdminStorageObjectView} plus storage-accurate counts computed over
 * the WHOLE reconciled set (not just the current page), so the gallery's filter chips
 * and totals reflect what's physically in the store.
 */
@Data
@Builder
public class AdminStorageListResponse {
    private List<AdminStorageObjectView> items;
    private Counts counts;
    private int page;
    private int size;
    private long total;         // total objects matching the active filters
    private boolean hasNext;

    @Data
    @Builder
    public static class Counts {
        private long all;
        private long image;
        private long video;
        private long voice;
        private long audio;
        private long file;
        private long linked;
        private long orphan;
        private long bytes;     // total bytes across the matched set
    }
}
