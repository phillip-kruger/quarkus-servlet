package io.quarkiverse.servlet.it;

import java.io.IOException;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Starts async processing and completes it from a different thread, after service() has returned.
 * This is the pattern that used to deadlock the event loop.
 */
@WebServlet(urlPatterns = "/async", asyncSupported = true)
public class AsyncServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        AsyncContext ac = req.startAsync();
        ac.setTimeout(10000);
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(50);
                ac.getResponse().getWriter().write("async-done");
                ac.complete();
            } catch (Exception e) {
                ac.complete();
            }
        });
    }
}
