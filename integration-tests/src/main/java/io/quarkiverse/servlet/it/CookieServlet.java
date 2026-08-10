package io.quarkiverse.servlet.it;

import java.io.IOException;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@WebServlet(urlPatterns = "/cookies")
public class CookieServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.addCookie(new Cookie("test-cookie", "cookie-value"));
        resp.setContentType("text/plain");

        Cookie[] cookies = req.getCookies();
        if (cookies != null) {
            StringBuilder sb = new StringBuilder();
            for (Cookie c : cookies) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(c.getName()).append("=").append(c.getValue());
            }
            resp.getWriter().write("cookies=" + sb);
        } else {
            resp.getWriter().write("cookies=none");
        }
    }
}
