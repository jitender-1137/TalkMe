package com.chat.talkMe.mapper;

import com.chat.talkMe.domain.Role;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.AuthUserResponse;
import com.chat.talkMe.dto.response.UserResponse;
import com.chat.talkMe.enums.Interest;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * MapStruct mapper from a {@link com.chat.talkMe.domain.User} entity to its response DTOs:
 * {@link com.chat.talkMe.dto.response.AuthUserResponse} (self) and
 * {@link com.chat.talkMe.dto.response.UserResponse} (other users). Renders UUIDs/enums/timestamps
 * as strings, converts interest/language/looking-for enum sets to name sets, and ignores fields
 * enriched later by the service layer (presence, last seen, block/friend/messaging flags, counts,
 * and self-only features). Default methods implement the collection/role conversions.
 */
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
    @Mapping(target = "features", ignore = true) // self-only; enriched in AuthServiceImpl, never here
    @Mapping(target = "interests", expression = "java(mapInterestsToStringSet(user.getInterests()))")
    @Mapping(target = "mood", expression = "java(user.getMood() != null ? user.getMood().name() : null)")
    @Mapping(target = "conversationEnergy", expression = "java(user.getConversationEnergy() != null ? user.getConversationEnergy().name() : null)")
    @Mapping(target = "languages", expression = "java(mapEnumSet(user.getLanguages()))")
    @Mapping(target = "lookingFor", expression = "java(mapEnumSet(user.getLookingFor()))")
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
    @Mapping(target = "mood", expression = "java(user.getMood() != null ? user.getMood().name() : null)")
    @Mapping(target = "conversationEnergy", expression = "java(user.getConversationEnergy() != null ? user.getConversationEnergy().name() : null)")
    @Mapping(target = "languages", expression = "java(mapEnumSet(user.getLanguages()))")
    @Mapping(target = "lookingFor", expression = "java(mapEnumSet(user.getLookingFor()))")
    UserResponse toUserResponse(User user);

    default List<String> mapRoleNames(User user) {
        if (user.getRoles() == null) return Collections.emptyList();
        return user.getRoles().stream()
                .map(Role::getName)
                .collect(Collectors.toList());
    }

    default Set<String> mapInterestsToStringSet(Set<Interest> interests) {
        if (interests == null) return Collections.emptySet();
        Set<String> stringInterests = new HashSet<>();
        for (Interest interest : interests) {
            stringInterests.add(interest.name());
        }
        return stringInterests;
    }

    /**
     * Generic enum-set → name-set (languages, looking-for).
     */
    default <E extends Enum<E>> Set<String> mapEnumSet(Set<E> values) {
        if (values == null) return Collections.emptySet();
        Set<String> out = new LinkedHashSet<>();
        for (E v : values) out.add(v.name());
        return out;
    }
}
