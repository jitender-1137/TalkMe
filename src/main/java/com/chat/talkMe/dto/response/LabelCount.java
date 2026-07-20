package com.chat.talkMe.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;

/** A single {label → count} bucket for breakdown charts/tables. */
@Data
@AllArgsConstructor
@lombok.NoArgsConstructor
public class LabelCount {
    private String label;
    private long count;
}
