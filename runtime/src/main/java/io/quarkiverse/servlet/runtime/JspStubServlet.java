package io.quarkiverse.servlet.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class JspStubServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        String jspFile = getServletConfig().getInitParameter("jspFile");
        if (jspFile == null) {
            resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "No jspFile configured");
            return;
        }

        InputStream is = getServletContext().getResourceAsStream(jspFile);
        if (is == null) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND, "JSP file not found: " + jspFile);
            return;
        }

        String source;
        try (is) {
            source = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }

        resp.setContentType("text/html");
        resp.getWriter().write(evaluate(source));
    }

    static String evaluate(String source) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < source.length()) {
            if (source.startsWith("<%--", i)) {
                int end = source.indexOf("--%>", i + 4);
                i = (end >= 0) ? end + 4 : source.length();
            } else if (source.startsWith("<%=", i)) {
                int end = source.indexOf("%>", i + 3);
                if (end >= 0) {
                    String expr = source.substring(i + 3, end).trim();
                    out.append(evaluateExpression(expr));
                    i = end + 2;
                } else {
                    out.append(source.charAt(i));
                    i++;
                }
            } else if (source.startsWith("<%", i)) {
                int end = source.indexOf("%>", i + 2);
                i = (end >= 0) ? end + 2 : source.length();
            } else {
                out.append(source.charAt(i));
                i++;
            }
        }
        return out.toString();
    }

    private static String evaluateExpression(String expr) {
        if (expr.startsWith("\"") && expr.endsWith("\"") && expr.length() >= 2) {
            return expr.substring(1, expr.length() - 1);
        }
        return expr;
    }
}
