package com.chat.talkMe.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DiscoverProfileResponse {
    private String id; // user uuid
    private String name;
    private Integer age;
    private String username;
    private String gender;
    private String bio;
    private String location;
    private String city;
    private String country;
    private String distance;
    private Double distanceKm;
    private String occupation;
    private String education;
    private Set<String> interests;
    private List<String> images;
    @JsonProperty("isVerified")
    private boolean isVerified;
    
    @JsonProperty("isOnline")
    private boolean isOnline;
    
    @JsonProperty("isLiked")
    private boolean isLiked;
    
    @JsonProperty("isFriend")
    private boolean isFriend;
    private int mutualFriendsCount;

    @JsonProperty("isRequestSent")
    private boolean isRequestSent;
    private String pendingRequestId;
    /** True when this user restricts messaging to friends — drives the avatar lock badge. */
    @JsonProperty("messagingFriendsOnly")
    private Boolean messagingFriendsOnly;
}
