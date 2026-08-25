package io.quarkiverse.servlet.it;

import java.io.IOException;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Reports what {@link TldDeclaredListener} observed when the context started. */
@WebServlet(urlPatterns = "/tld-listener")
public class TldListenerServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("text/plain");
        resp.getWriter().print("ran=" + getServletContext().getAttribute(TldDeclaredListener.RAN)
                + ";restricted=" + getServletContext().getAttribute(TldDeclaredListener.RESTRICTED));
    }
}
