package io.quarkiverse.servlet.it;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Flushing an empty response must commit it (Servlet 6.1, {@code ServletResponse.flushBuffer}),
 * after which reset is rejected and further body output still works.
 */
@QuarkusTest
class FlushBufferTest {

    @Test
    void emptyFlushCommitsAndAllowsFurtherOutput() {
        given()
                .when().get("/flush-buffer")
                .then()
                .statusCode(200)
                .body(is("true:body"));
    }

    @Test
    void resetBufferRejectedAfterEmptyFlush() {
        given()
                .when().get("/flush-buffer?action=resetBuffer")
                .then()
                .statusCode(200)
                .body(is("true"));
    }

    @Test
    void resetRejectedAfterEmptyFlush() {
        given()
                .when().get("/flush-buffer?action=reset")
                .then()
                .statusCode(200)
                .body(is("true"));
    }
}
