package io.agt.examples.live.web.tracing;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.nio.file.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class TraceStartupTest {
    @TempDir Path directory;

    @Test
    void missingModelHasSpecificCodeAndDoesNotLeaveBusyFlagSet() throws Exception {
        var models =
                new DefaultListableBeanFactory()
                        .getBeanProvider(dev.langchain4j.model.chat.ChatModel.class);
        var service = new MultiAgentTraceService(models, directory.toString());
        try {
            var mvc = MockMvcBuilders.standaloneSetup(new TraceController(service)).build();
            for (int i = 0; i < 2; i++)
                mvc.perform(post("/api/agent-traces"))
                        .andExpect(status().isServiceUnavailable())
                        .andExpect(jsonPath("$.code").value("trace_model_unavailable"));
            mvc.perform(post("/api/agent-traces").header("Origin", "https://foreign.example"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("trace_model_unavailable"));
        } finally {
            service.close();
        }
    }

    @Test
    void fullStoreHasDistinctCodeAndKeepsSavedHistory() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        for (int i = 1; i <= 100; i++) {
            String id = String.format("%032x", i);
            json.writeValue(
                    directory.resolve(id + ".json").toFile(),
                    new TraceStore.View(id, i, "completed", List.of(), List.of(), Map.of()));
        }
        var models =
                new DefaultListableBeanFactory()
                        .getBeanProvider(dev.langchain4j.model.chat.ChatModel.class);
        var service = new MultiAgentTraceService(models, directory.toString());
        try {
            var mvc = MockMvcBuilders.standaloneSetup(new TraceController(service)).build();
            mvc.perform(post("/api/agent-traces"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("trace_store_full"));
            assertEquals(100, service.store().list().size());
        } finally {
            service.close();
        }
    }
}
