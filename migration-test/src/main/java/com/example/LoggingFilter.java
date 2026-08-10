package com.example;

import java.io.IOException;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletResponse;

@WebFilter(urlPatterns = "/api/*", dispatcherTypes = { DispatcherType.REQUEST, DispatcherType.FORWARD })
public class LoggingFilter implements Filter {

    @Override
    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain)
            throws IOException, jakarta.servlet.ServletException {
        ((HttpServletResponse) resp).setHeader("X-Filtered", "true");
        chain.doFilter(req, resp);
    }
}
