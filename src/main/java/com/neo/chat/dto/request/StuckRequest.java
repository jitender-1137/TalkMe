package com.neo.chat.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body for POST /rooms/study/{roomUuid}/stuck (STUDY_ROOMS) — the "I'm stuck" button. The
 * {@code note} is an optional short message describing what the caller is stuck on so others can
 * help; the service trims it to a safe length.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StuckRequest {

    private String note;
}
