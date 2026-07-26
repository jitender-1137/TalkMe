package com.chat.talkMe.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

/** The set of feature wire-names the authenticated user may use. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeatureAccessResponse {
    private Set<String> features;
}
