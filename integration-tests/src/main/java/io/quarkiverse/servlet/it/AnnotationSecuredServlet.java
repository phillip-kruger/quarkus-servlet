package io.quarkiverse.servlet.it;

import java.io.IOException;

import jakarta.servlet.annotation.HttpConstraint;
import jakarta.servlet.annotation.ServletSecurity;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Secured only by its annotation, on a path {@code web.xml} says nothing about. Here the annotation
 * is the whole constraint - and when it was not scanned at all, this servlet was open to anyone.
 */
@WebServlet(urlPatterns = "/annotation-secured")
@ServletSecurity(@HttpConstraint(rolesAllowed = "tester"))
public class AnnotationSecuredServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.getWriter().write("secured by annotation");
    }
}
