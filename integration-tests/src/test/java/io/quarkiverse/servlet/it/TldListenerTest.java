package io.quarkiverse.servlet.it;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

/**
 * A {@code <listener>} declared in a TLD has to be registered (Jakarta Pages 3.1 section 7.3.1) and
 * then restricted (Servlet 6.1 section 4.4), because a TLD is not a deployment descriptor.
 */
@QuarkusTest
class TldListenerTest {

    @Test
    void tldListenerRunsAndIsRestricted() {
        given()
                .when().get("/tld-listener")
                .then()
                .statusCode(200)
                .body(is("ran=true;restricted=true"));
    }
}
