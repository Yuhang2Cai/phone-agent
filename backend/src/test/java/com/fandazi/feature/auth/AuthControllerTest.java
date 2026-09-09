package com.fandazi.feature.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.fandazi.FandaziApplication;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = FandaziApplication.class)
@AutoConfigureMockMvc
class AuthControllerTest {

    private static final String JSON = "application/json";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository users;

    @BeforeEach
    void clearUsers() {
        users.deleteAll();
    }

    @Test
    void registersUserAndReturnsTokenWithoutPassword() throws Exception {
        String response = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"email\":\"alice@example.com\",\"password\":\"password123\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.user.username").value("alice"))
                .andExpect(jsonPath("$.user.email").value("alice@example.com"))
                .andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(response).at("/user/password").isMissingNode()).isTrue();
    }

    @Test
    void rejectsDuplicateUsername() throws Exception {
        register("alice", "alice@example.com");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(JSON)
                        .content("{\"username\":\"alice\",\"email\":\"other@example.com\",\"password\":\"password123\"}"))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    @Test
    void logsInWithValidCredentials() throws Exception {
        register("alice", "alice@example.com");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(JSON)
                        .content("{\"username\":\"alice\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.user.username").value("alice"));
    }

    @Test
    void rejectsIncorrectPassword() throws Exception {
        register("alice", "alice@example.com");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(JSON)
                        .content("{\"username\":\"alice\",\"password\":\"wrong-password\"}"))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    @Test
    void rejectsMeRequestWithoutBearerToken() throws Exception {
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returnsCurrentUserForValidBearerToken() throws Exception {
        String token = register("alice", "alice@example.com");

        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.email").value("alice@example.com"));
    }

    private String register(String username, String email) throws Exception {
        String response = mockMvc.perform(post("/api/auth/register")
                        .contentType(JSON)
                        .content("{\"username\":\"%s\",\"email\":\"%s\",\"password\":\"password123\"}".formatted(username, email)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode token = objectMapper.readTree(response).get("token");
        assertThat(token).isNotNull();
        return token.asText();
    }

}
