package com.chat.talkMe.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** A single metric's time series with the resolved interval — for a per-graph query. */
@Data
@Builder
@lombok.NoArgsConstructor
@lombok.AllArgsConstructor
public class AdminTimeseriesResult {
    private String metric;        // messages / signups / attachments
    private String granularity;   // "hour" | "day" — how to format the point labels
    private String interval;      // resolved bucket size key (e.g. "1h", "1d")
    private long total;           // sum of all buckets
    private List<AdminTimeseriesPoint> points;
}
