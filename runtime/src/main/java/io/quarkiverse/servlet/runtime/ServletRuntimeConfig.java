package io.quarkiverse.servlet.runtime;

import java.util.Optional;
import java.util.Set;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigRoot(phase = ConfigPhase.RUN_TIME)
@ConfigMapping(prefix = "quarkus.servlet")
public interface ServletRuntimeConfig {

    /**
     * The default buffer size for servlet output streams.
     */
    @WithDefault("8192")
    int bufferSize();

    /**
     * The default character encoding for servlet requests and responses.
     */
    Optional<String> defaultCharset();

    /**
     * The maximum number of HTTP request parameters. If a request contains
     * more parameters than this limit, a 400 Bad Request is returned.
     */
    @WithDefault("1000")
    int maxParameters();

    /**
     * HTTP methods that should be rejected with a 405 Method Not Allowed response.
     */
    Optional<Set<String>> disallowedMethods();

    /**
     * If true, all servlets run on virtual threads by default. Individual servlets
     * can still opt in with {@code @RunOnVirtualThread} when this is false (the default),
     * or this can be set to true to run all servlets on virtual threads without
     * annotating each one.
     */
    @WithDefault("false")
    boolean virtualThreads();
}
