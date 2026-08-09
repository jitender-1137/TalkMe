package com.chat.talkMe.service;

import com.chat.talkMe.domain.Chat;
import com.chat.talkMe.domain.ChatMember;
import com.chat.talkMe.domain.ChatSettings;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.enums.MemberRole;
import com.chat.talkMe.enums.SendPolicy;
import com.chat.talkMe.exception.ForbiddenException;
import com.chat.talkMe.repository.ChatMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link GroupAuthzService} — the single place group/channel/room
 * membership, role, and send-policy decisions are made. Covers every branch of
 * {@code requireMember} (active / not-found / deleted / left / banned), {@code requireRole}
 * (sufficient / insufficient), and {@code canSend} (null / banned / muted / ADMINS_ONLY /
 * EVERYONE), asserting the exact {@code TM_###} code on every rejection.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GroupAuthzService (unit)")
class GroupAuthzServiceTest {

    @Mock
    private ChatMemberRepository chatMemberRepository;

    private GroupAuthzService service;

    private Chat chat;
    private User user;

    @BeforeEach
    void setUp() {
        service = new GroupAuthzService(chatMemberRepository);
        chat = new Chat();
        chat.setSettings(ChatSettings.builder().build());
        user = User.builder().name("Alice").username("alice").build();
        user.setId(1L);
    }

    private ChatMember member(MemberRole role) {
        ChatMember m = ChatMember.builder().chat(chat).user(user).joinedAt(Instant.now()).build();
        m.setRole(role);
        return m;
    }

    @Nested
    @DisplayName("requireMember")
    class RequireMember {

        @Test
        @DisplayName("active member → returns the membership")
        void activeMember() {
            ChatMember m = member(MemberRole.MEMBER);
            when(chatMemberRepository.findByChatAndUser(chat, user)).thenReturn(Optional.of(m));

            assertThat(service.requireMember(chat, user)).isSameAs(m);
        }

        @Test
        @DisplayName("no membership row → ForbiddenException TM_141")
        void notAMember() {
            when(chatMemberRepository.findByChatAndUser(chat, user)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.requireMember(chat, user))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_141"));
        }

        @Test
        @DisplayName("soft-deleted membership → filtered out → TM_141")
        void deletedMembership() {
            ChatMember m = member(MemberRole.MEMBER);
            m.setDeleted(true);
            when(chatMemberRepository.findByChatAndUser(chat, user)).thenReturn(Optional.of(m));

            assertThatThrownBy(() -> service.requireMember(chat, user))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_141"));
        }

        @Test
        @DisplayName("former member (leftAt set) → filtered out → TM_141")
        void formerMember() {
            ChatMember m = member(MemberRole.MEMBER);
            m.setLeftAt(Instant.now());
            when(chatMemberRepository.findByChatAndUser(chat, user)).thenReturn(Optional.of(m));

            assertThatThrownBy(() -> service.requireMember(chat, user))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_141"));
        }

        @Test
        @DisplayName("banned member → ForbiddenException TM_290")
        void bannedMember() {
            ChatMember m = member(MemberRole.MEMBER);
            m.setBanned(true);
            when(chatMemberRepository.findByChatAndUser(chat, user)).thenReturn(Optional.of(m));

            assertThatThrownBy(() -> service.requireMember(chat, user))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_290"));
        }
    }

    @Nested
    @DisplayName("requireRole")
    class RequireRole {

        @Test
        @DisplayName("role at least the minimum → returns the membership")
        void sufficientRole() {
            ChatMember m = member(MemberRole.OWNER);
            when(chatMemberRepository.findByChatAndUser(chat, user)).thenReturn(Optional.of(m));

            assertThat(service.requireRole(chat, user, MemberRole.ADMIN)).isSameAs(m);
        }

        @Test
        @DisplayName("role exactly at the minimum → allowed")
        void exactRole() {
            ChatMember m = member(MemberRole.ADMIN);
            when(chatMemberRepository.findByChatAndUser(chat, user)).thenReturn(Optional.of(m));

            assertThat(service.requireRole(chat, user, MemberRole.ADMIN)).isSameAs(m);
        }

        @Test
        @DisplayName("role below the minimum → ForbiddenException TM_291")
        void insufficientRole() {
            ChatMember m = member(MemberRole.MEMBER);
            when(chatMemberRepository.findByChatAndUser(chat, user)).thenReturn(Optional.of(m));

            assertThatThrownBy(() -> service.requireRole(chat, user, MemberRole.ADMIN))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_291"));
        }

        @Test
        @DisplayName("non-member → propagates requireMember's TM_141 before any role check")
        void notMemberPropagates() {
            when(chatMemberRepository.findByChatAndUser(chat, user)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.requireRole(chat, user, MemberRole.MEMBER))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_141"));
        }
    }

    @Nested
    @DisplayName("canSend")
    class CanSend {

        private Chat chatWith(SendPolicy policy) {
            Chat c = new Chat();
            c.setSettings(ChatSettings.builder().whoCanSend(policy).build());
            return c;
        }

        @Test
        @DisplayName("null member → false")
        void nullMember() {
            assertThat(service.canSend(chatWith(SendPolicy.EVERYONE), null)).isFalse();
        }

        @Test
        @DisplayName("banned member → false")
        void bannedMember() {
            ChatMember m = member(MemberRole.MEMBER);
            m.setBanned(true);
            assertThat(service.canSend(chatWith(SendPolicy.EVERYONE), m)).isFalse();
        }

        @Test
        @DisplayName("member muted into the future → false")
        void mutedNow() {
            ChatMember m = member(MemberRole.MEMBER);
            m.setMutedUntil(Instant.now().plusSeconds(3600));
            assertThat(service.canSend(chatWith(SendPolicy.EVERYONE), m)).isFalse();
        }

        @Test
        @DisplayName("member whose mute already elapsed → not blocked by the mute")
        void mutePast() {
            ChatMember m = member(MemberRole.MEMBER);
            m.setMutedUntil(Instant.now().minusSeconds(3600));
            assertThat(service.canSend(chatWith(SendPolicy.EVERYONE), m)).isTrue();
        }

        @Test
        @DisplayName("EVERYONE policy → a plain member may send")
        void everyoneMember() {
            assertThat(service.canSend(chatWith(SendPolicy.EVERYONE), member(MemberRole.MEMBER))).isTrue();
        }

        @Test
        @DisplayName("ADMINS_ONLY policy → a plain member may NOT send")
        void adminsOnlyMemberBlocked() {
            assertThat(service.canSend(chatWith(SendPolicy.ADMINS_ONLY), member(MemberRole.MEMBER))).isFalse();
        }

        @Test
        @DisplayName("ADMINS_ONLY policy → an admin may send")
        void adminsOnlyAdminAllowed() {
            assertThat(service.canSend(chatWith(SendPolicy.ADMINS_ONLY), member(MemberRole.ADMIN))).isTrue();
        }

        @Test
        @DisplayName("ADMINS_ONLY policy → the owner may send")
        void adminsOnlyOwnerAllowed() {
            assertThat(service.canSend(chatWith(SendPolicy.ADMINS_ONLY), member(MemberRole.OWNER))).isTrue();
        }
    }
}
