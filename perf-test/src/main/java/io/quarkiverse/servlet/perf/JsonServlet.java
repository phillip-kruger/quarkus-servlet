package io.quarkiverse.servlet.perf;

import java.io.IOException;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@WebServlet(urlPatterns = "/json")
public class JsonServlet extends HttpServlet {

    private static final String RESPONSE = "{\"message\":\"Hello, World!\"}";

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json");
        resp.getWriter().write(RESPONSE);
    }
}
