package io.agt.langchain4j.internal.service;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.tool.ToolExecution;
import dev.langchain4j.service.tool.ToolExecutionResult;
import io.agt.core.JsonValues;
import io.agt.langchain4j.internal.message.TextProjection;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Result 的内容、来源和历史响应的完整披露投影。
 *
 * <p>不只检查 content，避免从最终响应、工具记录或检索来源取回未检查原文。
 */
public final class ServiceResultProjection {
    private ServiceResultProjection() {}

    public static Object project(Result<?> result) {
        var value = new LinkedHashMap<String, Object>();
        value.put("content", TypedValues.project(result.content()));
        value.put(
                "sources",
                result.sources() == null
                        ? null
                        : result.sources().stream().map(ServiceResultProjection::source).toList());
        value.put(
                "tools",
                result.toolExecutions() == null
                        ? null
                        : result.toolExecutions().stream()
                                .map(ServiceResultProjection::tool)
                                .toList());
        value.put(
                "intermediate",
                result.intermediateResponses() == null
                        ? null
                        : result.intermediateResponses().stream()
                                .map(ServiceResultProjection::response)
                                .toList());
        value.put(
                "final", result.finalResponse() == null ? null : response(result.finalResponse()));
        return JsonValues.freeze(value);
    }

    public static Result<?> restore(Result<?> original, Object effective, Type contentType) {
        var values = TextProjection.object(effective);
        TextProjection.sameKeys(TextProjection.object(project(original)), values);
        var builder =
                Result.builder()
                        .content(
                                TypedValues.restore(
                                        original.content(), values.get("content"), contentType))
                        .tokenUsage(original.tokenUsage())
                        .finishReason(original.finishReason());
        if (original.sources() != null) {
            var after = sameSize(original.sources(), values.get("sources"));
            var sources = new ArrayList<Content>();
            for (int i = 0; i < after.size(); i++) {
                sources.add(restoreSource(original.sources().get(i), after.get(i)));
            }
            builder.sources(sources);
        } else if (values.get("sources") != null) {
            TextProjection.unsupported();
        }
        if (original.toolExecutions() != null) {
            var after = sameSize(original.toolExecutions(), values.get("tools"));
            var tools = new ArrayList<ToolExecution>();
            for (int i = 0; i < after.size(); i++) {
                tools.add(restoreTool(original.toolExecutions().get(i), after.get(i)));
            }
            builder.toolExecutions(tools);
        } else if (values.get("tools") != null) {
            TextProjection.unsupported();
        }
        if (original.intermediateResponses() != null) {
            var after = sameSize(original.intermediateResponses(), values.get("intermediate"));
            var responses = new ArrayList<ChatResponse>();
            for (int i = 0; i < after.size(); i++) {
                responses.add(
                        restoreResponse(original.intermediateResponses().get(i), after.get(i)));
            }
            builder.intermediateResponses(responses);
        } else if (values.get("intermediate") != null) {
            TextProjection.unsupported();
        }
        if (original.finalResponse() != null) {
            builder.finalResponse(restoreResponse(original.finalResponse(), values.get("final")));
        } else if (values.get("final") != null) {
            TextProjection.unsupported();
        }
        return builder.build();
    }

    public static Map<String, Object> response(ChatResponse response) {
        var value = new LinkedHashMap<String, Object>();
        value.put("message", TextProjection.message(response.aiMessage()));
        value.put("metadata", TypedValues.project(response.metadata()));
        return TextProjection.object(JsonValues.freeze(value));
    }

    public static ChatResponse restoreResponse(ChatResponse original, Object effective) {
        var values = TextProjection.object(effective);
        var before = response(original);
        TextProjection.sameKeys(before, values);
        var metadata = original.metadata();
        if (!TypedValues.equivalent(before.get("metadata"), values.get("metadata"))) {
            var a = TextProjection.object(before.get("metadata"));
            var b = TextProjection.object(values.get("metadata"));
            TextProjection.sameKeys(a, b);
            TextProjection.fixed(a, b, "tokenUsage");
            TextProjection.fixed(a, b, "finishReason");
            metadata =
                    (dev.langchain4j.model.chat.response.ChatResponseMetadata)
                            TypedValues.restore(metadata, b, metadata.getClass());
        }
        return original.toBuilder()
                .aiMessage(
                        (AiMessage)
                                TextProjection.replace(original.aiMessage(), values.get("message")))
                .metadata(metadata)
                .build();
    }

    public static Map<String, Object> source(Content source) {
        var value = new LinkedHashMap<String, Object>();
        value.put("text", source.textSegment().text());
        value.put("segmentMetadata", TypedValues.project(source.textSegment().metadata().toMap()));
        var metadata = new LinkedHashMap<String, Object>();
        if (source.metadata() != null) {
            source.metadata().forEach((k, v) -> metadata.put(k.name(), TypedValues.project(v)));
        }
        value.put("sourceMetadata", metadata);
        return TextProjection.object(JsonValues.freeze(value));
    }

    public static Content restoreSource(Content original, Object effective) {
        var before = source(original);
        var after = TextProjection.object(effective);
        TextProjection.sameKeys(before, after);
        // 来源身份不可借输出改写伪造，正文可脱敏。
        TextProjection.fixed(before, after, "segmentMetadata");
        TextProjection.fixed(before, after, "sourceMetadata");
        return Content.from(
                TextSegment.from(
                        TextProjection.requiredString(after.get("text")),
                        Metadata.from(original.textSegment().metadata().toMap())),
                original.metadata());
    }

    public static Map<String, Object> tool(ToolExecution execution) {
        var value = new LinkedHashMap<String, Object>();
        value.put("request", request(execution.request()));
        value.put(
                "context",
                execution.invocationContext() == null
                        ? null
                        : FrameworkValues.project(execution.invocationContext()));
        value.put("result", TypedValues.project(execution.resultObject()));
        value.put("contents", MediaValues.project(execution.resultContents()));
        value.put("attributes", TypedValues.project(execution.attributes()));
        return TextProjection.object(JsonValues.freeze(value));
    }

    public static ToolExecution restoreTool(ToolExecution original, Object effective) {
        var values = TextProjection.object(effective);
        TextProjection.sameKeys(tool(original), values);
        Object result =
                TypedValues.restore(
                        original.resultObject(),
                        values.get("result"),
                        original.resultObject() == null
                                ? Object.class
                                : original.resultObject().getClass());
        var checked =
                ToolExecutionResult.builder()
                        .result(result)
                        .resultContents(
                                MediaValues.restore(
                                        original.resultContents(), values.get("contents")))
                        .attributes(TextProjection.nullableObject(values.get("attributes")))
                        .isError(original.hasFailed())
                        .build();
        return ToolExecution.builder()
                .request(restoreRequest(original.request(), values.get("request")))
                .result(checked)
                .startTime(original.startTime())
                .finishTime(original.finishTime())
                .invocationContext(
                        original.invocationContext() == null
                                ? null
                                : (dev.langchain4j.invocation.InvocationContext)
                                        FrameworkValues.restore(
                                                original.invocationContext(),
                                                values.get("context")))
                .build();
    }

    public static Map<String, Object> request(ToolExecutionRequest request) {
        return Map.of(
                "id",
                request.id() == null ? "" : request.id(),
                "name",
                request.name(),
                "arguments",
                JsonValues.parseObject(request.arguments()));
    }

    public static ToolExecutionRequest restoreRequest(
            ToolExecutionRequest original, Object effective) {
        var values = TextProjection.object(effective);
        var before = request(original);
        TextProjection.sameKeys(before, values);
        TextProjection.fixed(before, values, "id");
        TextProjection.fixed(before, values, "name");
        return ToolExecutionRequest.builder()
                .id(original.id())
                .name(original.name())
                .arguments(JsonValues.write(TextProjection.object(values.get("arguments"))))
                .build();
    }

    private static List<?> sameSize(List<?> before, Object effective) {
        var after = TextProjection.list(effective);
        if (before.size() != after.size()) {
            TextProjection.unsupported();
        }
        return after;
    }
}
