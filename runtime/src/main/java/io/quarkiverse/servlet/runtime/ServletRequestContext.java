package io.quarkiverse.servlet.runtime;

import io.vertx.ext.web.RoutingContext;

public final class ServletRequestContext {

    private static final ThreadLocal<ServletRequestContext> CURRENT = new ThreadLocal<>();

    private final VertxServletRequest request;
    private final VertxServletResponse response;
    private final RoutingContext routingContext;

    private ServletRequestContext(VertxServletRequest request, VertxServletResponse response,
            RoutingContext routingContext) {
        this.request = request;
        this.response = response;
        this.routingContext = routingContext;
    }

    public static void set(VertxServletRequest request, VertxServletResponse response,
            RoutingContext routingContext) {
        CURRENT.set(new ServletRequestContext(request, response, routingContext));
    }

    public static ServletRequestContext get() {
        ServletRequestContext ctx = CURRENT.get();
        if (ctx == null) {
            throw new IllegalStateException("No servlet request context available on this thread");
        }
        return ctx;
    }

    public static void clear() {
        CURRENT.remove();
    }

    public VertxServletRequest getRequest() {
        return request;
    }

    public VertxServletResponse getResponse() {
        return response;
    }

    public RoutingContext getRoutingContext() {
        return routingContext;
    }
}
