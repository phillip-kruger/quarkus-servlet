package io.quarkiverse.servlet.it;

import java.io.IOException;

import jakarta.servlet.annotation.HttpConstraint;
import jakarta.servlet.annotation.ServletSecurity;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Denied to everyone by its annotation, and granted to a role by {@code web.xml}.
 * <p>
 * Servlet 6.1, 13.4.1: the descriptor wins and the annotation is ignored for patterns it covers.
 * Honouring both is not additive but contradictory - an empty-role DENY beats any role list - so a
 * container that applies both locks out a resource the deployer opened. This deployment exists to
 * make that failure visible.
 */
@WebServlet(urlPatterns = "/descriptor-wins")
@ServletSecurity(@HttpConstraint(ServletSecurity.EmptyRoleSemantic.DENY))
public class DescriptorSecuredServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.getWriter().write("reached by " + req.getRemoteUser());
    }
}
