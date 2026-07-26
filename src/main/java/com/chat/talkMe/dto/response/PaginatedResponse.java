package com.chat.talkMe.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaginatedResponse<T> {
    private List<T> items;
    private PaginationInfo pagination;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PaginationInfo {
        private String cursor;
        private boolean hasNext;
        private boolean hasPrevious;
        private Long total;
        // Page-numbered metadata (0-based page index, page size, total page count).
        // Populated for the admin page-numbered lists; left 0 for cursor-only callers.
        private int page;
        private int size;
        private int totalPages;
    }
}
