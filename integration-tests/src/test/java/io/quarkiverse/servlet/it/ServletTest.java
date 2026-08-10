package io.quarkiverse.servlet.it;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class ServletTest {

    @Test
    void testGetRequest() {
        given()
                .when().get("/test")
                .then()
                .statusCode(200)
                .body(is("Hello from Servlet"));
    }

    @Test
    void testPostRequest() {
        given()
                .body("hello world")
                .when().post("/test")
                .then()
                .statusCode(200)
                .body(is("Received: hello world"));
    }

    @Test
    void testQueryParameters() {
        given()
                .queryParam("name", "Quarkus")
                .when().get("/params")
                .then()
                .statusCode(200)
                .body(is("name=Quarkus"));
    }

    @Test
    void testHeaders() {
        given()
                .header("X-Test", "foo")
                .when().get("/headers")
                .then()
                .statusCode(200)
                .header("X-Custom", "value")
                .body(is("X-Test=foo"));
    }

    @Test
    void testCookies() {
        given()
                .when().get("/cookies")
                .then()
                .statusCode(200)
                .header("set-cookie", notNullValue());
    }

    @Test
    void testCookiesWithInput() {
        given()
                .cookie("input", "test-value")
                .when().get("/cookies")
                .then()
                .statusCode(200)
                .body(containsString("input=test-value"));
    }

    @Test
    void testFilter() {
        given()
                .when().get("/filtered/test")
                .then()
                .statusCode(200)
                .header("X-Filtered", "true")
                .body(is("Filtered content"));
    }

    @Test
    void testCdi() {
        given()
                .when().get("/cdi")
                .then()
                .statusCode(200)
                .body(is("Hello from CDI"));
    }

    @Test
    void testNotFound() {
        given()
                .when().get("/nonexistent")
                .then()
                .statusCode(404);
    }

    @Test
    void testContentType() {
        given()
                .when().get("/test")
                .then()
                .contentType(containsString("text/plain"));
    }
}
