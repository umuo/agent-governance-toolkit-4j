package io.agt.langchain4j;

import static io.agt.langchain4j.FragmentProvenanceTest.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import io.agt.core.*;
import io.agt.core.data.*;
import io.agt.core.execution.TerminationEvidence;
import io.agt.core.flow.*;
import io.agt.provenance.JdbcLabelStore;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DocumentVersionTest {
    @Test
    void duplicateTextInDifferentlyClassifiedDocumentsJoinsLabelsWithinOneRetrieval() {
        var labels = labels();
        publish(labels, 0, "duplicate", DataLabel.PUBLIC);
        labels.publishDocument(
                SOURCE,
                "secret-policy",
                0,
                Map.of("chunk", DataFragment.document("duplicate").digest()),
                DataLabel.SECRET,
                new LabelProvenance(
                        "ingestion",
                        "second-document",
                        List.of("host-policy"),
                        LabelProvenance.Kind.CLASSIFICATION));
        var first = FragmentProvenanceTest.content("duplicate", "first-backend-row");
        var second = FragmentProvenanceTest.content("duplicate", "second-backend-row");
        var bindings = new IdentityHashMap<Content, DocumentSegment>();
        bindings.put(first, reference(1));
        bindings.put(second, new DocumentSegment("secret-policy", 1, "chunk"));
        try (var control = control(labels);
                var task = control.openTaskSession(ID, limits(DataLabel.PUBLIC, DataLabel.PUBLIC));
                var binding = task.bind()) {
            var governance = new LangChain4jGovernance(control);
            var results =
                    governance
                            .guardRetriever(SOURCE, query -> List.of(first, second), bindings::get)
                            .retrieve(Query.from("query"));
            assertEquals(2, results.size());
            assertEquals(DataLabel.SECRET, task.dataFlowSnapshot().label());
            assertEquals(
                    DataLabel.SECRET,
                    labels.readFragment(SOURCE, DataFragment.document("duplicate"))
                            .orElseThrow()
                            .label());
            var calls = new AtomicInteger();
            assertThrows(
                    GovernanceException.class,
                    () -> governance.guardModel(model(calls)).chat("question"));
            assertEquals(0, calls.get());
        }
    }

    @Test
    void remoteModelResponseIsNotDeliveredIfDocumentChangesDuringNativeExecution()
            throws Exception {
        var labels = labels();
        publish(labels, 0, "one", DataLabel.PUBLIC);
        var generator = java.security.KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var keys = generator.generateKeyPair();
        var clock = java.time.Clock.systemUTC();
        var registry = new io.agt.delegation.InMemoryGrantRegistry(clock);
        var scope =
                new io.agt.core.delegation.DelegationScope(
                        "document-response",
                        true,
                        Map.of(),
                        Map.of(SOURCE, Set.of(DataOperation.RETRIEVE)));
        try (var sender = control(labels);
                var source =
                        sender.openTaskSession(
                                ID, limits(DataLabel.PUBLIC, DataLabel.PUBLIC), null, scope);
                var authority =
                        new io.agt.delegation.DelegationAuthority(
                                "urn:agt:document-test",
                                "key",
                                (java.security.interfaces.RSAPrivateKey) keys.getPrivate(),
                                registry,
                                clock);
                var root = authority.register(source);
                var verifier =
                        new io.agt.delegation.DelegationVerifier(
                                "urn:agt:document-test",
                                "worker",
                                Map.of(
                                        "key",
                                        (java.security.interfaces.RSAPublicKey) keys.getPublic()),
                                registry,
                                clock);
                var receiver = AgentControl.builder(policy()).audit(r -> {}).build()) {
            var token =
                    authority.issue(
                            root.lease(),
                            "reader",
                            "worker",
                            ID.permissions(),
                            scope,
                            java.time.Duration.ofSeconds(30));
            try (var permit =
                            verifier.accept(
                                    token, new io.agt.delegation.AuthenticatedAgent("reader"));
                    var session =
                            receiver.openAuthorizedSession(
                                    permit, new TaskLimits(CALLS, 20, 1, 1), null);
                    var binding = session.bind()) {
                var governance = new LangChain4jGovernance(receiver);
                governance
                        .guardRetriever(
                                SOURCE, query -> List.of(content("one")), item -> reference(1))
                        .retrieve(Query.from("query"));
                var calls = new AtomicInteger();
                dev.langchain4j.model.chat.ChatModel nativeModel =
                        new dev.langchain4j.model.chat.ChatModel() {
                            public dev.langchain4j.model.chat.response.ChatResponse doChat(
                                    dev.langchain4j.model.chat.request.ChatRequest request) {
                                calls.incrementAndGet();
                                publish(labels, 1, "two", DataLabel.PUBLIC);
                                return dev.langchain4j.model.chat.response.ChatResponse.builder()
                                        .aiMessage(
                                                dev.langchain4j.data.message.AiMessage.from(
                                                        "must-not-deliver"))
                                        .build();
                            }
                        };
                assertEquals(
                        "document_version_stale",
                        assertThrows(
                                        GovernanceException.class,
                                        () -> governance.guardModel(nativeModel).chat("question"))
                                .code());
                assertEquals(1, calls.get());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void addingVersionCannotEraseContentClassificationEvenWhenItChangesAfterRetrieval(
            boolean late) {
        var labels = labels();
        publish(labels, 0, "same-body", DataLabel.PUBLIC);
        var classification =
                new LabelProvenance(
                        "ingestion",
                        "classify-content",
                        List.of("host-policy"),
                        LabelProvenance.Kind.CLASSIFICATION);
        if (!late)
            labels.recordFragment(
                    SOURCE, DataFragment.document("same-body"), DataLabel.SECRET, classification);
        try (var control = control(labels);
                var task = control.openTaskSession(ID, limits(DataLabel.PUBLIC, DataLabel.PUBLIC));
                var binding = task.bind()) {
            var governance = new LangChain4jGovernance(control);
            governance
                    .guardRetriever(
                            SOURCE, query -> List.of(content("same-body")), item -> reference(1))
                    .retrieve(Query.from("query"));
            if (late)
                labels.recordFragment(
                        SOURCE,
                        DataFragment.document("same-body"),
                        DataLabel.SECRET,
                        classification);
            var calls = new AtomicInteger();
            assertEquals(
                    "data_flow_model_denied",
                    assertThrows(
                                    GovernanceException.class,
                                    () -> governance.guardModel(model(calls)).chat("question"))
                            .code());
            assertEquals(0, calls.get());
            assertEquals(DataLabel.SECRET, task.dataFlowSnapshot().label());
            assertEquals(
                    DataLabel.SECRET,
                    labels.readFragment(SOURCE, DataFragment.document("same-body", reference(1)))
                            .orElseThrow()
                            .label());
        }
    }

    static JdbcLabelStore labels() {
        var store =
                new JdbcLabelStore(
                        data("jdbc:h2:mem:document-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"),
                        2);
        store.initializeSchema();
        return store;
    }

    static DocumentVersion publish(
            JdbcLabelStore labels, long expected, String text, DataLabel label) {
        return labels.publishDocument(
                SOURCE,
                "policy",
                expected,
                Map.of("chunk", DataFragment.document(text).digest()),
                label,
                new LabelProvenance(
                        "ingestion",
                        "version-" + (expected + 1),
                        List.of("host-policy"),
                        LabelProvenance.Kind.CLASSIFICATION));
    }

    static AgentControl control(JdbcLabelStore labels) {
        return AgentControl.builder(policy()).labelStore(labels).audit(r -> {}).build();
    }

    static Content content(String text) {
        return FragmentProvenanceTest.content(text, "forged-document-public");
    }

    static DocumentSegment reference(long version) {
        return new DocumentSegment("policy", version, "chunk");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void actualPublishedClassificationOverridesForgedMetadataAndBlocksModel(boolean async)
            throws Exception {
        var labels = labels();
        publish(labels, 0, "sensitive", DataLabel.CONFIDENTIAL);
        try (var control = control(labels);
                var task = control.openTaskSession(ID, limits(DataLabel.PUBLIC, DataLabel.PUBLIC));
                var binding = task.bind()) {
            var governance = new LangChain4jGovernance(control);
            var retriever =
                    governance.guardRetriever(
                            SOURCE, query -> List.of(content("sensitive")), item -> reference(1));
            var result =
                    async
                            ? retriever.retrieveAsync(Query.from("query")).get(3, TimeUnit.SECONDS)
                            : retriever.retrieve(Query.from("query"));
            assertEquals(1, result.size());
            assertEquals(DataLabel.CONFIDENTIAL, task.dataFlowSnapshot().label());
            var nativeCalls = new AtomicInteger();
            assertEquals(
                    "data_flow_model_denied",
                    assertThrows(
                                    GovernanceException.class,
                                    () ->
                                            governance
                                                    .guardModel(model(nativeCalls))
                                                    .chat("question"))
                            .code());
            assertEquals(0, nativeCalls.get());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void replacedVersionAndChangedBodyAreRejectedBeforeRetrievalDelivery(boolean stale) {
        var labels = labels();
        publish(labels, 0, "one", DataLabel.PUBLIC);
        publish(labels, 1, "two", DataLabel.PUBLIC);
        try (var control = control(labels);
                var task = control.openTaskSession(ID, limits(DataLabel.PUBLIC, DataLabel.PUBLIC));
                var binding = task.bind()) {
            var retriever =
                    new LangChain4jGovernance(control)
                            .guardRetriever(
                                    SOURCE,
                                    query -> List.of(content("one")),
                                    item -> reference(stale ? 1 : 2));
            assertEquals(
                    stale ? "document_version_stale" : "document_segment_mismatch_denied",
                    assertThrows(
                                    GovernanceException.class,
                                    () -> retriever.retrieve(Query.from("query")))
                            .code());
        }
    }

    @Test
    void versionChangeDuringAsyncRetrievalPreventsAnyDelivery() throws Exception {
        var labels = labels();
        publish(labels, 0, "one", DataLabel.PUBLIC);
        var nativeResult = new CompletableFuture<List<Content>>();
        var entered = new CountDownLatch(1);
        ContentRetriever nativeRetriever =
                new ContentRetriever() {
                    public List<Content> retrieve(Query query) {
                        throw new AssertionError();
                    }

                    public CompletableFuture<List<Content>> retrieveAsync(Query query) {
                        entered.countDown();
                        return nativeResult;
                    }
                };
        try (var control = control(labels);
                var task = control.openTaskSession(ID, limits(DataLabel.PUBLIC, DataLabel.PUBLIC));
                var binding = task.bind()) {
            var future =
                    new LangChain4jGovernance(control)
                            .guardRetriever(SOURCE, nativeRetriever, item -> reference(1))
                            .retrieveAsync(Query.from("query"));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            publish(labels, 1, "two", DataLabel.PUBLIC);
            nativeResult.complete(List.of(content("one")));
            var error =
                    assertThrows(ExecutionException.class, () -> future.get(3, TimeUnit.SECONDS));
            assertEquals("document_version_stale", ((GovernanceException) error.getCause()).code());
        }
    }

    @Test
    void versionChangeDuringModelPreparationIsRecheckedBeforeNativeDispatch() {
        var labels = labels();
        publish(labels, 0, "one", DataLabel.PUBLIC);
        ModelTerminationContract termination =
                request -> {
                    publish(labels, 1, "two", DataLabel.PUBLIC);
                    return () -> CompletableFuture.completedFuture(TerminationEvidence.NOT_STARTED);
                };
        var options =
                new AiServiceOptions(
                        StreamDelivery.REALTIME, StreamingLimits.DEFAULT, 32, null, termination);
        try (var control = control(labels);
                var task = control.openTaskSession(ID, limits(DataLabel.PUBLIC, DataLabel.PUBLIC));
                var binding = task.bind()) {
            var governance = new LangChain4jGovernance(control, options);
            governance
                    .guardRetriever(SOURCE, query -> List.of(content("one")), item -> reference(1))
                    .retrieve(Query.from("query"));
            var calls = new AtomicInteger();
            assertEquals(
                    "document_version_stale",
                    assertThrows(
                                    GovernanceException.class,
                                    () -> governance.guardModel(model(calls)).chat("question"))
                            .code());
            assertEquals(0, calls.get());
            assertEquals(0, task.externalExecutionSnapshot().active());
            assertTrue(task.externalExecutionSnapshot().allTerminationConfirmed());
        }
    }

    @Test
    void versionedRetrieverRequiresFlowAndTrustedCatalogWithoutSilentFallback() {
        var labels = labels();
        publish(labels, 0, "one", DataLabel.PUBLIC);
        var calls = new AtomicInteger();
        ContentRetriever nativeRetriever =
                query -> {
                    calls.incrementAndGet();
                    return List.of(content("one"));
                };
        try (var control = control(labels);
                var session = control.openSession(ID);
                var binding = session.bind()) {
            var retriever =
                    new LangChain4jGovernance(control)
                            .guardRetriever(SOURCE, nativeRetriever, item -> reference(1));
            assertEquals(
                    "document_flow_required",
                    assertThrows(
                                    GovernanceException.class,
                                    () -> retriever.retrieve(Query.from("query")))
                            .code());
            assertEquals(0, calls.get());
        }
        try (var control = AgentControl.builder(policy()).audit(r -> {}).build();
                var task = control.openTaskSession(ID, limits(DataLabel.PUBLIC, DataLabel.PUBLIC));
                var binding = task.bind()) {
            var retriever =
                    new LangChain4jGovernance(control)
                            .guardRetriever(SOURCE, nativeRetriever, item -> reference(1));
            assertEquals(
                    "document_catalog_required",
                    assertThrows(
                                    GovernanceException.class,
                                    () -> retriever.retrieve(Query.from("query")))
                            .code());
        }
    }
}
