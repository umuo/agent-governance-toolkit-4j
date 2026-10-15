package io.agt.examples.live.console;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolExecutor;
import io.agt.core.audit.AuditRecord;
import io.agt.core.audit.AuditSink;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 展示真实治理事件与工具执行，不读取模型隐藏推理。
 *
 * <p>工具摘要只显示到本地终端，持久审计仍仅写动作摘要。
 */
public final class TerminalTrace implements AuditSink {
    private final AuditSink audit;
    private final PrintWriter output;

    public TerminalTrace(AuditSink audit, PrintWriter output) {
        this.audit = audit;
        this.output = output;
    }

    public synchronized void event(String text) {
        output.println(text);
        output.flush();
    }

    public void failure(String stage, Throwable error) {
        var buffer = new StringWriter();
        error.printStackTrace(new PrintWriter(buffer));
        event("[异常] " + stage + "\n" + buffer);
    }

    /** 在核心将异常转换为安全原因码之前记录诊断；不安装原始模型 listener。 */
    public ChatModel model(ChatModel delegate) {
        return (ChatModel)
                Proxy.newProxyInstance(
                        ChatModel.class.getClassLoader(),
                        new Class<?>[] {ChatModel.class},
                        (proxy, method, arguments) -> {
                            try {
                                return method.invoke(delegate, arguments);
                            } catch (InvocationTargetException wrapped) {
                                Throwable cause = wrapped.getCause();
                                failure("模型调用 / " + method.getName(), cause);
                                throw cause;
                            }
                        });
    }

    public static String preview(String text) {
        if (text == null) {
            return "";
        }
        String safe = text.replaceAll("[\\p{Cc}&&[^\\n\\t]]", "?");
        return safe.length() <= 1200 ? safe : safe.substring(0, 1200) + " …[已截断]";
    }

    @Override
    public void write(AuditRecord record) throws Exception {
        audit.write(record);
        if (java.util.Set.of("DENY", "ERROR").contains(record.verdict())
                || !record.approvalOutcome().equals("not_requested")) {
            event(
                    "[AGT] "
                            + record.point().wireName()
                            + " "
                            + record.reasonCode()
                            + " approval="
                            + record.approvalOutcome());
        }
        if (record.verdict().equals("ALLOW")) {
            switch (record.point()) {
                case PRE_MODEL_CALL -> event("[模型] 请求中…");
                case POST_MODEL_CALL -> event("[模型] 已收到响应");
                default -> {}
            }
        }
    }

    public Map<ToolSpecification, ToolExecutor> tools(Object object) {
        var registered = new LinkedHashMap<ToolSpecification, ToolExecutor>();
        for (var method : object.getClass().getMethods()) {
            if (!method.isAnnotationPresent(Tool.class)) {
                continue;
            }
            var specification = ToolSpecifications.toolSpecificationFrom(method);
            var delegate = new DefaultToolExecutor(object, method);
            registered.put(
                    specification,
                    (request, memoryId) -> {
                        long start = System.nanoTime();
                        event("[工具 →] " + request.name() + " " + preview(request.arguments()));
                        try {
                            String result = delegate.execute(request, memoryId);
                            event(
                                    "[工具 ←] "
                                            + request.name()
                                            + " "
                                            + (System.nanoTime() - start) / 1_000_000
                                            + "ms\n"
                                            + preview(result));
                            return result;
                        } catch (RuntimeException error) {
                            failure("工具 " + request.name(), error);
                            event(
                                    "[工具失败] "
                                            + request.name()
                                            + " ("
                                            + error.getClass().getSimpleName()
                                            + ")");
                            throw error;
                        }
                    });
        }
        return registered;
    }
}
