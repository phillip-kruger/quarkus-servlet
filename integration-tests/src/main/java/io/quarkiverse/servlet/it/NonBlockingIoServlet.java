package io.quarkiverse.servlet.it;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Reads the request body through a {@link ReadListener} and echoes its length back. Exercises the
 * spec's non-blocking read state machine: onDataAvailable must only be delivered while isReady(),
 * and onAllDataRead only once everything has actually been consumed.
 */
@WebServlet(urlPatterns = "/nio-read", asyncSupported = true)
public class NonBlockingIoServlet extends HttpServlet {

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        AsyncContext ac = req.startAsync();
        ac.setTimeout(10000);

        ServletInputStream in = req.getInputStream();
        ByteArrayOutputStream collected = new ByteArrayOutputStream();

        in.setReadListener(new ReadListener() {
            @Override
            public void onDataAvailable() throws IOException {
                byte[] chunk = new byte[64];
                while (in.isReady()) {
                    int read = in.read(chunk);
                    if (read < 0) {
                        break;
                    }
                    collected.write(chunk, 0, read);
                }
            }

            @Override
            public void onAllDataRead() throws IOException {
                ac.getResponse().getWriter()
                        .write("read=" + collected.size() + " body=" + collected.toString("UTF-8"));
                ac.complete();
            }

            @Override
            public void onError(Throwable t) {
                ac.complete();
            }
        });
    }
}
