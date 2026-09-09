package com.fandazi.feature.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AuthConflictIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuthService authService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @SpyBean
    private UserRepository users;

    @BeforeEach
    void createExistingUser() {
        users.deleteAll();
        users.saveAndFlush(new UserEntity("alice", "alice@example.com", passwordEncoder.encode("password123")));
    }

    @Test
    void mapsRealUsernameUniqueConstraintTo409WhenPrecheckRaces() throws Exception {
        makePrechecksMissRace();

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"email\":\"new@example.com\",\"password\":\"password123\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Username or email is already in use"));
    }

    @Test
    void mapsRealEmailUniqueConstraintTo409WhenPrecheckRaces() throws Exception {
        makePrechecksMissRace();

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"new-user\",\"email\":\"alice@example.com\",\"password\":\"password123\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Username or email is already in use"));
    }

    @Test
    void doesNotConvertUnrelatedDataIntegrityViolationToAuthConflict() {
        doThrow(new DataIntegrityViolationException("constraint [UK_APP_USERS_STATUS] violated"))
                .when(users).saveAndFlush(any(UserEntity.class));

        assertThatThrownBy(() -> authService.register(new AuthDtos.RegisterRequest(
                        "new-user", "new@example.com", "password123")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .isNotInstanceOf(AuthConflictException.class);
    }

    private void makePrechecksMissRace() {
        doReturn(false).when(users).existsByUsername(anyString());
        doReturn(false).when(users).existsByEmail(anyString());
    }
}
