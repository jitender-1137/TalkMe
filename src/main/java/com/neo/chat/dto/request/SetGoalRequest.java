package com.neo.chat.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body for POST /rooms/study/{roomUuid}/goal (STUDY_ROOMS) — set the caller's shared session goal.
 * The goal text is required (non-blank) and length-capped by the service.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SetGoalRequest {

    private String text;
}
