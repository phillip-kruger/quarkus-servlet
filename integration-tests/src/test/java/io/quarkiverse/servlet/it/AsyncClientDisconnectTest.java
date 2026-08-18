package io.quarkiverse.servlet.it;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;

/**
 * When a client drops the connection on a never-completed async cycle - the normal end of a
 * Server-Sent Events stream - the registered {@link jakarta.servlet.AsyncListener} must still be
 * notified (onError then onComplete) and the request state released. Otherwise the AsyncContext
 * leaks until the deployment stops.
 */
@QuarkusTest
class AsyncClientDisconnectTest {

    @TestHTTPResource("/async-disconnect")
    URL disconnectUrl;

    @Test
    @Timeout(30)
    void asyncListenerNotifiedWhenClientDisconnects() throws Exception {
        // Open the async stream on a raw socket, read the first flushed line, then drop the
        // connection abruptly without the server ever completing the cycle.
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(disconnectUrl.getHost(), disconnectUrl.getPort()), 5000);
            OutputStream out = socket.getOutputStream();
            out.write(("GET " + disconnectUrl.getPath() + " HTTP/1.1\r\n"
                    + "Host: " + disconnectUrl.getHost() + "\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();

            InputStream in = socket.getInputStream();
            byte[] buffer = new byte[256];
            in.read(buffer);

            // Give the container a moment to register its end handler before the drop, so this
            // exercises a genuine mid-stream disconnect rather than a race at handoff.
            Thread.sleep(500);
        }

        // A second request reports the listener state; it waits for the callbacks, which run on
        // another thread once the disconnect is observed.
        given()
                .when().get("/async-disconnect-status")
                .then()
                .statusCode(200)
                .body(containsString("error=true"))
                .body(containsString("complete=true"));
    }
}
