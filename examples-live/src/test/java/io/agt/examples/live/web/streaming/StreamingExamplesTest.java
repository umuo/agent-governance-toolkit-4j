package io.agt.examples.live.web.streaming;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.agt.core.JsonValues;
import io.agt.examples.live.web.AgentApplication;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.*;

@SpringBootTest(classes = AgentApplication.class)
@AutoConfigureMockMvc
class StreamingExamplesTest {
    @Autowired MockMvc mvc;

    @org.springframework.test.context.bean.override.mockito.MockitoBean(name = "guardrailLiveModel")
    dev.langchain4j.model.chat.StreamingChatModel live;

    @Test
    void liveModelWithoutMarkerReportsDoneAndNeverClaimsBlock() throws Exception {
        var fixture =
                new dev.langchain4j.model.chat.StreamingChatModel() {
                    @Override
                    public void doChat(
                            dev.langchain4j.model.chat.request.ChatRequest request,
                            dev.langchain4j.model.chat.response.StreamingChatResponseHandler
                                    handler) {
                        handler.onPartialResponse("安全正文没有标记");
                        handler.onCompleteResponse(
                                dev.langchain4j.model.chat.response.ChatResponse.builder()
                                        .aiMessage(
                                                dev.langchain4j.data.message.AiMessage.from(
                                                        "安全正文没有标记"))
                                        .build());
                    }
                };
        org.mockito.Mockito.when(live.defaultRequestParameters())
                .thenReturn(fixture.defaultRequestParameters());
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            fixture.chat(
                                    invocation.getArgument(
                                            0,
                                            dev.langchain4j.model.chat.request.ChatRequest.class),
                                    invocation.getArgument(
                                            1,
                                            dev.langchain4j.model.chat.response
                                                    .StreamingChatResponseHandler.class));
                            return null;
                        })
                .when(live)
                .chat(
                        org.mockito.ArgumentMatchers.any(
                                dev.langchain4j.model.chat.request.ChatRequest.class),
                        org.mockito.ArgumentMatchers.any(
                                dev.langchain4j.model.chat.response.StreamingChatResponseHandler
                                        .class));
        var run =
                mvc.perform(
                                post("/api/examples/streaming/split-word/run")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"mode\":\"GUARDED\",\"provider\":\"live\",\"message\":\"hello\"}"))
                        .andExpect(request().asyncStarted())
                        .andReturn();
        var events = events(run);
        var result =
                data(
                        events.stream()
                                .filter(e -> e.get("type").equals("result"))
                                .findFirst()
                                .orElseThrow());
        assertEquals("done", result.get("status"));
        assertEquals(false, ((Map<?, ?>) result.get("metrics")).get("triggered"));
        assertEquals("安全正文没有标记", text(events));
    }

    List<Map<String, Object>> events(MvcResult run) throws Exception {
        run.getAsyncResult(10_000);
        var finished = mvc.perform(asyncDispatch(run)).andExpect(status().isOk()).andReturn();
        return finished.getResponse()
                .getContentAsString(StandardCharsets.UTF_8)
                .lines()
                .filter(line -> line.startsWith("data:"))
                .map(line -> JsonValues.parseObject(line.substring(5)))
                .toList();
    }

    MvcResult start(String id, String mode, MockHttpSession session) throws Exception {
        return mvc.perform(
                        post("/api/examples/streaming/" + id + "/run")
                                .session(session)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        JsonValues.write(
                                                Map.of(
                                                        "mode",
                                                        mode,
                                                        "provider",
                                                        "fixture",
                                                        "message",
                                                        "test"))))
                .andExpect(request().asyncStarted())
                .andReturn();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> data(Map<String, Object> event) {
        return (Map<String, Object>) event.get("data");
    }

    static String text(List<Map<String, Object>> events) {
        return events.stream()
                .filter(e -> e.get("type").equals("text_delta"))
                .map(e -> (String) data(e).get("text"))
                .reduce("", String::concat);
    }

    @Test
    void catalogAndPackagedOfficialSdkSource() throws Exception {
        mvc.perform(get("/api/examples/streaming"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));
        mvc.perform(get("/api/examples/streaming/split-word/source"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.configuration")
                                .value(
                                        org.hamcrest.Matchers.containsString(
                                                "ChunkBufferOptions.defaults")))
                .andExpect(
                        jsonPath("$.configuration")
                                .value(
                                        org.hamcrest.Matchers.containsString(
                                                "new LangChain4jGovernance")));
        mvc.perform(get("/api/examples/streaming/missing/source")).andExpect(status().isNotFound());
    }

    @Test
    void guardedBlocksWholeSplitAndLongMarkersWhileRealtimePreviewsAndStrictWithholds()
            throws Exception {
        for (String id : List.of("split-word", "long-word"))
            for (String mode : List.of("REALTIME", "GUARDED", "STRICT")) {
                var events = events(start(id, mode, new MockHttpSession()));
                var results = events.stream().filter(e -> e.get("type").equals("result")).toList();
                assertEquals(1, results.size());
                assertEquals("blocked", data(results.get(0)).get("status"));
                String body = text(events), marker = StreamingCases.entry(id).marker();
                if (mode.equals("REALTIME")) assertTrue(body.contains(marker));
                else if (mode.equals("STRICT")) assertEquals("", body);
                else {
                    assertFalse(body.isEmpty());
                    assertTrue(StreamingCases.entry(id).fragments().get(0).startsWith(body));
                    assertFalse(body.contains(marker.substring(0, 3)));
                    assertTrue(
                            events.stream()
                                    .anyMatch(
                                            e ->
                                                    e.get("type").equals("check")
                                                            && data(e).get("stage").equals("local")
                                                            && data(e).get("verdict")
                                                                    .equals("DENY")));
                }
            }
    }

    @Test
    void safeResultHasNoDuplicateTextAcrossModes() throws Exception {
        for (String mode : List.of("REALTIME", "GUARDED", "STRICT")) {
            var events = events(start("safe", mode, new MockHttpSession()));
            assertEquals(String.join("", StreamingCases.entry("safe").fragments()), text(events));
            assertEquals(
                    "done",
                    data(events.stream()
                                    .filter(e -> e.get("type").equals("result"))
                                    .findFirst()
                                    .orElseThrow())
                            .get("status"));
        }
    }

    @Test
    void cancelBelongsToBrowserSessionAndEmitsOneTerminalResult() throws Exception {
        var session = new MockHttpSession();
        var run = start("long-word", "GUARDED", session);
        String runId = "";
        for (int i = 0; i < 100; i++) {
            String body = run.getResponse().getContentAsString(StandardCharsets.UTF_8);
            var first = body.lines().filter(l -> l.startsWith("data:")).findFirst();
            if (first.isPresent()) {
                runId =
                        (String)
                                data(JsonValues.parseObject(first.get().substring(5))).get("runId");
                break;
            }
            Thread.sleep(5);
        }
        assertFalse(runId.isEmpty());
        mvc.perform(
                        post("/api/examples/streaming/runs/" + runId + "/cancel")
                                .session(new MockHttpSession()))
                .andExpect(status().isOk());
        mvc.perform(post("/api/examples/streaming/runs/" + runId + "/cancel").session(session))
                .andExpect(status().isOk());
        var results = events(run).stream().filter(e -> e.get("type").equals("result")).toList();
        assertEquals(1, results.size());
        assertEquals("cancelled", data(results.get(0)).get("status"));
    }

    @Test
    void rejectsInvalidConfigurationAndCrossOrigin() throws Exception {
        for (String body :
                List.of("{\"mode\":\"missing\",\"provider\":\"fixture\",\"message\":\"ok\"}", "{}"))
            mvc.perform(
                            post("/api/examples/streaming/safe/run")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                    .andExpect(status().isBadRequest());
        mvc.perform(
                        post("/api/examples/streaming/safe/run")
                                .header("Origin", "https://invalid.example")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isForbidden());
    }
}
