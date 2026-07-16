package com.chat.talkMe.dto.request;

import lombok.Data;

/** SuperAdmin user-list filters. All fields optional; null = no constraint. */
@Data
public class AdminUserFilter {
    private String query;          // free text over username / name / email
    private Boolean verified;
    private Boolean guest;         // true = guests, false = registered users
    private Boolean banned;
    private Boolean deleted;       // soft-deleted
    private String gender;         // MALE / FEMALE / OTHER
    private String countries; // pipe-delimited any-of match (CLDR English names), e.g. "India|United States"
    private String role;           // e.g. ROLE_MODERATOR / ROLE_SUPER_ADMIN
    private Integer minAge;
    private Integer maxAge;
    private String createdAfter;   // ISO instant or yyyy-MM-dd
    private String createdBefore;
    private String updatedAfter;
    private String updatedBefore;
    private String sort;           // createdAt | updatedAt | username | age
    private String dir;            // asc | desc (default desc)
}
