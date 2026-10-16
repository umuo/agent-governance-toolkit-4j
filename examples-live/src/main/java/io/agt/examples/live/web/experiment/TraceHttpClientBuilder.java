package io.agt.examples.live.web.experiment;

import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.HttpClientBuilderLoader;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import java.time.Duration;
import java.util.Map;

/** 复用官方 HTTP 客户端 SPI，仅采集当前实验实际发送的 JSON body；不记录 URL、请求头或凭据。 */
public final class TraceHttpClientBuilder implements HttpClientBuilder {
    private final HttpClientBuilder delegate;

    public TraceHttpClientBuilder() {
        this(HttpClientBuilderLoader.loadHttpClientBuilder());
    }

    public TraceHttpClientBuilder(HttpClientBuilder delegate) {
        this.delegate = delegate;
    }

    @Override
    public Duration connectTimeout() {
        return delegate.connectTimeout();
    }

    @Override
    public HttpClientBuilder connectTimeout(Duration value) {
        delegate.connectTimeout(value);
        return this;
    }

    @Override
    public Duration readTimeout() {
        return delegate.readTimeout();
    }

    @Override
    public HttpClientBuilder readTimeout(Duration value) {
        delegate.readTimeout(value);
        return this;
    }

    @Override
    public HttpClient build() {
        HttpClient client = delegate.build();
        return new HttpClient() {
            @Override
            public SuccessfulHttpResponse execute(HttpRequest request) {
                var trace = ExperimentTrace.current();
                if (trace != null) trace.httpBody("http_request", request.body());
                try {
                    var response = client.execute(request);
                    if (trace != null) trace.httpBody("http_response", response.body());
                    return response;
                } catch (RuntimeException error) {
                    // Provider 错误正文可能反射凭据或连接信息，不向网页转发异常原文。
                    if (trace != null)
                        trace.add("http_error", Map.of("code", "provider_request_failed"));
                    throw error;
                }
            }

            @Override
            public void execute(
                    HttpRequest request,
                    ServerSentEventParser parser,
                    ServerSentEventListener listener) {
                client.execute(request, parser, listener);
            }
        };
    }
}
