package com.chat.talkMe.domain;

import com.chat.talkMe.enums.ChatType;
import com.chat.talkMe.enums.ChatVisibility;
import com.chat.talkMe.enums.Interest;
import com.chat.talkMe.enums.JoinPolicy;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Entity
@Table(name = "chats", indexes = {
        // Discovery: list PUBLIC groups/channels/rooms of a given type.
        @Index(name = "idx_chats_visibility_type", columnList = "visibility, chat_type"),
        // Public @handle lookup.
        @Index(name = "idx_chats_slug", columnList = "slug")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Chat extends BaseEntity {

    @Column(name = "name", length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "chat_type", nullable = false, length = 30)
    @Builder.Default
    private ChatType chatType = ChatType.PRIVATE;

    @OneToMany(mappedBy = "chat", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<ChatMember> members = new ArrayList<>();

    // ── Group / channel / room metadata (null / defaulted for 1:1 chats) ───────

    @Column(name = "description", length = 1024)
    private String description;

    /** Group avatar image URL. */
    @Column(name = "image_url", length = 512)
    private String imageUrl;

    /**
     * Public @handle for discoverable groups/channels. Unique; nullable (Postgres
     * treats NULLs as distinct, so 1:1 and private chats — which have none — never
     * collide).
     */
    @Column(name = "slug", length = 64, unique = true)
    private String slug;

    @Enumerated(EnumType.STRING)
    @ColumnDefault("'PRIVATE'")
    @Column(name = "visibility", nullable = false, length = 20)
    @Builder.Default
    private ChatVisibility visibility = ChatVisibility.PRIVATE;

    @Enumerated(EnumType.STRING)
    @ColumnDefault("'INVITE_ONLY'")
    @Column(name = "join_policy", nullable = false, length = 20)
    @Builder.Default
    private JoinPolicy joinPolicy = JoinPolicy.INVITE_ONLY;

    /**
     * Content policy: true = mature/explicit ("non-clear") content is allowed in
     * this group; false = explicit content is hard-blocked. (Replaces the old
     * age-restricted flag — this is about content, not age.)
     */
    @ColumnDefault("false")
    @Column(name = "allow_explicit_content", nullable = false)
    @Builder.Default
    private boolean allowExplicitContent = false;

    /**
     * Membership policy: true = any user can be added; false = only the adder's
     * friends can be added. (Groups live in the Chats tab, unreachable by guests,
     * so this replaced the old allow-guests flag.)
     */
    @ColumnDefault("false")
    @Column(name = "allow_non_friends", nullable = false)
    @Builder.Default
    private boolean allowNonFriends = false;

    /** Max members (MVP cap). */
    @ColumnDefault("256")
    @Column(name = "member_limit", nullable = false)
    @Builder.Default
    private int memberLimit = 256;

    /** Denormalized owner id for cheap authz; the OWNER-role member is source of truth. */
    @Column(name = "owner_id")
    private Long ownerId;

    @Embedded
    @Builder.Default
    private ChatSettings settings = ChatSettings.builder().build();

    /** Free-form category label for room discovery. */
    @Column(name = "category", length = 64)
    private String category;

    /** Interest tags for room/group discovery (mirrors user_interests). */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "chat_tags",
            joinColumns = @JoinColumn(name = "chat_id"),
            indexes = @Index(name = "idx_chat_tags_tag", columnList = "tag"))
    @Enumerated(EnumType.STRING)
    @Column(name = "tag", length = 30)
    @Builder.Default
    private Set<Interest> tags = new HashSet<>();

    /** True for GROUP/CHANNEL/ROOM. Convenience delegate to chatType. */
    @Transient
    public boolean isMultiParty() {
        return chatType != null && chatType.isMultiParty();
    }
}
