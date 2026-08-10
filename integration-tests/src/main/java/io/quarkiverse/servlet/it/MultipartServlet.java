package io.quarkiverse.servlet.it;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.MultipartConfig;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;

/**
 * Reports what the container parsed out of a multipart request, so the test can assert part names,
 * file names, content types, sizes and bodies.
 */
@MultipartConfig
@WebServlet(urlPatterns = "/multipart")
public class MultipartServlet extends HttpServlet {

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {

        List<String> lines = new ArrayList<>();
        for (Part part : req.getParts()) {
            lines.add("part=" + part.getName()
                    + " file=" + part.getSubmittedFileName()
                    + " type=" + part.getContentType()
                    + " size=" + part.getSize()
                    + " body=" + read(part));
        }
        // A named lookup and a form field surfaced as a request parameter.
        Part named = req.getPart("file");
        lines.add("getPart(file)=" + (named != null ? named.getSubmittedFileName() : "null"));
        lines.add("getParameter(field)=" + req.getParameter("field"));

        resp.setContentType("text/plain");
        resp.getWriter().write(String.join("\n", lines));
    }

    private static String read(Part part) throws IOException {
        try (InputStream in = part.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
