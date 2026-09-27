// 智能助手模块：验证关键行为与边界条件。
package com.fandazi.feature.agent;

import com.fandazi.feature.address.AddressCommand;
import com.fandazi.feature.address.AddressService;
import com.fandazi.feature.auth.UserEntity;
import com.fandazi.feature.auth.UserRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class AutomationControllerTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository users;
    @Autowired private AddressService addresses;

    @Test
    void exposesLongTermAndSingleTaskAutomationConfigurationThroughAuthenticatedApi() throws Exception {
        UserEntity user = users.save(new UserEntity("automation-api", "automation-api@example.com", "hash"));
        Long addressId = addresses.create(user.getId(), new AddressCommand("Api", "13800000000", "生产配置地址", true)).getId();
        var auth = authentication(new UsernamePasswordAuthenticationToken(user.getId(), null, List.of()));

        mockMvc.perform(put("/api/automation/policy").with(auth).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"defaultAddressId\":" + addressId + ",\"maxAmount\":30}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.defaultAddressId").value(addressId));

        mockMvc.perform(post("/api/automation/task-grants").with(auth).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"api-task-session\",\"addressId\":" + addressId + ",\"maxAmount\":25,\"expiresAt\":\"2099-01-01T00:00:00Z\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.sessionId").value("api-task-session"))
                .andExpect(jsonPath("$.consumed").value(false));
    }

    @Test
    void rejectsSingleTaskConfigurationForAnotherUsersAddress() throws Exception {
        UserEntity user = users.save(new UserEntity("automation-api-owner", "automation-api-owner@example.com", "hash"));
        UserEntity other = users.save(new UserEntity("automation-api-other", "automation-api-other@example.com", "hash"));
        Long addressId = addresses.create(other.getId(), new AddressCommand("Other", "13900000000", "他人地址", true)).getId();
        var auth = authentication(new UsernamePasswordAuthenticationToken(user.getId(), null, List.of()));

        mockMvc.perform(post("/api/automation/task-grants").with(auth).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"cross-user-task\",\"addressId\":" + addressId + ",\"maxAmount\":25,\"expiresAt\":\"2099-01-01T00:00:00Z\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value(containsString("地址")));
    }
}
