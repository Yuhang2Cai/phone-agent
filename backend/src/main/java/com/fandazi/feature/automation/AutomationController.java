package com.fandazi.feature.automation;

import com.fandazi.feature.agent.AgentMode;
import com.fandazi.feature.agent.AgentSessionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;

@RestController
@RequestMapping("/api/automation")
public class AutomationController {
    public record PolicyRequest(@NotNull Boolean enabled, @NotNull Long defaultAddressId,
                                 @NotNull @DecimalMin("0.01") BigDecimal maxAmount) {}
    public record PolicyResponse(boolean enabled, Long defaultAddressId, BigDecimal maxAmount) {
        static PolicyResponse from(AutomationPolicy policy) { return new PolicyResponse(policy.isEnabled(), policy.getDefaultAddressId(), policy.getMaxAmount()); }
    }
    public record TaskGrantRequest(@NotBlank String sessionId, @NotNull Long addressId,
                                   @NotNull @DecimalMin("0.01") BigDecimal maxAmount,
                                   @NotNull @Future Instant expiresAt) {}
    public record TaskGrantResponse(String sessionId, Long addressId, BigDecimal maxAmount, Instant expiresAt,
                                    boolean consumed) {
        static TaskGrantResponse from(AutomationGrant grant) { return new TaskGrantResponse(grant.getSessionId(), grant.getAddressId(), grant.getMaxAmount(), grant.getExpiresAt(), grant.isConsumed()); }
    }

    private final AutomationPolicyService policies;
    private final AgentSessionService sessions;
    public AutomationController(AutomationPolicyService policies, AgentSessionService sessions) { this.policies = policies; this.sessions = sessions; }

    @GetMapping("/policy")
    public PolicyResponse policy(@AuthenticationPrincipal Long userId) {
        AutomationPolicy policy = policies.policy(userId);
        return policy == null ? null : PolicyResponse.from(policy);
    }

    @PutMapping("/policy")
    public PolicyResponse upsertPolicy(@AuthenticationPrincipal Long userId, @Valid @RequestBody PolicyRequest request) {
        return PolicyResponse.from(policies.upsert(userId, request.enabled(), request.defaultAddressId(), request.maxAmount()));
    }

    @PostMapping("/task-grants")
    @ResponseStatus(HttpStatus.CREATED)
    public TaskGrantResponse grantForTask(@AuthenticationPrincipal Long userId, @Valid @RequestBody TaskGrantRequest request) {
        sessions.start(userId, request.sessionId(), AgentMode.THIS_TASK_AUTO, UUID.randomUUID().toString());
        return TaskGrantResponse.from(policies.grantForTask(userId, request.sessionId(), request.addressId(), request.maxAmount(), request.expiresAt()));
    }

    @GetMapping("/task-grants")
    public TaskGrantResponse taskGrant(@AuthenticationPrincipal Long userId, @org.springframework.web.bind.annotation.RequestParam String sessionId) {
        sessions.owned(userId, sessionId);
        AutomationGrant grant = policies.grant(userId, sessionId);
        return grant == null ? null : TaskGrantResponse.from(grant);
    }
}
