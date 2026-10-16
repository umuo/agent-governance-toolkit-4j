package io.agt.langchain4j.internal.service;

import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.service.tool.BeforeToolExecution;
import dev.langchain4j.service.tool.ToolExecution;
import io.agt.core.GovernanceException;
import io.agt.langchain4j.internal.message.TextProjection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 原生事件与 Reactive 结果中框架类型的专用投影。
 *
 * <p>消息正文和业务参数进入披露检查；计量、调用标识和宿主管理元数据保持身份不变。
 */
public final class FrameworkValues {
    private FrameworkValues() {}

    public static boolean supports(Object value) {
        return value instanceof dev.langchain4j.model.chat.response.RawStreamingEvent
                || GuardrailValues.supports(value)
                || value instanceof dev.langchain4j.service.AiServiceStreamingEvent
                || value instanceof dev.langchain4j.model.chat.response.PartialResponse
                || value instanceof dev.langchain4j.model.chat.response.PartialThinking
                || value instanceof dev.langchain4j.model.chat.response.PartialToolCall
                || value instanceof dev.langchain4j.model.chat.response.CompleteToolCall
                || value instanceof dev.langchain4j.agent.tool.ToolExecutionRequest
                || value instanceof ChatRequest
                || value instanceof ChatResponse
                || value instanceof Content
                || value instanceof ToolExecution
                || value instanceof BeforeToolExecution
                || value instanceof InvocationContext
                || value instanceof InvocationParameters
                || value instanceof Throwable
                || value instanceof Class<?>;
    }

    public static Object project(Object value) {
        if (value instanceof dev.langchain4j.model.chat.response.RawStreamingEvent event)
            return TypedValues.project(event.rawEvent());
        if (GuardrailValues.supports(value)) return GuardrailValues.project(value);
        if (value instanceof dev.langchain4j.service.AiServiceStreamingEvent event) {
            return ReactiveEventValues.project(event);
        }
        if (value instanceof dev.langchain4j.model.chat.response.PartialResponse part) {
            return part.text();
        }
        if (value instanceof dev.langchain4j.model.chat.response.PartialThinking part) {
            return part.text();
        }
        if (value instanceof dev.langchain4j.model.chat.response.PartialToolCall part) {
            var result = new LinkedHashMap<String, Object>();
            result.put("index", part.index());
            result.put("id", part.id());
            result.put("name", part.name());
            result.put("arguments", part.partialArguments());
            return result;
        }
        if (value instanceof dev.langchain4j.model.chat.response.CompleteToolCall part) {
            return Map.of(
                    "index",
                    part.index(),
                    "request",
                    ServiceResultProjection.request(part.toolExecutionRequest()));
        }
        if (value instanceof ChatResponse response) {
            return ServiceResultProjection.response(response);
        }
        if (value instanceof dev.langchain4j.agent.tool.ToolExecutionRequest request)
            return ServiceResultProjection.request(request);
        if (value instanceof ChatRequest request) {
            var result = new LinkedHashMap<String, Object>();
            result.put(
                    "messages", request.messages().stream().map(TextProjection::message).toList());
            result.put("parameters", TypedValues.project(request.parameters()));
            return result;
        }
        if (value instanceof Content content) {
            return ServiceResultProjection.source(content);
        }
        if (value instanceof ToolExecution tool) {
            return ServiceResultProjection.tool(tool);
        }
        if (value instanceof BeforeToolExecution before) {
            var result = new LinkedHashMap<String, Object>();
            result.put("request", ServiceResultProjection.request(before.request()));
            result.put(
                    "context",
                    before.invocationContext() == null
                            ? null
                            : project(before.invocationContext()));
            return result;
        }
        if (value instanceof InvocationParameters parameters) {
            return TypedValues.project(parameters.asMap());
        }
        if (value instanceof InvocationContext context) {
            var result = new LinkedHashMap<String, Object>();
            result.put("arguments", TypedValues.project(context.methodArguments()));
            result.put("memoryId", TypedValues.project(context.chatMemoryId()));
            result.put(
                    "userMessage",
                    context.userMessage() == null
                            ? null
                            : TextProjection.message(context.userMessage()));
            result.put(
                    "parameters",
                    context.invocationParameters() == null
                            ? null
                            : TypedValues.project(context.invocationParameters().asMap()));
            return result;
        }
        if (value instanceof Class<?> type) {
            return type.getName();
        }
        if (value instanceof Throwable error) {
            return error instanceof GovernanceException g ? g.code() : "callback_source_failed";
        }
        throw new GovernanceException("framework_value_unsupported");
    }

    @SuppressWarnings("unchecked")
    public static Object restore(Object original, Object effective) {
        if (original instanceof dev.langchain4j.model.chat.response.RawStreamingEvent event)
            return dev.langchain4j.model.chat.response.RawStreamingEvent.of(
                    TypedValues.restore(event.rawEvent(), effective, Object.class));
        if (GuardrailValues.supports(original)) return GuardrailValues.restore(original, effective);
        if (original instanceof dev.langchain4j.service.AiServiceStreamingEvent event) {
            return ReactiveEventValues.restore(event, effective);
        }
        if (original instanceof dev.langchain4j.model.chat.response.PartialResponse) {
            return new dev.langchain4j.model.chat.response.PartialResponse(
                    TextProjection.requiredString(effective));
        }
        if (original instanceof dev.langchain4j.model.chat.response.PartialThinking) {
            return new dev.langchain4j.model.chat.response.PartialThinking(
                    TextProjection.requiredString(effective));
        }
        if (original instanceof dev.langchain4j.model.chat.response.PartialToolCall part) {
            var before = TextProjection.object(project(part));
            var after = TextProjection.object(effective);
            TextProjection.sameKeys(before, after);
            TextProjection.fixed(before, after, "index");
            TextProjection.fixed(before, after, "id");
            TextProjection.fixed(before, after, "name");
            return dev.langchain4j.model.chat.response.PartialToolCall.builder()
                    .index(part.index())
                    .id(part.id())
                    .name(part.name())
                    .partialArguments(TextProjection.nullableString(after.get("arguments")))
                    .build();
        }
        if (original instanceof dev.langchain4j.model.chat.response.CompleteToolCall part) {
            var before = TextProjection.object(project(part));
            var after = TextProjection.object(effective);
            TextProjection.sameKeys(before, after);
            TextProjection.fixed(before, after, "index");
            return new dev.langchain4j.model.chat.response.CompleteToolCall(
                    part.index(),
                    ServiceResultProjection.restoreRequest(
                            part.toolExecutionRequest(), after.get("request")));
        }
        if (original instanceof ChatResponse response) {
            return ServiceResultProjection.restoreResponse(response, effective);
        }
        if (original instanceof Content content) {
            return ServiceResultProjection.restoreSource(content, effective);
        }
        if (original instanceof ToolExecution tool) {
            return ServiceResultProjection.restoreTool(tool, effective);
        }
        if (original instanceof BeforeToolExecution before) {
            var values = TextProjection.object(effective);
            TextProjection.sameKeys(TextProjection.object(project(before)), values);
            return BeforeToolExecution.builder()
                    .request(
                            ServiceResultProjection.restoreRequest(
                                    before.request(), values.get("request")))
                    .invocationContext(
                            before.invocationContext() == null
                                    ? null
                                    : (InvocationContext)
                                            restore(
                                                    before.invocationContext(),
                                                    values.get("context")))
                    .build();
        }
        if (original instanceof InvocationParameters parameters) {
            return InvocationParameters.from(
                    (Map<String, Object>)
                            TypedValues.restore(parameters.asMap(), effective, Map.class));
        }
        if (original instanceof InvocationContext context) {
            var values = TextProjection.object(effective);
            TextProjection.sameKeys(TextProjection.object(project(context)), values);
            return InvocationContext.builder()
                    .invocationId(context.invocationId())
                    .interfaceName(context.interfaceName())
                    .methodName(context.methodName())
                    .chatMemoryId(
                            TypedValues.restore(
                                    context.chatMemoryId(), values.get("memoryId"), Object.class))
                    .timestamp(context.timestamp())
                    .defaultRequestParameters(context.defaultRequestParameters())
                    .modelProvider(context.modelProvider())
                    .managedParameters(context.managedParameters())
                    .methodArguments(
                            (List<Object>)
                                    TypedValues.restore(
                                            context.methodArguments(),
                                            values.get("arguments"),
                                            List.class))
                    .userMessage(
                            context.userMessage() == null
                                    ? null
                                    : (dev.langchain4j.data.message.UserMessage)
                                            TextProjection.replace(
                                                    context.userMessage(),
                                                    values.get("userMessage")))
                    .invocationParameters(
                            context.invocationParameters() == null
                                    ? null
                                    : InvocationParameters.from(
                                            (Map<String, Object>)
                                                    TypedValues.restore(
                                                            context.invocationParameters().asMap(),
                                                            values.get("parameters"),
                                                            Map.class)))
                    .build();
        }
        if (original instanceof dev.langchain4j.agent.tool.ToolExecutionRequest request)
            return ServiceResultProjection.restoreRequest(request, effective);
        if (original instanceof ChatRequest request) {
            var values = TextProjection.object(effective);
            var before = TextProjection.object(project(request));
            TextProjection.sameKeys(before, values);
            // schema、模型参数由宿主配置，不允许披露改写偷偷改变实际请求配置。
            TextProjection.fixed(before, values, "parameters");
            var messages = TextProjection.list(values.get("messages"));
            if (messages.size() != request.messages().size()) {
                TextProjection.unsupported();
            }
            var copy = new ArrayList<dev.langchain4j.data.message.ChatMessage>();
            for (int i = 0; i < messages.size(); i++) {
                copy.add(TextProjection.replace(request.messages().get(i), messages.get(i)));
            }
            return request.toBuilder().messages(copy).build();
        }
        if (original instanceof Class<?>) {
            if (!project(original).equals(effective)) {
                TextProjection.unsupported();
            }
            return original;
        }
        if (original instanceof Throwable) {
            return new GovernanceException(TextProjection.requiredString(effective));
        }
        throw new GovernanceException("framework_value_unsupported");
    }
}
