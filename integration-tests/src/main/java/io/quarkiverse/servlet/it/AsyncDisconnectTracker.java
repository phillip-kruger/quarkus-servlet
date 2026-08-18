package io.quarkiverse.servlet.it;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Records the {@link jakarta.servlet.AsyncListener} callbacks fired for the client-disconnect test,
 * so a second request can observe them. Shared application state, since the disconnecting request's
 * own response is gone by the time the callbacks run.
 */
@ApplicationScoped
public class AsyncDisconnectTracker {

    private volatile CountDownLatch completed = new CountDownLatch(1);
    private volatile boolean onErrorCalled;
    private volatile boolean onCompleteCalled;

    void reset() {
        completed = new CountDownLatch(1);
        onErrorCalled = false;
        onCompleteCalled = false;
    }

    void error() {
        onErrorCalled = true;
    }

    void complete() {
        onCompleteCalled = true;
        completed.countDown();
    }

    /** Waits up to {@code millis} for {@code onComplete} to fire. */
    boolean awaitComplete(long millis) throws InterruptedException {
        return completed.await(millis, TimeUnit.MILLISECONDS);
    }

    boolean errored() {
        return onErrorCalled;
    }

    boolean completedNormally() {
        return onCompleteCalled;
    }
}
