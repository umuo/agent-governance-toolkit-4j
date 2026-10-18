package io.agt.core.usage;

import io.agt.core.Identifiers;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 宿主价格表与根任务用量上限；价格单位为指定货币/每百万 Token，不自动查询市场价格。 */
public record UsageLimits(
        long maxTokens, BigDecimal maxCost, String currency, Map<String, ModelRate> models) {
    public UsageLimits {
        positive(maxCost, true);
        Identifiers.require(currency);
        models = Map.copyOf(models);
        if (maxTokens < 0 || models.isEmpty() || models.size() > 128)
            throw new IllegalArgumentException("Invalid usage limits");
        models.keySet().forEach(Identifiers::require);
    }

    public record ModelRate(
            int maxInputTokens,
            int maxOutputTokens,
            BigDecimal inputPerMillion,
            BigDecimal outputPerMillion,
            Set<String> servedAliases) {
        public ModelRate {
            if (maxInputTokens < 1 || maxOutputTokens < 1)
                throw new IllegalArgumentException("Invalid model limits");
            positive(inputPerMillion, true);
            positive(outputPerMillion, true);
            servedAliases = Set.copyOf(servedAliases);
            if (servedAliases.size() > 128)
                throw new IllegalArgumentException("Too many model aliases");
            servedAliases.forEach(Identifiers::require);
        }

        BigDecimal cost(long input, long output) {
            return inputPerMillion
                    .multiply(BigDecimal.valueOf(input))
                    .add(outputPerMillion.multiply(BigDecimal.valueOf(output)))
                    .movePointLeft(6);
        }
    }

    private static void positive(BigDecimal value, boolean zeroAllowed) {
        Objects.requireNonNull(value);
        if (value.signum() < 0
                || (!zeroAllowed && value.signum() == 0)
                || value.precision() > 32
                || Math.abs((long) value.scale()) > 12) {
            throw new IllegalArgumentException("Invalid monetary amount");
        }
    }
}
