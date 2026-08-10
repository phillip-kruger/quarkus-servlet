package io.quarkiverse.servlet.it;

import java.io.IOException;

import jakarta.enterprise.context.SessionScoped;
import jakarta.inject.Inject;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Invalidates a session that holds a {@code @SessionScoped} bean. The CDI session context reads the
 * session while it is being destroyed, so invalidating before notifying listeners made this throw.
 */
@WebServlet(urlPatterns = "/session-invalidate")
public class SessionLifecycleServlet extends HttpServlet {

    @SessionScoped
    public static class SessionBean implements java.io.Serializable {
        public String value() {
            return "session bean";
        }
    }

    @Inject
    SessionBean bean;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        req.getSession(true).setAttribute("touched", bean.value());
        req.getSession().invalidate();
        resp.getWriter().write("invalidated");
    }
}
