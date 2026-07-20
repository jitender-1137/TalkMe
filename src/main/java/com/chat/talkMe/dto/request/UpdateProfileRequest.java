package com.chat.talkMe.dto.request;

import com.chat.talkMe.enums.Interest;
import com.chat.talkMe.validator.ValidAge;
import com.chat.talkMe.validator.ValidGender;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateProfileRequest {
    @Size(max = 100, message = "Name must not exceed 100 characters")
    private String name;

    @Size(max = 512, message = "Profile image URL must not exceed 512 characters")
    private String profileImage;

    @Size(max = 100, message = "Country must not exceed 100 characters")
    private String country;

    @Size(max = 100, message = "City must not exceed 100 characters")
    private String city;

    @Size(max = 30, message = "Mobile number must not exceed 30 characters")
    private String mobileNumber;

    @Size(max = 30, message = "Phone must not exceed 30 characters")
    private String phone;

    @ValidAge
    private Integer age;

    @ValidGender
    private String gender;

    @Size(max = 500, message = "Bio must not exceed 500 characters")
    private String bio;

    @Size(max = 100, message = "Occupation must not exceed 100 characters")
    private String occupation;

    @Size(max = 100, message = "Education must not exceed 100 characters")
    private String education;

    private Set<Interest> interests;
}
