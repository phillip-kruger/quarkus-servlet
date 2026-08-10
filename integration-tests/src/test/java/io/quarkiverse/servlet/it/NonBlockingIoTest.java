package io.quarkiverse.servlet.it;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.quarkus.test.junit.QuarkusTest;

/**
 * The ReadListener path previously fired onAllDataRead unconditionally after a single
 * onDataAvailable, so a listener that had not consumed everything still saw completion.
 */
@QuarkusTest
class NonBlockingIoTest {

    @Test
    @Timeout(20)
    void readListenerConsumesTheWholeBody() {
        String body = "hello non-blocking world";
        given()
                .body(body)
                .when().post("/nio-read")
                .then()
                .statusCode(200)
                .body(is("read=" + body.length() + " body=" + body));
    }

    @Test
    @Timeout(20)
    void readListenerHandlesABodyLargerThanOneChunk() {
        String body = "x".repeat(5000);
        given()
                .body(body)
                .when().post("/nio-read")
                .then()
                .statusCode(200)
                .body(is("read=5000 body=" + body));
    }

    @Test
    @Timeout(20)
    void readListenerHandlesAnEmptyBody() {
        given()
                .body("")
                .when().post("/nio-read")
                .then()
                .statusCode(200)
                .body(is("read=0 body="));
    }
}
