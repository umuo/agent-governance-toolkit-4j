package io.agt.examples;

import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Map;

/**
 * ASI02 / ASI05 学习演示入口。
 *
 * <p>只运行此处列出的固定场景类；每个类也可以通过 java -cp 独立运行。
 */
public final class SecurityDemos {
    private static final Map<String, List<Class<?>>> GROUPS =
            Map.of(
                    "asi02",
                            List.of(
                                    io.agt.examples.asi02.ApprovalAllowedDemo.class,
                                    io.agt.examples.asi02.ApprovalDeniedDemo.class,
                                    io.agt.examples.asi02.ApprovalExceptionDemo.class,
                                    io.agt.examples.asi02.ApprovalTimeoutDemo.class,
                                    io.agt.examples.asi02.ArgumentRewriteDemo.class,
                                    io.agt.examples.asi02.ControlledAiServiceDemo.class,
                                    io.agt.examples.asi02.DefaultDenyDemo.class,
                                    io.agt.examples.asi02.FileSideEffectDemo.class,
                                    io.agt.examples.asi02.InternalHttpDemo.class,
                                    io.agt.examples.asi02.MissingApprovalResolverDemo.class,
                                    io.agt.examples.asi02.NumericConstraintsDemo.class,
                                    io.agt.examples.asi02.PermissionDeniedDemo.class,
                                    io.agt.examples.asi02.RewriteRevalidationDemo.class,
                                    io.agt.examples.asi02.SqlBusinessAuthorizationDemo.class,
                                    io.agt.examples.asi02.StrictJsonDemo.class,
                                    io.agt.examples.asi02.TenantBindingDemo.class),
                    "asi05",
                            List.of(
                                    io.agt.examples.asi05.ExecutableIntegrityDemo.class,
                                    io.agt.examples.asi05.FixedArgumentInjectionDemo.class,
                                    io.agt.examples.asi05.InProcessJvmBoundaryDemo.class,
                                    io.agt.examples.asi05.NetworkIsolationDemo.class,
                                    io.agt.examples.asi05.ReadOnlyWorkspaceDemo.class,
                                    io.agt.examples.asi05.StrictReadIsolationDemo.class,
                                    io.agt.examples.asi05.TimeoutQuarantineDemo.class,
                                    io.agt.examples.asi05.UnapprovedExecutionToolDemo.class,
                                    io.agt.examples.asi05.WorkspaceWriteIsolationDemo.class));

    public static void main(String[] args) throws Exception {
        String group = args.length == 0 ? "all" : args[0];
        String selected = args.length < 2 ? "all" : args[1];
        if (!group.equals("all") && !GROUPS.containsKey(group)) {
            throw new IllegalArgumentException("用法：security [all|asi02|asi05] [all|场景类名]");
        }
        var candidates =
                group.equals("all")
                        ? java.util.stream.Stream.concat(
                                        GROUPS.get("asi02").stream(), GROUPS.get("asi05").stream())
                                .toList()
                        : GROUPS.get(group);
        var matching =
                candidates.stream()
                        .filter(c -> selected.equals("all") || c.getSimpleName().equals(selected))
                        .toList();
        if (matching.isEmpty()) {
            throw new IllegalArgumentException("未知场景类名");
        }
        for (var scenario : matching) {
            System.out.println("\n=== " + scenario.getName() + " ===");
            try {
                scenario.getMethod("main", String[].class).invoke(null, (Object) new String[0]);
            } catch (InvocationTargetException error) {
                if (error.getCause() instanceof Exception cause) {
                    throw cause;
                }
                throw error;
            }
        }
    }
}
