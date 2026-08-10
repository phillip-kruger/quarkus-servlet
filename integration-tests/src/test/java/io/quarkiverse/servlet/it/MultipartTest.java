package io.quarkiverse.servlet.it;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Multipart used to be entirely non-functional: getParts() always returned an empty collection
 * because it read Vert.x upload state that nothing populated.
 */
@QuarkusTest
class MultipartTest {

    @Test
    void filePartIsParsed() {
        given()
                .multiPart("file", "hello.txt", "file contents".getBytes(), "text/plain")
                .multiPart("field", "field value")
                .when().post("/multipart")
                .then()
                .statusCode(200)
                .body(containsString("part=file file=hello.txt type=text/plain size=13"))
                .body(containsString("body=file contents"));
    }

    @Test
    void formFieldIsParsedAndExposedAsAParameter() {
        given()
                .multiPart("file", "hello.txt", "x".getBytes(), "text/plain")
                .multiPart("field", "field value")
                .when().post("/multipart")
                .then()
                .statusCode(200)
                .body(containsString("part=field"))
                .body(containsString("getParameter(field)=field value"));
    }

    @Test
    void namedLookupFindsThePart() {
        given()
                .multiPart("file", "report.csv", "a,b".getBytes(), "text/csv")
                .when().post("/multipart")
                .then()
                .statusCode(200)
                .body(containsString("getPart(file)=report.csv"));
    }

    @Test
    void binaryContentSurvivesIntact() {
        byte[] binary = new byte[256];
        for (int i = 0; i < binary.length; i++) {
            binary[i] = (byte) i;
        }
        given()
                .multiPart("file", "blob.bin", binary, "application/octet-stream")
                .when().post("/multipart")
                .then()
                .statusCode(200)
                .body(containsString("size=256"));
    }
}
