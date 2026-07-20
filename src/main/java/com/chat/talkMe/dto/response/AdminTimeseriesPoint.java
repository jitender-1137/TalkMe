package com.chat.talkMe.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;

/** One day's bucket for the dashboard charts (e.g. signups per day). */
@Data
@AllArgsConstructor
@lombok.NoArgsConstructor
public class AdminTimeseriesPoint {
    private String date;   // ISO yyyy-MM-dd (UTC)
    private long count;
}
