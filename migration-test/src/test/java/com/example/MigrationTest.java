package com.example;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
public class MigrationTest {

    @Test
    void testGreetingWithCDI() {
        given().when().get("/greet?name=Quarkus")
                .then().statusCode(200)
                .body(is("Hello, Quarkus!"));
    }

    @Test
    void testGreetingDefault() {
        given().when().get("/greet")
                .then().statusCode(200)
                .body(is("Hello, World!"));
    }

    @Test
    void testJsonGet() {
        given().when().get("/api/data")
                .then().statusCode(200)
                .contentType("application/json")
                .body(containsString("\"status\":\"ok\""));
    }

    @Test
    void testJsonPost() {
        given().contentType("text/plain")
                .body("test data")
                .when().post("/api/data")
                .then().statusCode(200)
                .body(containsString("test data"));
    }

    @Test
    void testFilter() {
        given().when().get("/api/data")
                .then().statusCode(200)
                .header("X-Filtered", "true");
    }

    @Test
    void testSession() {
        String sessionCookie = given().when().get("/session")
                .then().statusCode(200)
                .body(is("count=1"))
                .cookie("JSESSIONID")
                .extract().cookie("JSESSIONID");

        given().cookie("JSESSIONID", sessionCookie)
                .when().get("/session")
                .then().statusCode(200)
                .body(is("count=2"));
    }

    @Test
    void testForward() {
        given().when().get("/forward")
                .then().statusCode(200)
                .body(is("Hello, Forwarded!"));
    }

    @Test
    void testStaticResource() {
        given().when().get("/index.html")
                .then().statusCode(200)
                .body(containsString("Welcome to the servlet app"));
    }
}
