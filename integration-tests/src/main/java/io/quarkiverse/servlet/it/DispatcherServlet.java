package io.quarkiverse.servlet.it;

import java.io.IOException;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Forwards through {@link jakarta.servlet.ServletContext#getRequestDispatcher(String)}. That path
 * needs the context to hold a reference back to the deployment; without it every forward failed.
 */
@WebServlet(urlPatterns = "/context-forward")
public class DispatcherServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        getServletContext().getRequestDispatcher("/forward-target").forward(req, resp);
    }
}
