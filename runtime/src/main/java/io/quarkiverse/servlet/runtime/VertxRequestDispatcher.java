package io.quarkiverse.servlet.runtime;

import java.io.IOException;
import java.util.Locale;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;

public class VertxRequestDispatcher implements RequestDispatcher {

    private final String path;
    private final ServletDeployment deployment;
    private final boolean namedDispatch;
    private final String servletName;

    public VertxRequestDispatcher(String path, ServletDeployment deployment) {
        this(path, deployment, false, null);
    }

    public VertxRequestDispatcher(String path, ServletDeployment deployment,
            boolean namedDispatch, String servletName) {
        this.path = path;
        this.deployment = deployment;
        this.namedDispatch = namedDispatch;
        this.servletName = servletName;
    }

    @Override
    public void forward(ServletRequest request, ServletResponse response) throws ServletException, IOException {
        HttpServletRequest httpReq = unwrapHttp(request);
        HttpServletResponse httpResp = unwrapHttpResponse(response);

        if (httpResp.isCommitted()) {
            throw new IllegalStateException("Cannot forward after response has been committed");
        }

        httpResp.reset();

        String targetPath = path;
        String targetQueryString = null;
        if (path != null) {
            int queryIdx = path.indexOf('?');
            if (queryIdx >= 0) {
                targetPath = path.substring(0, queryIdx);
                targetQueryString = path.substring(queryIdx + 1);
            }
        }

        // Set FORWARD dispatch attributes only for path-based dispatches (not named)
        // and only on the FIRST forward in a chain (attributes reflect the original request)
        if (!namedDispatch && httpReq.getAttribute(RequestDispatcher.FORWARD_REQUEST_URI) == null) {
            httpReq.setAttribute(RequestDispatcher.FORWARD_REQUEST_URI, httpReq.getRequestURI());
            httpReq.setAttribute(RequestDispatcher.FORWARD_CONTEXT_PATH, httpReq.getContextPath());
            httpReq.setAttribute(RequestDispatcher.FORWARD_SERVLET_PATH, httpReq.getServletPath());
            httpReq.setAttribute(RequestDispatcher.FORWARD_PATH_INFO, httpReq.getPathInfo());
            httpReq.setAttribute(RequestDispatcher.FORWARD_QUERY_STRING, httpReq.getQueryString());
            try {
                httpReq.setAttribute(RequestDispatcher.FORWARD_MAPPING, httpReq.getHttpServletMapping());
            } catch (UnsupportedOperationException e) {
                // HttpServletMapping not yet implemented, skip
            }
        }

        if (deployment == null) {
            throw new ServletException("No deployment available for forward to: " + path);
        }

        ServletInfo target;
        UrlPatternMatcher.MatchResult match;
        if (namedDispatch && servletName != null) {
            target = deployment.getServlets().get(servletName);
            match = null;
            if (target == null) {
                throw new ServletException("No servlet found with name: " + servletName);
            }
        } else {
            match = deployment.matchServlet(targetPath);
            if (match == null) {
                throw new ServletException("No servlet found for path: " + targetPath);
            }
            target = match.getServletInfo();
        }
        try {
            deployment.ensureServletInitialized(target);
        } catch (ServletException e) {
            throw e;
        } catch (Exception e) {
            throw new ServletException("Failed to initialize servlet for forward", e);
        }

        // Update request paths and dispatch type for the forwarded servlet
        DispatcherType originalDispatchType = null;
        VertxServletRequest vsr = unwrap(httpReq);
        if (vsr != null) {
            originalDispatchType = vsr.getDispatcherType();
            vsr.setDispatcherType(DispatcherType.FORWARD);
            if (!namedDispatch && match != null) {
                vsr.setForwardedPath(
                        httpReq.getContextPath() + targetPath,
                        match.getServletPath(),
                        match.getPathInfo(),
                        targetQueryString);
            }
        }

        // Also set dispatch query string for parameter merging
        if (vsr != null && targetQueryString != null) {
            vsr.setDispatchQueryString(targetQueryString);
        }

        FilterInfo[] filters = deployment.getMatchingFilters(
                targetPath, target.getName(), DispatcherType.FORWARD);
        // Async support belongs to the chain actually handling the request, so it is recomputed
        // for the forward target rather than inherited from the forwarding servlet.
        if (vsr != null) {
            vsr.setAsyncSupported(ServletDeployment.isAsyncSupported(target, filters));
        }
        VertxFilterChain chain = new VertxFilterChain(filters, target);
        try {
            chain.doFilter(request, response);
            response.flushBuffer();
        } catch (RuntimeException e) {
            throw e;
        } catch (IOException e) {
            throw e;
        } catch (ServletException e) {
            throw e;
        } catch (Exception e) {
            throw new ServletException(e);
        }
    }

    @Override
    public void include(ServletRequest request, ServletResponse response) throws ServletException, IOException {
        HttpServletRequest httpReq = unwrapHttp(request);
        HttpServletResponse httpResp = unwrapHttpResponse(response);

        String targetPath = path;
        String targetQueryString = null;
        if (path != null) {
            int queryIdx = path.indexOf('?');
            if (queryIdx >= 0) {
                targetPath = path.substring(0, queryIdx);
                targetQueryString = path.substring(queryIdx + 1);
            }
        }

        if (deployment == null) {
            throw new ServletException("No deployment available for include of: " + path);
        }

        ServletInfo target;
        UrlPatternMatcher.MatchResult match;
        if (namedDispatch && servletName != null) {
            target = deployment.getServlets().get(servletName);
            match = null;
            if (target == null) {
                throw new ServletException("No servlet found with name: " + servletName);
            }
        } else {
            match = deployment.matchServlet(targetPath);
            if (match == null) {
                throw new ServletException("No servlet found for path: " + targetPath);
            }
            target = match.getServletInfo();
        }

        DispatcherType originalDispatchType = null;
        VertxServletRequest vsr = unwrap(httpReq);
        if (vsr != null) {
            originalDispatchType = vsr.getDispatcherType();
            vsr.setDispatcherType(DispatcherType.INCLUDE);
            if (targetQueryString != null) {
                vsr.setDispatchQueryString(targetQueryString);
            }
        }
        if (!namedDispatch && match != null) {
            httpReq.setAttribute(RequestDispatcher.INCLUDE_REQUEST_URI, httpReq.getContextPath() + targetPath);
            httpReq.setAttribute(RequestDispatcher.INCLUDE_CONTEXT_PATH, httpReq.getContextPath());
            httpReq.setAttribute(RequestDispatcher.INCLUDE_SERVLET_PATH, match.getServletPath());
            httpReq.setAttribute(RequestDispatcher.INCLUDE_PATH_INFO, match.getPathInfo());
            httpReq.setAttribute(RequestDispatcher.INCLUDE_QUERY_STRING, targetQueryString);
        }

        HttpServletResponse wrappedResponse = new IncludeResponseWrapper(httpResp);

        try {
            deployment.ensureServletInitialized(target);
        } catch (ServletException e) {
            throw e;
        } catch (Exception e) {
            throw new ServletException("Failed to initialize servlet for include", e);
        }

        try {
            FilterInfo[] includeFilters = deployment.getMatchingFilters(
                    targetPath, target.getName(), DispatcherType.INCLUDE);
            if (vsr != null) {
                vsr.setAsyncSupported(ServletDeployment.isAsyncSupported(target, includeFilters));
            }
            VertxFilterChain includeChain = new VertxFilterChain(includeFilters, target);
            includeChain.doFilter(request, wrappedResponse);
        } finally {
            if (vsr != null) {
                if (originalDispatchType != null) {
                    vsr.setDispatcherType(originalDispatchType);
                }
                vsr.clearDispatchQueryString();
            }
            httpReq.removeAttribute(RequestDispatcher.INCLUDE_REQUEST_URI);
            httpReq.removeAttribute(RequestDispatcher.INCLUDE_CONTEXT_PATH);
            httpReq.removeAttribute(RequestDispatcher.INCLUDE_SERVLET_PATH);
            httpReq.removeAttribute(RequestDispatcher.INCLUDE_PATH_INFO);
            httpReq.removeAttribute(RequestDispatcher.INCLUDE_QUERY_STRING);
            httpReq.removeAttribute(RequestDispatcher.INCLUDE_MAPPING);
        }
    }

    private static HttpServletRequest unwrapHttp(ServletRequest request) {
        while (request != null) {
            if (request instanceof HttpServletRequest hsr) {
                return hsr;
            }
            if (request instanceof jakarta.servlet.ServletRequestWrapper wrapper) {
                request = wrapper.getRequest();
            } else {
                break;
            }
        }
        throw new IllegalArgumentException("Cannot unwrap to HttpServletRequest");
    }

    private static HttpServletResponse unwrapHttpResponse(ServletResponse response) {
        while (response != null) {
            if (response instanceof HttpServletResponse hsr) {
                return hsr;
            }
            if (response instanceof jakarta.servlet.ServletResponseWrapper wrapper) {
                response = wrapper.getResponse();
            } else {
                break;
            }
        }
        throw new IllegalArgumentException("Cannot unwrap to HttpServletResponse");
    }

    private static VertxServletRequest unwrap(ServletRequest request) {
        while (request != null) {
            if (request instanceof VertxServletRequest vsr) {
                return vsr;
            }
            if (request instanceof jakarta.servlet.ServletRequestWrapper wrapper) {
                request = wrapper.getRequest();
            } else {
                return null;
            }
        }
        return null;
    }

    private void fireRequestInitialized(ServletRequest request) {
        if (deployment != null) {
            for (java.util.EventListener l : deployment.getServletContext().getListeners()) {
                if (l instanceof jakarta.servlet.ServletRequestListener srl) {
                    srl.requestInitialized(
                            new jakarta.servlet.ServletRequestEvent(deployment.getServletContext(), request));
                }
            }
        }
    }

    private void fireRequestDestroyed(ServletRequest request) {
        if (deployment != null) {
            var listeners = deployment.getServletContext().getListeners();
            for (int i = listeners.size() - 1; i >= 0; i--) {
                if (listeners.get(i) instanceof jakarta.servlet.ServletRequestListener srl) {
                    srl.requestDestroyed(
                            new jakarta.servlet.ServletRequestEvent(deployment.getServletContext(), request));
                }
            }
        }
    }

    /**
     * Response wrapper for include() that prevents the included servlet from
     * modifying the response status, headers, or sending errors/redirects.
     * The included servlet CAN write to the body via getOutputStream/getWriter.
     */
    private static class IncludeResponseWrapper extends HttpServletResponseWrapper {

        IncludeResponseWrapper(HttpServletResponse response) {
            super(response);
        }

        @Override
        public void setStatus(int sc) {
            // no-op: included servlet must not change status
        }

        @Override
        public void setHeader(String name, String value) {
            // no-op
        }

        @Override
        public void addHeader(String name, String value) {
            // no-op
        }

        @Override
        public void setDateHeader(String name, long date) {
            // no-op
        }

        @Override
        public void addDateHeader(String name, long date) {
            // no-op
        }

        @Override
        public void setIntHeader(String name, int value) {
            // no-op
        }

        @Override
        public void addIntHeader(String name, int value) {
            // no-op
        }

        @Override
        public void sendRedirect(String location) {
            // no-op
        }

        @Override
        public void sendRedirect(String location, int sc, boolean clearBuffer) {
            // no-op
        }

        @Override
        public void sendError(int sc) {
            // no-op
        }

        @Override
        public void sendError(int sc, String msg) {
            // no-op
        }

        @Override
        public void setContentType(String type) {
            // no-op
        }

        @Override
        public void setContentLength(int len) {
            // no-op
        }

        @Override
        public void setContentLengthLong(long len) {
            // no-op
        }

        @Override
        public void setCharacterEncoding(String charset) {
            // no-op
        }

        @Override
        public void setLocale(Locale loc) {
            // no-op
        }

        @Override
        public void reset() {
            // no-op
        }

        @Override
        public void resetBuffer() {
            // no-op
        }
    }
}
