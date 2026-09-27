// 身份认证模块：验证关键行为与边界条件。
package com.fandazi.feature.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AuthControllerConflictMappingTest {

    @Mock
    private AuthService authService;

    @Test
    void mapsConcurrentRegistrationConflictToConflictResponse() throws Exception {
        when(authService.register(any()))
                .thenThrow(new AuthConflictException("Username or email is already in use"));

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new AuthController(authService)).build();

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"email\":\"alice@example.com\",\"password\":\"password123\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Username or email is already in use"));
    }
}
