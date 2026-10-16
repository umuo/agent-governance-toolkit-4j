package io.agt.langchain4j.spring;

import io.agt.langchain4j.AiServiceOptions;
import io.agt.langchain4j.StreamDelivery;
import io.agt.langchain4j.StreamingLimits;
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
                delivery, new StreamingLimits(maxCharacters, maxEvents), publisherPrefetch);
    }
}
