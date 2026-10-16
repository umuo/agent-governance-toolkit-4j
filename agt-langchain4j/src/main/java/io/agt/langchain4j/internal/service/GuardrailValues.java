package io.agt.langchain4j.internal.service;

import dev.langchain4j.data.message.*;
import dev.langchain4j.guardrail.*;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.rag.AugmentationResult;
import io.agt.core.GovernanceException;
import io.agt.langchain4j.internal.message.TextProjection;
import java.util.*;

/** Guardrail 观察事件只提供经过检查的副本，不暴露可重试模型或修改记忆的运行时句柄。 */
final class GuardrailValues {
    private GuardrailValues() {}

    static boolean supports(Object value) {
        return value instanceof GuardrailRequest<?> || value instanceof GuardrailResult<?>;
    }

    static Object project(Object original) {
        var value = new LinkedHashMap<String, Object>();
        if (original instanceof InputGuardrailRequest request) {
            value.put("message", TypedValues.project(request.userMessage()));
            value.put("params", params(request.requestParams()));
        } else if (original instanceof OutputGuardrailRequest request) {
            value.put("response", TypedValues.project(request.responseFromLLM()));
            value.put("params", params(request.requestParams()));
        } else if (original instanceof GuardrailResult<?> result) {
            value.put("status", result.result().name());
            value.put("text", result.successfulText());
            if (result instanceof OutputGuardrailResult output)
                value.put("result", TypedValues.project(output.successfulResult()));
            value.put(
                    "failures",
                    result.failures().stream()
                            .map(
                                    f -> {
                                        var p = new LinkedHashMap<String, Object>();
                                        p.put("message", f.message());
                                        p.put("cause", TypedValues.project(f.cause()));
                                        p.put(
                                                "class",
                                                f.guardrailClass() == null
                                                        ? null
                                                        : f.guardrailClass().getName());
                                        if (f instanceof OutputGuardrailResult.Failure output) {
                                            p.put("retry", output.retry());
                                            p.put("reprompt", output.reprompt());
                                            p.put("remove", output.removeViolatingMessage());
                                        }
                                        return p;
                                    })
                            .toList());
        } else throw new GovernanceException("guardrail_value_unsupported");
        return value;
    }

    private static Object params(GuardrailRequestParams params) {
        if (params == null) return null;
        var value = new LinkedHashMap<String, Object>();
        value.put("context", TypedValues.project(params.invocationContext()));
        value.put("template", params.userMessageTemplate());
        value.put("variables", TypedValues.project(params.variables()));
        if (params.chatMemory() == null) value.put("memory", null);
        else {
            var memory = new LinkedHashMap<String, Object>();
            memory.put("id", TypedValues.project(params.chatMemory().id()));
            memory.put("messages", TypedValues.project(params.chatMemory().messages()));
            value.put("memory", memory);
        }
        if (params.augmentationResult() == null) value.put("augmentation", null);
        else {
            var augmentation = new LinkedHashMap<String, Object>();
            augmentation.put(
                    "message", TypedValues.project(params.augmentationResult().chatMessage()));
            augmentation.put(
                    "contents", TypedValues.project(params.augmentationResult().contents()));
            value.put("augmentation", augmentation);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static GuardrailRequestParams restoreParams(
            GuardrailRequestParams original, Object effective) {
        if (original == null) {
            if (effective != null) TextProjection.unsupported();
            return null;
        }
        var values = TextProjection.object(effective);
        TextProjection.sameKeys(TextProjection.object(params(original)), values);
        ChatMemory memory = null;
        if (original.chatMemory() != null) {
            var data = TextProjection.object(values.get("memory"));
            var id = TypedValues.restore(original.chatMemory().id(), data.get("id"), Object.class);
            var messages =
                    (List<ChatMessage>)
                            TypedValues.restore(
                                    original.chatMemory().messages(),
                                    data.get("messages"),
                                    List.class);
            memory =
                    new ChatMemory() {
                        public Object id() {
                            return id;
                        }

                        public List<ChatMessage> messages() {
                            return List.copyOf(messages);
                        }

                        public void add(ChatMessage message) {
                            throw new GovernanceException("event_memory_read_only");
                        }

                        public void clear() {
                            throw new GovernanceException("event_memory_read_only");
                        }
                    };
        }
        AugmentationResult augmentation = null;
        if (original.augmentationResult() != null) {
            var data = TextProjection.object(values.get("augmentation"));
            var prior = original.augmentationResult();
            augmentation =
                    new AugmentationResult(
                            (ChatMessage)
                                    TypedValues.restore(
                                            prior.chatMessage(),
                                            data.get("message"),
                                            ChatMessage.class),
                            (List<dev.langchain4j.rag.content.Content>)
                                    TypedValues.restore(
                                            prior.contents(), data.get("contents"), List.class));
        }
        return GuardrailRequestParams.builder()
                .chatMemory(memory)
                .augmentationResult(augmentation)
                .userMessageTemplate(TextProjection.nullableString(values.get("template")))
                .variables(
                        (Map<String, Object>)
                                TypedValues.restore(
                                        original.variables(), values.get("variables"), Map.class))
                .invocationContext(
                        (InvocationContext)
                                TypedValues.restore(
                                        original.invocationContext(),
                                        values.get("context"),
                                        InvocationContext.class))
                .build();
    }

    static Object restore(Object original, Object effective) {
        var values = TextProjection.object(effective);
        var before = TextProjection.object(project(original));
        TextProjection.sameKeys(before, values);
        if (original instanceof InputGuardrailRequest request)
            return InputGuardrailRequest.builder()
                    .userMessage(
                            (UserMessage)
                                    TypedValues.restore(
                                            request.userMessage(),
                                            values.get("message"),
                                            UserMessage.class))
                    .commonParams(restoreParams(request.requestParams(), values.get("params")))
                    .build();
        if (original instanceof OutputGuardrailRequest request)
            return OutputGuardrailRequest.builder()
                    .responseFromLLM(
                            (dev.langchain4j.model.chat.response.ChatResponse)
                                    TypedValues.restore(
                                            request.responseFromLLM(),
                                            values.get("response"),
                                            dev.langchain4j.model.chat.response.ChatResponse.class))
                    .requestParams(restoreParams(request.requestParams(), values.get("params")))
                    .chatExecutor(
                            new ChatExecutor() {
                                public dev.langchain4j.model.chat.response.ChatResponse execute() {
                                    throw new GovernanceException("event_execution_unsupported");
                                }

                                public dev.langchain4j.model.chat.response.ChatResponse execute(
                                        List<ChatMessage> messages) {
                                    throw new GovernanceException("event_execution_unsupported");
                                }
                            })
                    .build();
        var result = (GuardrailResult<?>) original;
        TextProjection.fixed(before, values, "status");
        String text = TextProjection.nullableString(values.get("text"));
        if (result.isSuccess()) {
            TextProjection.fixed(before, values, "failures");
            if (result instanceof InputGuardrailResult)
                return text == null
                        ? InputGuardrailResult.success()
                        : InputGuardrailResult.successWith(text);
            var prior = ((OutputGuardrailResult) result).successfulResult();
            var restored = TypedValues.restore(prior, values.get("result"), Object.class);
            return text == null && restored == null
                    ? OutputGuardrailResult.success()
                    : OutputGuardrailResult.successWith(text, restored);
        }
        // 原生单个 guardrail 的输入失败只含一项；公开输出工厂支持合并的失败列表。
        var failures = TextProjection.list(values.get("failures"));
        var originals = result.failures();
        if (failures.size() != originals.size()) TextProjection.unsupported();
        var output = new ArrayList<OutputGuardrailResult.Failure>();
        InputGuardrailResult input = null;
        for (int i = 0; i < failures.size(); i++) {
            var data = TextProjection.object(failures.get(i));
            var prior = originals.get(i);
            var projected =
                    TextProjection.object(TextProjection.list(before.get("failures")).get(i));
            TextProjection.sameKeys(projected, data);
            TextProjection.fixed(projected, data, "class");
            String message = TextProjection.requiredString(data.get("message"));
            Throwable cause =
                    data.get("cause") == null
                            ? null
                            : new GovernanceException(
                                    TextProjection.requiredString(data.get("cause")));
            if (result instanceof InputGuardrailResult) {
                if (failures.size() != 1)
                    throw new GovernanceException("guardrail_failure_shape_unsupported");
                var helper = new InputGuardrail() {};
                input =
                        result.isFatal()
                                ? helper.fatal(message, cause)
                                : helper.failure(message, cause);
                input = input.validatedBy(prior.guardrailClass());
            } else {
                TextProjection.fixed(projected, data, "retry");
                TextProjection.fixed(projected, data, "remove");
                var helper = new OutputGuardrail() {};
                OutputGuardrailResult item;
                if (Boolean.TRUE.equals(data.get("remove")))
                    item =
                            result.isFatal()
                                    ? helper.fatalWithMessageRemoval(message, cause)
                                    : helper.failureWithMessageRemoval(message, cause);
                else if (data.get("reprompt") != null)
                    item =
                            helper.reprompt(
                                    message,
                                    cause,
                                    TextProjection.requiredString(data.get("reprompt")));
                else if (Boolean.TRUE.equals(data.get("retry")))
                    item = helper.retry(message, cause);
                else
                    item =
                            result.isFatal()
                                    ? helper.fatal(message, cause)
                                    : helper.failure(message, cause);
                output.add(
                        item.validatedBy(prior.guardrailClass())
                                .<OutputGuardrailResult.Failure>failures()
                                .get(0));
            }
        }
        return result instanceof InputGuardrailResult
                ? input
                : OutputGuardrailResult.failure(output);
    }
}
