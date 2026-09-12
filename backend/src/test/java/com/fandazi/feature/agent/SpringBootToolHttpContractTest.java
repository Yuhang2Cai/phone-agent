package com.fandazi.feature.agent;

import com.fandazi.feature.address.AddressCommand;
import com.fandazi.feature.address.AddressService;
import com.fandazi.feature.auth.UserEntity;
import com.fandazi.feature.auth.UserRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class SpringBootToolHttpContractTest {
    @LocalServerPort private int port;
    @Autowired private UserRepository users;
    @Autowired private AddressService addresses;
    @Autowired private AgentSessionService sessions;

    @Test
    void pythonSpringBootToolClientAuthenticatesToTheRealInternalToolController() throws Exception {
        UserEntity user = users.save(new UserEntity("python-contract", "python-contract@example.com", "hash"));
        addresses.create(user.getId(), new AddressCommand("Contract", "13800000000", "HTTP 契约地址", true));
        AgentSession session = sessions.start(user.getId(), "python-http-contract", AgentMode.CONFIRM, "contract-trace");

        ProcessResult probe = runPythonProbe(session.getSessionId());

        assertThat(probe.exitCode()).as(probe.output()).isZero();
        assertThat(probe.output()).contains("\"ok\": true").contains("\"recipient\": \"Contract\"");
    }

    private ProcessResult runPythonProbe(String sessionId) throws Exception {
        Path projectRoot = Path.of(System.getProperty("user.dir")).getParent();
        Path sourceRoot = projectRoot.resolve("agent-service").resolve("src");
        Path script = projectRoot.resolve("backend").resolve("src").resolve("test").resolve("resources")
                .resolve("spring_tool_contract_probe.py");
        String python = System.getenv().getOrDefault("PYTHON_EXECUTABLE", "python");
        ProcessBuilder process = new ProcessBuilder(python, script.toString(), "http://127.0.0.1:" + port,
                sessionId, "test-agent-token");
        process.environment().put("PYTHONPATH", sourceRoot.toString());
        process.environment().put("NO_PROXY", "127.0.0.1,localhost");
        process.environment().put("no_proxy", "127.0.0.1,localhost");
        process.redirectErrorStream(true);
        Process running = process.start();
        boolean finished = running.waitFor(20, TimeUnit.SECONDS);
        if (!finished) {
            running.destroyForcibly();
            throw new IOException("Python SpringBootToolClient contract probe timed out");
        }
        return new ProcessResult(running.exitValue(), new String(running.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    private record ProcessResult(int exitCode, String output) {}
}
