package com.chat.talkMe.dto.request;

import lombok.Data;

/** SuperAdmin edit-profile payload. All fields optional — only non-null ones apply. */
@Data
public class AdminUpdateUserRequest {
    private String name;
    private String bio;
    private String country;
    private String city;
    private Integer age;
    private String gender;
    private String occupation;
    private String education;
    private String mobileNumber;
    private Boolean verified;
    // Identity + account (super-admin only)
    private String email;
    private String username;
    private java.util.List<String> interests; // Interest enum names; replaces the set when non-null
    private String newPassword;                // when set, re-hashes the account password
}
