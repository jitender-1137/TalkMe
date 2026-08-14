package com.neo.chat.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * A paginated media list (per-user or per-conversation) plus a small summary so the
 * admin detail pages can show a header row of totals alongside the thumbnails.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminMediaListResponse {
    private List<AdminMediaAssetView> items;
    private long total;
    private long totalBytes;
    private List<AdminMediaOwnershipResponse.Bucket> byContext; // per-context split
    private int page;
    private int size;
    private boolean hasNext;
}
