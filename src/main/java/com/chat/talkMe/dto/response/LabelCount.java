package com.chat.talkMe.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A single {label → count} bucket for breakdown charts/tables.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class LabelCount {
    private String label;
    private long count;
}
