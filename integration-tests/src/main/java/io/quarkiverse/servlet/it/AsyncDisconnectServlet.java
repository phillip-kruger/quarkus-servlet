package io.quarkiverse.servlet.it;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.inject.Inject;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Starts an async cycle that is never completed by the application - the shape of a Server-Sent
 * Events endpoint. The registered listener records its callbacks so the test can prove they fire
 * when the client disconnects, rather than the AsyncContext leaking until undeploy.
 * <p>
 * The timeout is left off (0 = never), so a disconnect is the only thing that can end the cycle: if
 * the container failed to notice the closed connection, nothing else would rescue the test.
 */
@WebServlet(urlPatterns = "/async-disconnect", asyncSupported = true)
public class AsyncDisconnectServlet extends HttpServlet {

    @Inject
    AsyncDisconnectTracker tracker;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        tracker.reset();
        AsyncContext ac = req.startAsync();
        ac.setTimeout(0);
        ac.addListener(new AsyncListener() {
            @Override
            public void onComplete(AsyncEvent event) {
                tracker.complete();
            }

            @Override
            public void onError(AsyncEvent event) {
                tracker.error();
            }

            @Override
            public void onTimeout(AsyncEvent event) {
            }

            @Override
            public void onStartAsync(AsyncEvent event) {
            }
        });
        // Commit the response and stream one line, so the client has a live connection to drop.
        resp.setContentType("text/plain");
        resp.getOutputStream().write("open\n".getBytes(StandardCharsets.UTF_8));
        resp.getOutputStream().flush();
    }
}
