package com.chat.talkMe.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateGroupRequest {
    @NotBlank(message = "Group name is required")
    @Size(max = 100)
    private String name;

    @Size(max = 1024)
    private String description;

    private String imageUrl;

    /** Initial member user uuids (creator is added as OWNER automatically). */
    private List<String> memberIds;

    /** "group" | "channel" | "room" (defaults to group). */
    private String subtype;

    /** Room/channel discovery category (free-form label). */
    private String category;

    /** Room/channel interest tags (enum names of com.chat.talkMe.enums.Interest). */
    private java.util.List<String> tags;

    /** true = any user can be added; false/null = only the creator's friends. */
    private Boolean allowNonFriends;

    /** true = mature/explicit ("non-clear") content allowed; false/null = hard-blocked. */
    private Boolean allowExplicitContent;

    /** PRIVATE | PUBLIC (defaults to PRIVATE). */
    private String visibility;
}
