package com.chat.talkMe.dto.request;

import com.chat.talkMe.enums.ConversationEnergy;
import com.chat.talkMe.enums.Interest;
import com.chat.talkMe.enums.Language;
import com.chat.talkMe.enums.LookingForTag;
import com.chat.talkMe.enums.Mood;
import com.chat.talkMe.enums.PersonalityTrait;
import com.chat.talkMe.validator.ValidAge;
import com.chat.talkMe.validator.ValidGender;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;
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

    // ── Optional "About me" dropdown attributes (null ⇒ leave unchanged;
    //    empty string ⇒ clear). Backed by client-side option lists. ──
    @Size(max = 40) private String bodyType;
    @Size(max = 40) private String hairColor;
    @Size(max = 40) private String eyeColor;
    @Size(max = 40) private String relationshipStatus;
    @Size(max = 40) private String children;
    @Size(max = 40) private String drinking;
    @Size(max = 40) private String smoking;
    @Size(max = 40) private String workout;
    @Size(max = 40) private String zodiac;
    @Size(max = 40) private String religion;

    private Set<Interest> interests;

    // ── Late-Night Social attributes (null ⇒ leave unchanged; collections replace) ──
    private Mood mood;
    private ConversationEnergy conversationEnergy;
    private Set<Language> languages;
    private Set<LookingForTag> lookingFor;
    /** Personality trait → 0..100 score. Out-of-range values are clamped server-side. */
    private Map<PersonalityTrait, Integer> personality;

    /** Async voice-introduction clip (feature #16): URL from the upload endpoint + duration. */
    @Size(max = 512, message = "Voice intro URL must not exceed 512 characters")
    private String voiceIntroUrl;
    private Integer voiceIntroDurationMs;
}
