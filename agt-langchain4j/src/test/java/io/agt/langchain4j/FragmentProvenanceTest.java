package io.agt.langchain4j;

import static org.junit.jupiter.api.Assertions.*;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.message.*;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import io.agt.core.*;
import io.agt.core.audit.AuditRecord;
import io.agt.core.data.*;
import io.agt.core.flow.*;
import io.agt.core.policy.*;
import io.agt.provenance.JdbcLabelStore;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FragmentProvenanceTest {
    static final TrustedContext ID =
            new TrustedContext("agent", "tenant", "user", Set.of("read", "write"));
    static final DataResource SOURCE =
            new DataResource("docs", DataResource.Kind.RETRIEVAL, "tenant", "user", "partition");
    static final DataResource MEMORY =
            new DataResource("memory", DataResource.Kind.MEMORY, "tenant", "user", "conversation");
    static final BudgetLimits CALLS = new BudgetLimits(10, 10, 60000);
    @TempDir Path directory;

    static StructuredPolicy policy(ContentRule... rules) {
        var builder =
                StructuredPolicy.builder("fragment-v1")
                        .budget(CALLS)
                        .resource(
                                "docs",
                                new DataRule(
                                        DataResource.Kind.RETRIEVAL,
                                        DataRule.Scope.PRINCIPAL,
                                        Map.of(DataOperation.RETRIEVE, Set.of("read"))))
                        .resource(
                                "memory",
                                new DataRule(
                                        DataResource.Kind.MEMORY,
                                        DataRule.Scope.PRINCIPAL,
                                        Map.of(
                                                DataOperation.MEMORY_READ,
                                                Set.of("read"),
                                                DataOperation.MEMORY_ADD,
                                                Set.of("write"),
                                                DataOperation.MEMORY_SET,
                                                Set.of("write"))));
        for (var rule : rules) builder.content(rule);
        return builder.build();
    }

    static TaskLimits limits(DataLabel input, DataLabel memory) {
        return new TaskLimits(
                CALLS,
                20,
                5,
                2,
                new DataFlowPolicy(
                        Map.of(SOURCE, DataLabel.PUBLIC, MEMORY, memory),
                        Map.of(),
                        Map.of(),
                        Set.of(),
                        input,
                        DataLabel.PUBLIC,
                        DataLabel.PUBLIC));
    }

    static JdbcDataSource data(String url) {
        var data = new JdbcDataSource();
        data.setURL(url);
        return data;
    }

    JdbcLabelStore labels() {
        var labels =
                new JdbcLabelStore(
                        data("jdbc:h2:file:" + directory.resolve("labels") + ";WRITE_DELAY=0"), 2);
        labels.initializeSchema();
        return labels;
    }

    static Content content(String text, String claimedId) {
        return Content.from(
                TextSegment.from(
                        text,
                        Metadata.from(
                                Map.of("id", claimedId, "label", "PUBLIC", "trusted", "true"))));
    }

    static ChatModel model(AtomicInteger calls) {
        return new ChatModel() {
            public ChatResponse doChat(ChatRequest request) {
                calls.incrementAndGet();
                return ChatResponse.builder().aiMessage(AiMessage.from("ok")).build();
            }
        };
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void fragmentClassificationOverridesForgedMetadataAndBlocksModelBeforeNativeCall(boolean async)
            throws Exception {
        var labels = labels();
        var sensitive = DataFragment.document("synthetic-sensitive-segment");
        labels.recordFragment(
                SOURCE,
                sensitive,
                DataLabel.CONFIDENTIAL,
                new LabelProvenance(
                        "ingestor",
                        "classification",
                        List.of("host-policy"),
                        LabelProvenance.Kind.CLASSIFICATION));
        try (var control =
                        AgentControl.builder(policy())
                                .labelStore(labels)
                                .audit(record -> {})
                                .build();
                var task = control.openTaskSession(ID, limits(DataLabel.PUBLIC, DataLabel.PUBLIC));
                var binding = task.bind()) {
            var governance = new LangChain4jGovernance(control);
            ContentRetriever source =
                    query ->
                            List.of(
                                    content(
                                            "synthetic-sensitive-segment",
                                            UUID.randomUUID().toString()));
            var wrapped = governance.guardRetriever(SOURCE, source);
            var result =
                    async
                            ? wrapped.retrieveAsync(Query.from("query")).get(5, TimeUnit.SECONDS)
                            : wrapped.retrieve(Query.from("query"));
            assertEquals(1, result.size());
            assertEquals(DataLabel.CONFIDENTIAL, task.dataFlowSnapshot().label());
            assertTrue(
                    task.dataFlowSnapshot()
                            .sources()
                            .contains("fragment:" + sensitive.key(SOURCE)));
            var calls = new AtomicInteger();
            assertEquals(
                    "data_flow_model_denied",
                    assertThrows(
                                    GovernanceException.class,
                                    () -> governance.guardModel(model(calls)).chat("request"))
                            .code());
            assertEquals(0, calls.get());
            assertTrue(
                    labels.read(SOURCE).isEmpty(),
                    "A per-fragment classification need not taint other source items");
        }
        try (var control =
                        AgentControl.builder(policy())
                                .labelStore(labels())
                                .audit(record -> {})
                                .build();
                var task = control.openTaskSession(ID, limits(DataLabel.PUBLIC, DataLabel.PUBLIC));
                var binding = task.bind()) {
            new LangChain4jGovernance(control)
                    .guardRetriever(
                            SOURCE, query -> List.of(content("public-segment", "same-claimed-id")))
                    .retrieve(Query.from("query"));
            assertEquals(DataLabel.PUBLIC, task.dataFlowSnapshot().label());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preparedMemoryWriteAndReopenedReadRetainActualMessageProducer(boolean async)
            throws Exception {
        var labels = labels();
        var nativeMemory = new Memory();
        var audit = new CopyOnWriteArrayList<AuditRecord>();
        var redact =
                new ContentRule(
                        "redact",
                        Set.of(InterventionPoint.PRE_DATA_ACCESS),
                        List.of(),
                        Map.of("secret-value", "redacted-value"));
        String producer;
        var redacted = LangChain4jFragments.message(UserMessage.from("redacted-value"));
        var original = LangChain4jFragments.message(UserMessage.from("secret-value"));
        try (var control =
                        AgentControl.builder(policy(redact))
                                .labelStore(labels)
                                .audit(audit::add)
                                .build();
                var task =
                        control.openTaskSession(
                                ID, limits(DataLabel.CONFIDENTIAL, DataLabel.SECRET));
                var binding = task.bind()) {
            producer = task.taskSnapshot().taskId();
            var wrapped = new LangChain4jGovernance(control).guardMemory(MEMORY, nativeMemory);
            if (async)
                wrapped.addAsync(List.of(UserMessage.from("secret-value")))
                        .get(5, TimeUnit.SECONDS);
            else wrapped.add(UserMessage.from("secret-value"));
            assertEquals(List.of(UserMessage.from("redacted-value")), nativeMemory.messages());
            assertTrue(labels.readFragment(MEMORY, original).isEmpty());
            var event = labels.fragmentHistory(MEMORY, redacted, 1).orElseThrow();
            assertEquals(LabelProvenance.Kind.WRITE, event.provenance().kind());
            assertEquals(producer, event.provenance().taskId());
            assertEquals(DataLabel.SECRET, event.mark().label());
            assertTrue(
                    audit.stream()
                            .anyMatch(
                                    record ->
                                            record.actionId().equals(event.provenance().actionId())
                                                    && record.point()
                                                            == InterventionPoint.PRE_DATA_ACCESS));
        }
        var reopened = labels();
        try (var control =
                        AgentControl.builder(policy())
                                .labelStore(reopened)
                                .audit(record -> {})
                                .build();
                var task = control.openTaskSession(ID, limits(DataLabel.PUBLIC, DataLabel.PUBLIC));
                var binding = task.bind()) {
            var wrapped = new LangChain4jGovernance(control).guardMemory(MEMORY, nativeMemory);
            var result =
                    async ? wrapped.messagesAsync().get(5, TimeUnit.SECONDS) : wrapped.messages();
            assertEquals(1, result.size());
            assertEquals(DataLabel.SECRET, task.dataFlowSnapshot().label());
            assertTrue(
                    task.dataFlowSnapshot()
                            .sources()
                            .contains("stored-fragment:" + redacted.key(MEMORY) + ":1"));
            assertEquals(
                    producer,
                    reopened.fragmentHistory(MEMORY, redacted, 1)
                            .orElseThrow()
                            .provenance()
                            .taskId());
        }
    }

    @Test
    void readRedactionKeepsOriginalSourceAndCapacityCannotDropUntrackedFragments() {
        var labels = labels();
        labels.recordFragment(
                SOURCE,
                DataFragment.document("secret-value"),
                DataLabel.CONFIDENTIAL,
                new LabelProvenance(
                        "ingestor",
                        "classification",
                        List.of(),
                        LabelProvenance.Kind.CLASSIFICATION));
        var redact =
                new ContentRule(
                        "redact",
                        Set.of(InterventionPoint.POST_DATA_ACCESS),
                        List.of(),
                        Map.of("secret-value", "redacted-value"));
        try (var control =
                        AgentControl.builder(policy(redact))
                                .labelStore(labels)
                                .audit(record -> {})
                                .build();
                var task = control.openTaskSession(ID, limits(DataLabel.PUBLIC, DataLabel.PUBLIC));
                var binding = task.bind()) {
            var result =
                    new LangChain4jGovernance(control)
                            .guardRetriever(SOURCE, query -> List.of(content("secret-value", "id")))
                            .retrieve(Query.from("query"));
            assertEquals("redacted-value", result.get(0).textSegment().text());
            assertEquals(DataLabel.CONFIDENTIAL, task.dataFlowSnapshot().label());
            assertTrue(
                    task.dataFlowSnapshot()
                            .sources()
                            .contains(
                                    "fragment:"
                                            + DataFragment.document("secret-value").key(SOURCE)));
            assertTrue(
                    labels.readFragment(SOURCE, DataFragment.document("redacted-value")).isEmpty());
        }
        try (var control =
                        AgentControl.builder(policy())
                                .labelStore(labels)
                                .audit(record -> {})
                                .build();
                var task = control.openTaskSession(ID, limits(DataLabel.PUBLIC, DataLabel.PUBLIC));
                var binding = task.bind()) {
            var wrapped =
                    new LangChain4jGovernance(control)
                            .guardRetriever(
                                    SOURCE,
                                    query ->
                                            java.util.stream.IntStream.range(0, 129)
                                                    .mapToObj(i -> content("document-" + i, "id"))
                                                    .toList());
            assertEquals(
                    "data_fragment_capacity_denied",
                    assertThrows(
                                    GovernanceException.class,
                                    () -> wrapped.retrieve(Query.from("query")))
                            .code());
            assertTrue(task.budgetSnapshot().failed());
        }
    }

    static final class Memory implements ChatMemory {
        private final List<ChatMessage> messages = new ArrayList<>();

        public Object id() {
            return MEMORY.instanceId();
        }

        public void add(ChatMessage message) {
            messages.add(message);
        }

        public List<ChatMessage> messages() {
            return List.copyOf(messages);
        }

        public void clear() {
            messages.clear();
        }
    }
}
