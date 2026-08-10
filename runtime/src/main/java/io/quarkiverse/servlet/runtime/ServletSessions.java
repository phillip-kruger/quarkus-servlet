package io.quarkiverse.servlet.runtime;

import jakarta.servlet.http.HttpSession;

import io.vertx.core.http.Cookie;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;

/**
 * Session lookup for code that has a Vert.x exchange but no {@link jakarta.servlet.ServletRequest}.
 * <p>
 * Authentication runs before the servlet layer does, yet FORM login has to keep the request it
 * interrupted somewhere the client will carry back - which the servlet spec makes the session,
 * because the session cookie is the only thing a browser is guaranteed to return. Sharing this with
 * {@link VertxServletRequest} keeps one notion of "the session for this exchange"; two would drift.
 */
public final class ServletSessions {

    private ServletSessions() {
    }

    /** The configured session cookie name, {@code JSESSIONID} unless the application changed it. */
    public static String cookieName(VertxServletContext servletContext) {
        return servletContext.sessionCookieConfig().getName();
    }

    /**
     * Builds the session cookie. HttpOnly keeps it out of reach of scripts, and Secure is set
     * whenever the request arrived over TLS so the id is never echoed back in clear text.
     */
    public static Cookie newSessionCookie(VertxServletContext servletContext, String id,
            String contextPath, boolean secureRequest) {
        VertxServletContext.SimpleSessionCookieConfig config = servletContext.sessionCookieConfig();

        Cookie cookie = Cookie.cookie(config.getName(), id);
        cookie.setPath(config.getPath() != null ? config.getPath()
                : (contextPath == null || contextPath.isEmpty() ? "/" : contextPath));
        if (config.getDomain() != null) {
            cookie.setDomain(config.getDomain());
        }
        if (config.getMaxAge() >= 0) {
            cookie.setMaxAge(config.getMaxAge());
        }
        cookie.setHttpOnly(config.isHttpOnly());
        cookie.setSecure(config.isSecure() || secureRequest);
        return cookie;
    }

    /** Reads the session id the client presented, or {@code null} if it presented none. */
    public static String requestedSessionId(VertxServletContext servletContext,
            HttpServerRequest request) {
        String cookieHeader = request.headers().get("Cookie");
        if (cookieHeader == null) {
            return null;
        }
        String prefix = cookieName(servletContext) + "=";
        for (String pair : cookieHeader.split(";")) {
            pair = pair.trim();
            if (pair.startsWith(prefix)) {
                return pair.substring(prefix.length()).trim();
            }
        }
        return null;
    }

    /**
     * The session for this exchange, creating it - and emitting its cookie - when asked. Returns
     * {@code null} if there is no session and {@code create} is false.
     */
    public static HttpSession sessionFor(ServletDeployment deployment, RoutingContext exchange,
            boolean create) {
        VertxServletContext servletContext = deployment.getServletContext();
        VertxSessionStore store = deployment.getSessionStore();
        String id = requestedSessionId(servletContext, exchange.request());
        if (id != null) {
            HttpSession existing = store.getSession(id);
            if (existing != null) {
                return existing;
            }
        }
        if (!create) {
            return null;
        }
        HttpSession session = store.createSession(servletContext);
        exchange.response().addCookie(newSessionCookie(servletContext, session.getId(),
                servletContext.getContextPath(), exchange.request().isSSL()));
        return session;
    }
}
