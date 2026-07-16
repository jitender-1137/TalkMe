package com.chat.talkMe.mapper;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.AuthUserResponse;
import com.chat.talkMe.dto.response.UserResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface UserMapper {

    @Mapping(target = "isVerified", source = "verified")
    @Mapping(target = "isGuest", source = "guest")
    @Mapping(target = "id", expression = "java(user.getUuid().toString())")
    @Mapping(target = "avatar", source = "profileImage")
    @Mapping(target = "createdAt", expression = "java(user.getCreatedAt() != null ? user.getCreatedAt().toString() : null)")
    @Mapping(target = "presence", ignore = true)
    @Mapping(target = "lastSeen", ignore = true)
    @Mapping(target = "messagingFriendsOnly", ignore = true)
    @Mapping(target = "roles", expression = "java(mapRoleNames(user))")
    AuthUserResponse toAuthUserResponse(User user);

    @Mapping(target = "isVerified", source = "verified")
    @Mapping(target = "isGuest", source = "guest")
    @Mapping(target = "id", expression = "java(user.getUuid().toString())")
    @Mapping(target = "avatar", source = "profileImage")
    @Mapping(target = "phone", source = "mobileNumber")
    @Mapping(target = "interests", expression = "java(mapInterestsToStringSet(user.getInterests()))")
    @Mapping(target = "createdAt", expression = "java(user.getCreatedAt() != null ? user.getCreatedAt().toString() : null)")
    @Mapping(target = "updatedAt", expression = "java(user.getUpdatedAt() != null ? user.getUpdatedAt().toString() : null)")
    @Mapping(target = "isBlocked", ignore = true)
    @Mapping(target = "canMessage", ignore = true)
    @Mapping(target = "messagingFriendsOnly", ignore = true)
    @Mapping(target = "presence", ignore = true)
    @Mapping(target = "lastSeen", ignore = true)
    @Mapping(target = "followersCount", ignore = true)
    @Mapping(target = "followingCount", ignore = true)
    @Mapping(target = "postsCount", ignore = true)
    @Mapping(target = "roles", expression = "java(mapRoleNames(user))")
    UserResponse toUserResponse(User user);

    default java.util.List<String> mapRoleNames(User user) {
        if (user.getRoles() == null) return java.util.Collections.emptyList();
        return user.getRoles().stream()
                .map(com.chat.talkMe.domain.Role::getName)
                .collect(java.util.stream.Collectors.toList());
    }

    default java.util.Set<String> mapInterestsToStringSet(java.util.Set<com.chat.talkMe.enums.Interest> interests) {
        if (interests == null) return java.util.Collections.emptySet();
        java.util.Set<String> stringInterests = new java.util.HashSet<>();
        for (com.chat.talkMe.enums.Interest interest : interests) {
            stringInterests.add(interest.name());
        }
        return stringInterests;
    }
}
