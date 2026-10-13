package com.tracelab.jenkinsloglab.scenarios;

import com.tracelab.jenkinsloglab.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Opt-in: {@code mvn test -Dfail.scenario=validation} or {@code -Pfail-validation}.
 * Posts an invalid payload and incorrectly expects HTTP 201, producing a genuine Surefire failure.
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfSystemProperty(named = "fail.scenario", matches = "validation")
class ValidationFailureScenarioTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TaskRepository taskRepository;

    @BeforeEach
    void resetStore() {
        taskRepository.clear();
    }

    @Test
    void blankTitleIsAccepted() throws Exception {
        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"\"}"))
                .andExpect(status().isCreated());
    }
}
