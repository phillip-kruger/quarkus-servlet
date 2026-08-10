package io.quarkiverse.servlet.runtime;

/**
 * The thread a servlet's {@code service()} method is invoked on.
 * <p>
 * Selected per servlet at build time from {@code @NonBlocking}, {@code @Blocking} and
 * {@code @RunOnVirtualThread}, falling back to the deployment-wide default derived from
 * {@code quarkus.servlet.execution-model}.
 */
public enum ExecutionModel {

    /**
     * Invoke the servlet directly on the Vert.x event loop. Fastest, but the servlet must never
     * block: no synchronous I/O, no {@code Thread.sleep}, no waiting on other threads.
     */
    EVENT_LOOP,

    /**
     * Invoke the servlet on a Vert.x worker thread. Safe for arbitrary blocking code, at the cost
     * of a thread hand-off per request.
     */
    WORKER,

    /**
     * Invoke the servlet on a virtual thread. Safe for blocking code that parks (JDK I/O, JDBC on a
     * virtual-thread-friendly driver) without pinning a platform thread.
     */
    VIRTUAL_THREAD
}
