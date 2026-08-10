package io.quarkiverse.servlet.it;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Covers the deployment-descriptor and context-lifecycle behaviour that the Servlet TCK found
 * missing once it began booting a real Quarkus application.
 * <p>
 * Every assertion here corresponds to something that was silently absent rather than merely wrong:
 * listeners that never ran, fragments that were never read, a context that never opened its
 * initialization window. None of it was visible to the extension's other tests, which is why these
 * exist - a regression in any of it should fail in seconds here rather than in a TCK run nobody
 * remembers to start.
 */
@QuarkusTest
class DeploymentDescriptorTest {

    @Test
    void listenerDeclaredInWebXmlRuns() {
        given().when().get("/lifecycle")
                .then().statusCode(200)
                .body(containsString("listenerRan=true"));
    }

    @Test
    void listenerCanRegisterServletsProgrammatically() {
        // Registration is only legal while the context is initializing. If that window never
        // opens, the listener above throws and the application does not start at all.
        given().when().get("/lifecycle")
                .then().statusCode(200)
                .body(containsString("programmaticRegistered=true"));

        given().when().get("/programmatic")
                .then().statusCode(200)
                .body(is("registered programmatically"));
    }

    @Test
    void servletContainerInitializerRunsWithHandledTypes() {
        given().when().get("/lifecycle")
                .then().statusCode(200)
                .body(containsString("sciRan=true"))
                // HandledType implements HandledMarker, so @HandlesTypes must match exactly one.
                .body(containsString("sciHandled=1"));
    }

    @Test
    void servletFromLibraryWebFragmentIsDeployed() {
        given().when().get("/lifecycle")
                .then().statusCode(200)
                .body(containsString("fragmentRegistered=true"));

        given().when().get("/fragment")
                .then().statusCode(200)
                .body(is("fragment servlet: web-fragment.xml"));
    }

    @Test
    void sessionConfigFromWebXmlIsApplied() {
        given().when().get("/lifecycle")
                .then().statusCode(200)
                .body(containsString("sessionTimeout=42"));
    }

    @Test
    void effectiveVersionReportsTheDescriptorNotTheContainer() {
        // web.xml declares 5.0 while the container implements 6.1.
        given().when().get("/lifecycle")
                .then().statusCode(200)
                .body(containsString("effectiveVersion=5.0"));
    }

    @Test
    void forwardThroughServletContextDispatcherWorks() {
        given().when().get("/context-forward")
                .then().statusCode(200)
                .body(is("forwarded"));
    }

    @Test
    void invalidatingASessionHoldingASessionScopedBeanSucceeds() {
        given().when().get("/session-invalidate")
                .then().statusCode(200)
                .body(is("invalidated"));
    }

    @Test
    void annotationSecuresAPathTheDescriptorSaysNothingAbout() {
        given().when().get("/annotation-secured")
                .then().statusCode(401);

        given().auth().preemptive().basic("tester", "tester")
                .when().get("/annotation-secured")
                .then().statusCode(200);
    }

    @Test
    void descriptorConstraintWinsOverTheServletSecurityAnnotation() {
        // The annotation denies everyone; web.xml grants the tester role. Applying both would
        // deny this to everyone, since an empty-role DENY beats any role list.
        given().auth().preemptive().basic("tester", "tester")
                .when().get("/descriptor-wins")
                .then().statusCode(200)
                .body(containsString("tester"));
    }
}
