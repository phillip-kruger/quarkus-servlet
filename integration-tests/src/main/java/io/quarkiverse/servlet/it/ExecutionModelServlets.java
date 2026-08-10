package io.quarkiverse.servlet.it;

import java.io.IOException;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import io.smallrye.common.annotation.Blocking;
import io.smallrye.common.annotation.NonBlocking;
import io.smallrye.common.annotation.RunOnVirtualThread;

/**
 * Servlets that report the thread they were invoked on, so the tests can assert which execution
 * model each annotation actually selects.
 */
public final class ExecutionModelServlets {

    private ExecutionModelServlets() {
    }

    private static void writeThread(HttpServletResponse resp) throws IOException {
        Thread current = Thread.currentThread();
        resp.getWriter().write((current.isVirtual() ? "virtual:" : "platform:") + current.getName());
    }

    /** Blocking work on a worker thread. */
    @Blocking
    @WebServlet(urlPatterns = "/exec/blocking")
    public static class BlockingServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            try {
                // safe here, and would stall the whole server on the event loop
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            writeThread(resp);
        }
    }

    /** Explicitly pinned to the event loop. */
    @NonBlocking
    @WebServlet(urlPatterns = "/exec/non-blocking")
    public static class NonBlockingServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            writeThread(resp);
        }
    }

    @RunOnVirtualThread
    @WebServlet(urlPatterns = "/exec/virtual")
    public static class VirtualThreadServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            writeThread(resp);
        }
    }

    /** No annotation: follows quarkus.servlet.execution-model. */
    @WebServlet(urlPatterns = "/exec/default")
    public static class DefaultServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            writeThread(resp);
        }
    }
}
