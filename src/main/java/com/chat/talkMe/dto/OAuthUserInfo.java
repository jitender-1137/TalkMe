package com.chat.talkMe.dto;

import lombok.Builder;
import lombok.Data;

/**
 * Normalised profile pulled from an external identity provider (currently Google)
 * during social login. {@code age} and {@code gender} are best-effort — Google only
 * returns them when the extra People-API scopes were granted, otherwise they are null.
 */
@Data
@Builder
public class OAuthUserInfo {
    /** Provider subject id — Google's "sub" claim. */
    private String providerId;
    private String email;
    private boolean emailVerified;
    /** Display name (Google "name"). */
    private String name;
    /** Profile image URL (Google "picture"). */
    private String picture;
    /** Best-effort — null unless the birthday scope was granted. */
    private Integer age;
    /** Best-effort — null unless the gender scope was granted. */
    private String gender;
}
