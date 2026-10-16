package io.agt.langchain4j.internal.service;

import dev.langchain4j.spi.services.PublisherAdapter;
import io.agt.core.GovernanceException;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.ServiceLoader;
import java.util.concurrent.Flow;

/**
 * JDK Publisher 与宿主已安装的原生 Reactive 适配器之间的桥接。
 *
 * <p>不强制依赖 Reactor；可选 Reactive Streams 仅通过其公开接口转发背压和取消。
 */
public final class PublisherTypes {
    private PublisherTypes() {}

    public static boolean isPublisher(Type type) {
        if (Flow.Publisher.class.isAssignableFrom(TypedValues.raw(type))) {
            return true;
        }
        return reactive(TypedValues.raw(type))
                || adapter(type) != null
                || java.util.stream.StreamSupport.stream(
                                ServiceLoader.load(
                                                dev.langchain4j.spi.services.TokenStreamAdapter
                                                        .class)
                                        .spliterator(),
                                false)
                        .anyMatch(a -> a.canAdaptTokenStreamTo(type));
    }

    private static boolean reactive(Class<?> type) {
        if (type == null) {
            return false;
        }
        if (type.getName().equals("org.reactivestreams.Publisher")) {
            return true;
        }
        for (Class<?> parent : type.getInterfaces()) {
            if (reactive(parent)) {
                return true;
            }
        }
        return reactive(type.getSuperclass());
    }

    public static boolean isStreaming(Type type) {
        return Flow.Publisher.class.isAssignableFrom(TypedValues.raw(type))
                || adapter(type) != null
                || TypedValues.raw(type).getName().equals("reactor.core.publisher.Flux");
    }

    private static PublisherAdapter adapter(Type type) {
        for (var adapter : ServiceLoader.load(PublisherAdapter.class)) {
            if (adapter.canAdapt(type)) {
                return adapter;
            }
        }
        return null;
    }

    public static Object adapt(Type type, Flow.Publisher<?> publisher) {
        if (Flow.Publisher.class.isAssignableFrom(TypedValues.raw(type))) {
            return publisher;
        }
        var adapter = adapter(type);
        if (adapter == null) {
            var raw = TypedValues.raw(type);
            if (raw.getName().equals("reactor.core.publisher.Mono")
                    || raw.getName().equals("reactor.core.publisher.Flux")) {
                try {
                    var reactivePublisher =
                            Class.forName(
                                    "org.reactivestreams.Publisher", false, raw.getClassLoader());
                    var subscriber =
                            Class.forName(
                                    "org.reactivestreams.Subscriber", false, raw.getClassLoader());
                    var subscription =
                            Class.forName(
                                    "org.reactivestreams.Subscription",
                                    false,
                                    raw.getClassLoader());
                    Object source =
                            Proxy.newProxyInstance(
                                    raw.getClassLoader(),
                                    new Class<?>[] {reactivePublisher},
                                    (self, method, args) -> {
                                        if (method.getName().equals("subscribe")) {
                                            Object target = args[0];
                                            publisher.subscribe(
                                                    new Flow.Subscriber<Object>() {
                                                        public void onSubscribe(
                                                                Flow.Subscription s) {
                                                            Object bridge =
                                                                    Proxy.newProxyInstance(
                                                                            raw.getClassLoader(),
                                                                            new Class<?>[] {
                                                                                subscription
                                                                            },
                                                                            (p, m, a) -> {
                                                                                if (m.getName()
                                                                                        .equals(
                                                                                                "request")) {
                                                                                    s.request(
                                                                                            (Long)
                                                                                                    a[
                                                                                                            0]);
                                                                                    return null;
                                                                                }
                                                                                if (m.getName()
                                                                                        .equals(
                                                                                                "cancel")) {
                                                                                    s.cancel();
                                                                                    return null;
                                                                                }
                                                                                return objectMethod(
                                                                                        p,
                                                                                        m.getName(),
                                                                                        a);
                                                                            });
                                                            call(
                                                                    subscriber,
                                                                    target,
                                                                    "onSubscribe",
                                                                    new Class<?>[] {subscription},
                                                                    bridge);
                                                        }

                                                        public void onNext(Object item) {
                                                            call(
                                                                    subscriber,
                                                                    target,
                                                                    "onNext",
                                                                    new Class<?>[] {Object.class},
                                                                    item);
                                                        }

                                                        public void onError(Throwable error) {
                                                            call(
                                                                    subscriber,
                                                                    target,
                                                                    "onError",
                                                                    new Class<?>[] {
                                                                        Throwable.class
                                                                    },
                                                                    error);
                                                        }

                                                        public void onComplete() {
                                                            call(
                                                                    subscriber,
                                                                    target,
                                                                    "onComplete",
                                                                    new Class<?>[0]);
                                                        }
                                                    });
                                            return null;
                                        }
                                        return objectMethod(self, method.getName(), args);
                                    });
                    return raw.getMethod(
                                    raw.getName().endsWith(".Mono") ? "fromDirect" : "from",
                                    reactivePublisher)
                            .invoke(null, source);
                } catch (ReflectiveOperationException error) {
                    throw new GovernanceException("publisher_adapter_failed");
                }
            }
            throw new GovernanceException("publisher_adapter_missing");
        }
        return adapter.fromPublisher(type, publisher);
    }

    @SuppressWarnings("unchecked")
    public static Flow.Publisher<Object> flow(Object source) {
        if (source instanceof Flow.Publisher<?> publisher) {
            return (Flow.Publisher<Object>) publisher;
        }
        return downstream -> {
            try {
                ClassLoader loader = source.getClass().getClassLoader();
                var publisherType = Class.forName("org.reactivestreams.Publisher", false, loader);
                var subscriberType = Class.forName("org.reactivestreams.Subscriber", false, loader);
                var subscriptionType =
                        Class.forName("org.reactivestreams.Subscription", false, loader);
                Object subscriber =
                        Proxy.newProxyInstance(
                                loader,
                                new Class<?>[] {subscriberType},
                                (self, method, arguments) -> {
                                    switch (method.getName()) {
                                        case "onSubscribe" -> {
                                            Object upstream = arguments[0];
                                            downstream.onSubscribe(
                                                    new Flow.Subscription() {
                                                        public void request(long n) {
                                                            call(
                                                                    subscriptionType,
                                                                    upstream,
                                                                    "request",
                                                                    new Class<?>[] {long.class},
                                                                    n);
                                                        }

                                                        public void cancel() {
                                                            call(
                                                                    subscriptionType,
                                                                    upstream,
                                                                    "cancel",
                                                                    new Class<?>[0]);
                                                        }
                                                    });
                                        }
                                        case "onNext" -> downstream.onNext(arguments[0]);
                                        case "onError" ->
                                                downstream.onError((Throwable) arguments[0]);
                                        case "onComplete" -> downstream.onComplete();
                                        case "toString" -> {
                                            return "GovernedReactiveSubscriber";
                                        }
                                        case "hashCode" -> {
                                            return System.identityHashCode(self);
                                        }
                                        case "equals" -> {
                                            return self == arguments[0];
                                        }
                                        default ->
                                                throw new GovernanceException(
                                                        "publisher_method_unsupported");
                                    }
                                    return null;
                                });
                publisherType.getMethod("subscribe", subscriberType).invoke(source, subscriber);
            } catch (ReflectiveOperationException error) {
                downstream.onSubscribe(
                        new Flow.Subscription() {
                            public void request(long n) {}

                            public void cancel() {}
                        });
                downstream.onError(new GovernanceException("publisher_adapter_failed"));
            }
        };
    }

    private static Object objectMethod(Object proxy, String name, Object[] args) {
        return switch (name) {
            case "toString" -> "GovernedReactiveBridge";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new GovernanceException("publisher_method_unsupported");
        };
    }

    private static void call(
            Class<?> type, Object source, String method, Class<?>[] parameters, Object... args) {
        try {
            type.getMethod(method, parameters).invoke(source, args);
        } catch (ReflectiveOperationException error) {
            throw new GovernanceException("publisher_adapter_failed");
        }
    }
}
