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
     * The thread servlets run on when they do not declare one themselves.
     * <p>
     * {@code event-loop} is the fastest and is safe only for servlets that never block.
     * {@code worker} matches the behaviour of traditional servlet containers and is safe for
     * arbitrary blocking code. {@code virtual-thread} runs every servlet on a virtual thread.
     * <p>
     * Individual servlets override this with {@code @NonBlocking}, {@code @Blocking} or
     * {@code @RunOnVirtualThread}.
     */
    @WithDefault("event-loop")
    ExecutionModel executionModel();

    /**
     * If true, all servlets run on virtual threads by default.
     *
     * @deprecated use {@code quarkus.servlet.execution-model=virtual-thread} instead. When set,
     *             this still wins over {@code execution-model} so existing configuration keeps
     *             working.
     */
    @Deprecated
    @WithDefault("false")
    boolean virtualThreads();

    /**
     * The deployment-wide execution model, taking the deprecated {@code virtual-threads} flag into
     * account.
     */
    default ExecutionModel defaultExecutionModel() {
        return virtualThreads() ? ExecutionModel.VIRTUAL_THREAD : executionModel();
    }
}
