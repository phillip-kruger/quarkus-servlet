package io.quarkiverse.servlet.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import io.quarkus.runtime.LaunchMode;

public class DefaultServlet extends HttpServlet {

    private static final int MAX_CACHE_ENTRIES = 1000;

    private final ConcurrentHashMap<String, CachedResource> cache = new ConcurrentHashMap<>();
    private final boolean cachingEnabled = LaunchMode.current() != LaunchMode.DEVELOPMENT;

    record CachedResource(byte[] content, String mimeType) {
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        String path;
        String includeServletPath = (String) req.getAttribute(
                jakarta.servlet.RequestDispatcher.INCLUDE_SERVLET_PATH);
        if (includeServletPath != null) {
            String includePathInfo = (String) req.getAttribute(
                    jakarta.servlet.RequestDispatcher.INCLUDE_PATH_INFO);
            path = includeServletPath + (includePathInfo != null ? includePathInfo : "");
        } else {
            path = req.getServletPath();
            if (req.getPathInfo() != null) {
                path = path + req.getPathInfo();
            }
        }

        if (cachingEnabled) {
            CachedResource cached = cache.get(path);
            if (cached != null) {
                serveContent(resp, cached.content, cached.mimeType);
                return;
            }
        }

        InputStream is = req.getServletContext().getResourceAsStream(path);
        if (is == null) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        byte[] content = is.readAllBytes();
        is.close();
        String mimeType = req.getServletContext().getMimeType(path);

        if (cachingEnabled && cache.size() < MAX_CACHE_ENTRIES) {
            cache.put(path, new CachedResource(content, mimeType));
        }

        serveContent(resp, content, mimeType);
    }

    private void serveContent(HttpServletResponse resp, byte[] content, String mimeType)
            throws IOException {
        if (mimeType != null) {
            resp.setContentType(mimeType);
        }
        try {
            resp.getOutputStream().write(content);
            resp.getOutputStream().flush();
        } catch (IllegalStateException e) {
            try {
                resp.getWriter().write(new String(content));
                resp.getWriter().flush();
            } catch (Exception e2) {
                // ignore
            }
        }
    }
}
