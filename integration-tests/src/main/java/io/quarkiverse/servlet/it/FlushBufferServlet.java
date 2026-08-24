package io.quarkiverse.servlet.it;

import java.io.IOException;

import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Exercises the Servlet contract around flushing an empty response: {@code flushBuffer()} must
 * commit the response even when nothing has been written, after which {@code reset()} and
 * {@code resetBuffer()} must be rejected, while further output can still be written and closed.
 */
@WebServlet(urlPatterns = "/flush-buffer")
public class FlushBufferServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String action = req.getParameter("action");
        if ("resetBuffer".equals(action)) {
            resetBuffer(resp);
        } else if ("reset".equals(action)) {
            reset(resp);
        } else {
            flushAndWrite(resp);
        }
    }

    private void flushAndWrite(HttpServletResponse resp) throws IOException {
        ServletOutputStream output = resp.getOutputStream();
        resp.flushBuffer();
        boolean committed = resp.isCommitted();
        // A second flush of an already-committed empty response must remain valid.
        resp.flushBuffer();
        output.print(committed + ":body");
        output.close();
    }

    private void resetBuffer(HttpServletResponse resp) throws IOException {
        ServletOutputStream output = resp.getOutputStream();
        resp.flushBuffer();
        boolean resetRejected = false;
        try {
            resp.resetBuffer();
        } catch (IllegalStateException expected) {
            resetRejected = true;
        }
        output.print(Boolean.toString(resetRejected));
        output.close();
    }

    private void reset(HttpServletResponse resp) throws IOException {
        ServletOutputStream output = resp.getOutputStream();
        resp.flushBuffer();
        boolean resetRejected = false;
        try {
            resp.reset();
        } catch (IllegalStateException expected) {
            resetRejected = true;
        }
        output.print(Boolean.toString(resetRejected));
        output.close();
    }
}
