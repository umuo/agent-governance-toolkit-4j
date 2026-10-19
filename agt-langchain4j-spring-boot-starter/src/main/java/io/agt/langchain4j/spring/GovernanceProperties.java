package io.agt.langchain4j.spring;

import io.agt.core.streaming.ChunkBufferOptions;
import io.agt.langchain4j.AiServiceOptions;
import io.agt.langchain4j.StreamDelivery;
import io.agt.langchain4j.StreamingLimits;
import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 原生 AI Service 的 Spring 默认配置。
 *
 * <p>属性前缀 agt.langchain4j；服务专属 Options 优先于这里的默认值。
 */
@ConfigurationProperties("agt.langchain4j")
public class GovernanceProperties {
    private StreamDelivery delivery = AiServiceOptions.DEFAULT.delivery();
    private int maxCharacters = AiServiceOptions.DEFAULT.streamingLimits().maxCharacters();
    private int maxEvents = AiServiceOptions.DEFAULT.streamingLimits().maxEvents();
    private int publisherPrefetch = AiServiceOptions.DEFAULT.publisherPrefetch();

    private Guardrails guardrails = new Guardrails();

    public Guardrails getGuardrails() {
        return guardrails;
    }

    public void setGuardrails(Guardrails value) {
        guardrails = value;
    }

    public static class Guardrails {
        private int chunkCharacters = 20;
        private String boundaries = "，。！？\n";
        private Duration maxWait = Duration.ofMillis(50);
        private int maxBufferedCharacters = 4096;
        private Set<String> localRules = Set.of();

        public int getChunkCharacters() {
            return chunkCharacters;
        }

        public void setChunkCharacters(int value) {
            chunkCharacters = value;
        }

        public String getBoundaries() {
            return boundaries;
        }

        public void setBoundaries(String value) {
            boundaries = value;
        }

        public Duration getMaxWait() {
            return maxWait;
        }

        public void setMaxWait(Duration value) {
            maxWait = value;
        }

        public int getMaxBufferedCharacters() {
            return maxBufferedCharacters;
        }

        public void setMaxBufferedCharacters(int value) {
            maxBufferedCharacters = value;
        }

        public Set<String> getLocalRules() {
            return localRules;
        }

        public void setLocalRules(Set<String> value) {
            localRules = value;
        }

        ChunkBufferOptions toOptions() {
            return new ChunkBufferOptions(
                    chunkCharacters, boundaries, maxWait, maxBufferedCharacters, localRules);
        }
    }

    public StreamDelivery getDelivery() {
        return delivery;
    }

    public void setDelivery(StreamDelivery value) {
        delivery = value;
    }

    public int getMaxCharacters() {
        return maxCharacters;
    }

    public void setMaxCharacters(int value) {
        maxCharacters = value;
    }

    public int getMaxEvents() {
        return maxEvents;
    }

    public void setMaxEvents(int value) {
        maxEvents = value;
    }

    public int getPublisherPrefetch() {
        return publisherPrefetch;
    }

    public void setPublisherPrefetch(int value) {
        publisherPrefetch = value;
    }

    public AiServiceOptions toOptions() {
        return new AiServiceOptions(
                delivery,
                new StreamingLimits(maxCharacters, maxEvents),
                publisherPrefetch,
                delivery == StreamDelivery.GUARDED ? guardrails.toOptions() : null);
    }
}
