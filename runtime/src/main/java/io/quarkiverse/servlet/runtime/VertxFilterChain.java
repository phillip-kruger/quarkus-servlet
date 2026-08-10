package io.quarkiverse.servlet.runtime;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;

public class VertxFilterChain implements FilterChain {

    private final FilterInfo[] filters;
    private final ServletInfo servletInfo;
    private int currentIndex;

    public VertxFilterChain(FilterInfo[] filters, ServletInfo servletInfo) {
        this.filters = filters;
        this.servletInfo = servletInfo;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response) throws IOException, ServletException {
        if (currentIndex < filters.length) {
            FilterInfo filter = filters[currentIndex++];
            filter.getFilter().doFilter(request, response, this);
            return;
        }
        servletInfo.getServlet().service(request, response);
    }
}
