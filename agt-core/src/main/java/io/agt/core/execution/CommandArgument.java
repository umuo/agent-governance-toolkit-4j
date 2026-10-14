package io.agt.core.execution;

import io.agt.core.Identifiers;
import java.util.List;

/**
 * 宿主固定的单个 argv 参数。
 *
 * <p>模型只可选择显式枚举值，不能提供命令、脚本、任意路径或自由文本。
 */
public record CommandArgument(String literal, String parameter, List<String> choices) {
    public CommandArgument {
        choices = choices == null ? List.of() : List.copyOf(choices);
        if ((literal == null) == (parameter == null)) {
            throw new IllegalArgumentException("Choose literal or parameter");
        }
        if (literal != null) {
            check(literal);
            if (!choices.isEmpty()) {
                throw new IllegalArgumentException("Literal cannot have choices");
            }
        } else {
            Identifiers.require(parameter);
            if (choices.isEmpty() || choices.size() > 100) {
                throw new IllegalArgumentException("Bounded choices required");
            }
            choices.forEach(CommandArgument::check);
        }
    }

    public static CommandArgument literal(String value) {
        return new CommandArgument(value, null, List.of());
    }

    public static CommandArgument choice(String name, String... values) {
        return new CommandArgument(null, name, List.of(values));
    }

    private static void check(String value) {
        if (value == null || value.length() > 4096 || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid command argument");
        }
    }
}
