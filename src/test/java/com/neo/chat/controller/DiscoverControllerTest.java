package com.neo.chat.controller;

import com.neo.chat.domain.Role;
import com.neo.chat.domain.User;
import com.neo.chat.domain.UserPresence;
import com.neo.chat.repository.RoleRepository;
import com.neo.chat.repository.UserPresenceRepository;
import com.neo.chat.repository.UserRepository;
import com.neo.chat.security.CustomUserDetails;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-stack integration test for {@link DiscoverController}.
 *
 * <p>Style: {@code @SpringBootTest} booting the full application context under the {@code test}
 * profile, with a real {@link MockMvc} built via {@code webAppContextSetup} and Spring Security
 * applied ({@link SecurityMockMvcConfigurers#springSecurity()}). Uses the real
 * {@link UserRepository}, {@link RoleRepository} and {@link UserPresenceRepository} (persisted
 * fixtures), so the security filter chain, method-security and CSRF are all live here — unlike the
 * companion standalone unit test.
 *
 * <p>The authenticated caller is installed directly into the {@link SecurityContextHolder}; each
 * test seeds its own users/presence and {@code tearDown} clears the context and wipes the tables.
 */
@SpringBootTest
@ActiveProfiles("test")
public class DiscoverControllerTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private UserPresenceRepository userPresenceRepository;

    private MockMvc mockMvc;
    private User testUser;
    private User discoverableUser;

    /**
     * Builds a security-enabled {@link MockMvc}, ensures the {@code ROLE_USER} role exists, and
     * persists two verified users: the authenticated caller ({@code testuser}) and a second
     * discoverable user. The caller is then placed into the {@link SecurityContextHolder}.
     */
    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        Role userRole = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(Role.builder().name("ROLE_USER").build()));

        testUser = User.builder()
                .username("testuser")
                .email("testuser@example.com")
                .name("Test User")
                .isGuest(false)
                .isVerified(true)
                .roles(Set.of(userRole))
                .build();
        testUser = userRepository.save(testUser);

        discoverableUser = User.builder()
                .username("discoverable")
                .email("discoverable@example.com")
                .name("Discoverable User")
                .isGuest(false)
                .isVerified(true)
                .roles(Set.of(userRole))
                .build();
        discoverableUser = userRepository.save(discoverableUser);

        CustomUserDetails userDetails = new CustomUserDetails(testUser);
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        userPresenceRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void testGetDiscover() throws Exception {
        mockMvc.perform(get("/api/v1/discover"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items[0].username").value("discoverable"))
                .andExpect(jsonPath("$.data.items[0].name").value("Discoverable User"));
    }

    /**
     * A user with {@code ONLINE} presence must sort ahead of an offline discoverable user, so the
     * online user is expected first and the offline one second in the returned page.
     */
    @Test
    void testGetDiscoverSorting() throws Exception {
        Role userRole = roleRepository.findByName("ROLE_USER").orElseThrow();

        User onlineUser = User.builder()
                .username("onlineuser")
                .email("onlineuser@example.com")
                .name("Online User")
                .isGuest(false)
                .isVerified(true)
                .roles(Set.of(userRole))
                .build();
        onlineUser = userRepository.save(onlineUser);

        UserPresence presence = UserPresence.builder()
                .user(onlineUser)
                .status("ONLINE")
                .invisibleModeEnabled(false)
                .build();
        userPresenceRepository.save(presence);

        mockMvc.perform(get("/api/v1/discover"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items[0].username").value("onlineuser"))
                .andExpect(jsonPath("$.data.items[0].name").value("Online User"))
                .andExpect(jsonPath("$.data.items[1].username").value("discoverable"))
                .andExpect(jsonPath("$.data.items[1].name").value("Discoverable User"));
    }

    /**
     * Likes then unlikes the discoverable user's profile over the live filter chain; both mutating
     * requests carry the matching CSRF cookie and {@code X-CSRF-Token} header required by the
     * security config.
     */
    @Test
    void testLikeAndUnlikeDiscoverProfile() throws Exception {
        Cookie csrfCookie = new Cookie("csrf_token", "test-token-value");
        String likedUserUuid = discoverableUser.getUuid().toString();

        // 1. Like profile
        mockMvc.perform(post("/api/v1/discover/" + likedUserUuid + "/like")
                        .cookie(csrfCookie)
                        .header("X-CSRF-Token", "test-token-value"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("User profile liked"));

        // 2. Unlike profile
        mockMvc.perform(delete("/api/v1/discover/" + likedUserUuid + "/like")
                        .cookie(csrfCookie)
                        .header("X-CSRF-Token", "test-token-value"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("User profile unliked"));
    }
}
