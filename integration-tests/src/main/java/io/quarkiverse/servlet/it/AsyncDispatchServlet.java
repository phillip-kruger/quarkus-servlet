package io.quarkiverse.servlet.it;

import java.io.IOException;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Starts async processing and dispatches to another servlet from a separate thread once service()
 * has already returned - the ordering the container has to cope with.
 */
@WebServlet(urlPatterns = "/async-dispatch", asyncSupported = true)
public class AsyncDispatchServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        AsyncContext ac = req.startAsync();
        ac.setTimeout(10000);
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(50);
                ac.dispatch("/async-target");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                ac.complete();
            }
        });
    }
}
