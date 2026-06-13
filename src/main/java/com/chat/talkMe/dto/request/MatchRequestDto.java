package com.chat.talkMe.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MatchRequestDto {
    private String gender; // male, female, or empty/any
    private Integer ageMin;
    private Integer ageMax;
    private String region;
    private String interests;
}
