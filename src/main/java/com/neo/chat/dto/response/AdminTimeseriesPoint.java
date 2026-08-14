package com.neo.chat.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One day's bucket for the dashboard charts (e.g. signups per day).
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class AdminTimeseriesPoint {
    private String date;   // ISO yyyy-MM-dd (UTC)
    private long count;
}
