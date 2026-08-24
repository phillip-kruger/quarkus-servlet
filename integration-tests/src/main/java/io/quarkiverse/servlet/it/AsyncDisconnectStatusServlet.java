package io.quarkiverse.servlet.it;

import java.io.IOException;

import jakarta.inject.Inject;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Reports whether the {@link AsyncDisconnectServlet}'s listener saw the disconnect, waiting a while
 * for the callbacks (which run asynchronously on another thread once the connection drops).
 */
@WebServlet(urlPatterns = "/async-disconnect-status")
public class AsyncDisconnectStatusServlet extends HttpServlet {

    @Inject
    AsyncDisconnectTracker tracker;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            tracker.awaitComplete(10000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        resp.setContentType("text/plain");
        resp.getWriter().write("error=" + tracker.errored() + ",complete=" + tracker.completedNormally());
    }
}
