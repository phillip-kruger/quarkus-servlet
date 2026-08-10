package io.quarkiverse.servlet.it;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Async processing and the execution-model annotations.
 * <p>
 * The timeouts matter: completing or dispatching an AsyncContext from another thread used to block
 * the event loop forever, so a hang here is a real regression, not a slow machine.
 */
@QuarkusTest
class AsyncAndExecutionModelTest {

    @Test
    @Timeout(20)
    void completeFromAnotherThread() {
        given()
                .when().get("/async")
                .then()
                .statusCode(200)
                .body(is("async-done"));
    }

    @Test
    @Timeout(20)
    void dispatchFromAnotherThread() {
        given()
                .when().get("/async-dispatch")
                .then()
                .statusCode(200)
                .body(is("dispatched:ASYNC"));
    }

    /**
     * Several async requests at once: with the old blocking implementation these would exhaust the
     * event loop threads and the last ones would never be served.
     */
    @Test
    @Timeout(30)
    void concurrentAsyncRequestsDoNotStarveTheEventLoop() {
        Duration limit = Duration.ofSeconds(25);
        long start = System.nanoTime();
        for (int i = 0; i < 20; i++) {
            given().when().get("/async").then().statusCode(200).body(is("async-done"));
        }
        if (Duration.ofNanos(System.nanoTime() - start).compareTo(limit) > 0) {
            throw new AssertionError("20 async requests took longer than " + limit);
        }
    }

    @Test
    void blockingRunsOnAWorkerThread() {
        given()
                .when().get("/exec/blocking")
                .then()
                .statusCode(200)
                .body(startsWith("platform:executor-thread-"));
    }

    @Test
    void nonBlockingRunsOnTheEventLoop() {
        given()
                .when().get("/exec/non-blocking")
                .then()
                .statusCode(200)
                .body(startsWith("platform:vert.x-eventloop-thread-"));
    }

    @Test
    void runOnVirtualThreadRunsOnAVirtualThread() {
        given()
                .when().get("/exec/virtual")
                .then()
                .statusCode(200)
                .body(startsWith("virtual:"));
    }

    /** Unannotated servlets follow the configured default, which is event-loop out of the box. */
    @Test
    void defaultFollowsConfiguredExecutionModel() {
        given()
                .when().get("/exec/default")
                .then()
                .statusCode(200)
                .body(startsWith("platform:vert.x-eventloop-thread-"));
    }
}
