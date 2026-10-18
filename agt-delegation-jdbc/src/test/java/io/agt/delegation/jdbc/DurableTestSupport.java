package io.agt.delegation.jdbc;

import io.agt.core.*;
import io.agt.core.data.*;
import io.agt.core.delegation.*;
import io.agt.core.flow.*;
import io.agt.core.policy.*;
import io.agt.core.usage.*;
import io.agt.delegation.*;
import io.agt.provenance.JdbcLabelStore;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.security.*;
import java.security.interfaces.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.h2.jdbcx.JdbcDataSource;

final class DurableTestSupport {
    static final String ISSUER = "urn:agt:durable-test", AUDIENCE = "worker";
    static final BudgetLimits CALLS = new BudgetLimits(10, 10, 60000);
    static final TrustedContext SOURCE =
            new TrustedContext("coordinator", "tenant", "user", Set.of("read", "write"));
    static final TrustedContext AUDITOR =
            new TrustedContext("auditor", "tenant", "user", Set.of("governance:inspect"));
    static final DataResource MEMORY =
            new DataResource("memory", DataResource.Kind.MEMORY, "tenant", "user", "stable-memory");
    static final KeyPair KEYS = keys();

    static KeyPair keys() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static ParameterRule sendArgs() {
        return ParameterRule.object(
                Map.of("destination", ParameterRule.string(null)), "destination");
    }

    static StructuredPolicy policy() {
        return StructuredPolicy.builder("durable-v1")
                .budget(CALLS)
                .tool("lookup", new ToolRule(Set.of("read"), ParameterRule.object(Map.of()), false))
                .tool("send", new ToolRule(Set.of("read"), sendArgs(), false))
                .resource(
                        "memory",
                        new DataRule(
                                DataResource.Kind.MEMORY,
                                DataRule.Scope.PRINCIPAL,
                                Map.of(
                                        DataOperation.MEMORY_READ,
                                        Set.of("read"),
                                        DataOperation.MEMORY_ADD,
                                        Set.of("write"))))
                .build();
    }

    static DelegationScope scope() {
        return new DelegationScope(
                "durable-task",
                true,
                Map.of("lookup", ParameterRule.object(Map.of()), "send", sendArgs()),
                Map.of(MEMORY, Set.of(DataOperation.MEMORY_READ, DataOperation.MEMORY_ADD)));
    }

    static DataFlowPolicy flow() {
        return new DataFlowPolicy(
                Map.of(MEMORY, DataLabel.CONFIDENTIAL),
                Map.of("lookup", DataLabel.CONFIDENTIAL),
                Map.of(
                        "send",
                        new DataFlowPolicy.SinkRule(
                                DataLabel.SECRET,
                                "destination",
                                Map.of("public", DataLabel.PUBLIC, "internal", DataLabel.SECRET),
                                Set.of())),
                Set.of(),
                DataLabel.PUBLIC,
                DataLabel.PUBLIC,
                DataLabel.PUBLIC);
    }

    static UsageLimits usage(long tokens) {
        return new UsageLimits(
                tokens,
                BigDecimal.ONE,
                "USD",
                Map.of(
                        "fixture",
                        new UsageLimits.ModelRate(
                                100, 50, BigDecimal.ONE, new BigDecimal("2"), Set.of())));
    }

    static JdbcDataSource dataSource(String url) {
        var result = new JdbcDataSource();
        result.setURL(url);
        return result;
    }

    static GrantLease lease(String id) {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return new GrantLease(id, Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
    }

    record Claimed(GrantLease lease, String session) {}

    record Input(ModelUsageReservation usageReservation) implements MeteredModelInput {}

    static String model(HostSession session, AtomicInteger effects) {
        return session.operationPrepared(
                false,
                null,
                Map.of("model", "fixture"),
                value -> new Input(session.reserveModelUsage("fixture", 100, 50)),
                value -> {
                    effects.incrementAndGet();
                    value.usageReservation().settle("fixture", 40, 10, 50);
                    return "ok";
                },
                value -> value,
                (original, value) -> (String) value);
    }

    static DelegationVerifier verifier(GrantStatusClient registry, Clock clock) {
        return new DelegationVerifier(
                ISSUER,
                AUDIENCE,
                Map.of("key-1", (RSAPublicKey) KEYS.getPublic()),
                registry,
                clock);
    }

    static final class MutableClock extends Clock {
        final AtomicLong time = new AtomicLong(System.currentTimeMillis() / 1000 * 1000);

        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId zone) {
            return this;
        }

        public long millis() {
            return time.get();
        }

        public Instant instant() {
            return Instant.ofEpochMilli(millis());
        }
    }

    static final class Fixture implements AutoCloseable {
        final MutableClock clock = new MutableClock();
        final JdbcDataSource data;
        final JdbcLabelStore labels;
        final JdbcGrantRegistry registry;
        final AgentControl control;
        final HostSession source;
        final DelegationAuthority authority;
        final DelegationAuthority.Root root;

        Fixture(Path directory, long tokens, boolean flow, boolean catalog, boolean localSpend) {
            data =
                    dataSource(
                            "jdbc:h2:file:"
                                    + directory.resolve("authority")
                                    + ";WRITE_DELAY=0;LOCK_TIMEOUT=3000");
            labels = catalog ? new JdbcLabelStore(data, 2) : null;
            if (labels != null) labels.initializeSchema();
            registry = registry();
            registry.initializeSchema();
            var builder = AgentControl.builder(policy()).audit(record -> {});
            if (labels != null) builder.labelStore(labels);
            control = builder.build();
            source =
                    control.openTaskSession(
                            SOURCE,
                            new TaskLimits(
                                    new BudgetLimits(10, 3, 60000),
                                    20,
                                    10,
                                    3,
                                    flow ? flow() : null,
                                    tokens < 0 ? null : usage(tokens)),
                            null,
                            scope());
            if (localSpend) model(source, new AtomicInteger());
            authority =
                    new DelegationAuthority(
                            ISSUER, "key-1", (RSAPrivateKey) KEYS.getPrivate(), registry, clock);
            root = authority.register(source);
        }

        JdbcGrantRegistry registry() {
            return new JdbcGrantRegistry(
                    data, "primary", clock, labels, 2, JdbcGrantRegistry.DEFAULT_MAXIMUM_BYTES);
        }

        DelegationToken token() {
            return authority.issue(
                    root.lease(),
                    "reader",
                    AUDIENCE,
                    SOURCE.permissions(),
                    scope(),
                    Duration.ofSeconds(30));
        }

        Claimed claim(String session) {
            return claim(token(), session);
        }

        Claimed claim(DelegationToken token, String session) {
            var values =
                    JsonValues.parseObject(
                            new String(
                                    Base64.getUrlDecoder().decode(token.value().split("\\.")[1]),
                                    java.nio.charset.StandardCharsets.UTF_8));
            var lease = lease((String) values.get("jti"));
            registry.claim(
                    lease.grantId(), (String) values.get("grant_hash"), "reader", AUDIENCE, lease);
            registry.bind(lease, session);
            return new Claimed(lease, session);
        }

        RegistryInspection inspect() {
            return registry().inspect(root.lease().grantId(), AUDITOR);
        }

        public void close() {
            root.close();
            authority.close();
            source.close();
            control.close();
        }
    }
}
