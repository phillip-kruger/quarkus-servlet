package io.quarkiverse.servlet.it.fragment;

import java.io.IOException;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Declared only in this library's {@code web-fragment.xml}, never in the application. */
public class FragmentServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.getWriter().write("fragment servlet: " + getInitParameter("origin"));
    }
}
