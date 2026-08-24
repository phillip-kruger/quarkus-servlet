package io.quarkiverse.servlet.runtime;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;

import org.jboss.logging.Logger;

import io.vertx.core.Vertx;

/**
 * {@link AsyncContext} implementation.
 * <p>
 * Nothing here ever blocks the calling thread. When a servlet starts async processing its
 * {@code service()} method returns normally, the container leaves the HTTP response open, and the
 * exchange is finished later by {@link #complete()} or by the servlet invoked through
 * {@link #dispatch()}. That is what makes async usable while servlets run on the event loop.
 */
public class VertxAsyncContext implements AsyncContext {

    private static final Logger log = Logger.getLogger(VertxAsyncContext.class);

    /**
     * Hooks back into the container, supplied by the code that is driving the request.
     */
    public interface Callbacks {

        /**
         * Runs {@code action} on the thread this request's servlet executes on, with the CDI
         * request context and servlet request context active for its duration.
         */
        void execute(Runnable action);

        /**
         * Finishes the exchange: closes the response and releases request-scoped state. Must be
         * idempotent.
         */
        void finish();
    }

    private record Registration(AsyncListener listener, ServletRequest request, ServletResponse response) {

        AsyncEvent event(AsyncContext ctx, Throwable throwable) {
            return new AsyncEvent(ctx, request, response, throwable);
        }
    }

    private final ServletRequest request;
    private final ServletResponse response;
    /** The container's own request, kept because {@link #dispatch()} may have to fall back to it. */
    private final HttpServletRequest containerRequest;
    private final boolean originalRequestAndResponse;
    private final List<Registration> listeners = new CopyOnWriteArrayList<>();
    private final Vertx vertx;
    private final ServletDeployment deployment;

    private volatile long timeout = 30000;
    private volatile Long timerId;
    private volatile Callbacks callbacks;
    private volatile String dispatchPath;

    private final AtomicBoolean completed = new AtomicBoolean();
    private final AtomicBoolean dispatchRequested = new AtomicBoolean();
    private final AtomicBoolean dispatchStarted = new AtomicBoolean();
    private final AtomicBoolean serviceReturned = new AtomicBoolean();
    private final AtomicBoolean finished = new AtomicBoolean();

    public VertxAsyncContext(ServletRequest request, ServletResponse response,
            HttpServletRequest containerRequest, boolean originalRequestAndResponse,
            Vertx vertx, ServletDeployment deployment) {
        this.request = request;
        this.response = response;
        this.containerRequest = containerRequest;
        this.originalRequestAndResponse = originalRequestAndResponse;
        this.vertx = vertx;
        this.deployment = deployment;
    }

    public void setCallbacks(Callbacks callbacks) {
        this.callbacks = callbacks;
    }

    public void startTimeout() {
        cancelTimeout();
        long t = timeout;
        if (t > 0 && vertx != null) {
            timerId = vertx.setTimer(t, id -> onTimeoutFired());
        }
    }

    /**
     * Signals that the servlet's {@code service()} method has returned while async is still active.
     * A dispatch requested from within {@code service()} only runs once that has happened, as the
     * spec requires.
     */
    public void onServiceReturned() {
        serviceReturned.set(true);
        if (dispatchRequested.get() && !completed.get()) {
            scheduleDispatch();
        } else if (completed.get()) {
            // complete() was called from inside service(); the exchange is only really finished
            // now that control has come back to the container.
            finishNow();
        }
    }

    @Override
    public ServletRequest getRequest() {
        return request;
    }

    @Override
    public ServletResponse getResponse() {
        return response;
    }

    @Override
    public boolean hasOriginalRequestAndResponse() {
        return originalRequestAndResponse;
    }

    @Override
    public void dispatch() {
        // Servlet 6.1, 2.3.3.3: the target is the URI of the request the application passed to
        // startAsync, but only when that request is an HttpServletRequest. An application is free
        // to wrap with a plain ServletRequestWrapper, and then the dispatch goes to the URI of the
        // request as the container last dispatched it.
        HttpServletRequest httpReq = request instanceof HttpServletRequest httpRequest
                ? httpRequest
                : containerRequest;
        String path = httpReq.getRequestURI();
        String contextPath = httpReq.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && !"/".equals(contextPath)
                && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        String query = httpReq.getQueryString();
        dispatch(httpReq.getServletContext(), query != null ? path + "?" + query : path);
    }

    @Override
    public void dispatch(String path) {
        dispatch(request.getServletContext(), path);
    }

    @Override
    public void dispatch(ServletContext context, String path) {
        if (completed.get()) {
            throw new IllegalStateException("Async processing has already completed");
        }
        if (dispatchRequested.getAndSet(true)) {
            throw new IllegalStateException("Async dispatch already in progress");
        }
        cancelTimeout();
        this.dispatchPath = path;
        if (serviceReturned.get()) {
            scheduleDispatch();
        }
    }

    public boolean isDispatched() {
        return dispatchRequested.get();
    }

    public boolean isCompleted() {
        return completed.get();
    }

    private void scheduleDispatch() {
        if (dispatchStarted.getAndSet(true)) {
            return;
        }
        Callbacks cb = callbacks;
        if (cb == null) {
            log.error("Async dispatch requested before the container attached its callbacks");
            return;
        }
        cb.execute(this::runDispatch);
    }

    private void runDispatch() {
        String path = dispatchPath;
        try {
            if (deployment == null || path == null) {
                complete();
                return;
            }

            String targetPath = path;
            String queryString = null;
            int queryIdx = targetPath.indexOf('?');
            if (queryIdx >= 0) {
                queryString = targetPath.substring(queryIdx + 1);
                targetPath = targetPath.substring(0, queryIdx);
            }
            int semiIdx = targetPath.indexOf(';');
            if (semiIdx >= 0) {
                targetPath = targetPath.substring(0, semiIdx);
            }

            UrlPatternMatcher.MatchResult match = deployment.matchServlet(targetPath);
            if (match == null) {
                if (response instanceof VertxServletResponse vsr) {
                    vsr.sendError(404);
                }
                complete();
                return;
            }

            deployment.ensureServletInitialized(match.getServletInfo());

            // The application may have handed startAsync() a wrapper, but the container state
            // lives on the request underneath it.
            VertxServletRequest vsr = unwrap(request);
            if (vsr != null) {
                vsr.setDispatcherType(DispatcherType.ASYNC);
                vsr.resetAsyncState();
                if (queryString != null) {
                    vsr.setDispatchQueryString(queryString);
                }

                request.setAttribute(ASYNC_REQUEST_URI, vsr.getContextPath() + targetPath);
                request.setAttribute(ASYNC_CONTEXT_PATH, vsr.getContextPath());
                request.setAttribute(ASYNC_SERVLET_PATH, match.getServletPath());
                request.setAttribute(ASYNC_PATH_INFO, match.getPathInfo());
                request.setAttribute(ASYNC_QUERY_STRING, queryString);

                // The dispatched servlet sees the target's paths and mapping (getHttpServletMapping,
                // getServletPath, getPathInfo), mirroring a forward.
                vsr.setAsyncDispatchTarget(match.getServletPath(), match.getPathInfo(),
                        match.getMappingMatch(), match.getMatchedPattern(),
                        match.getServletInfo().getName());
            }

            FilterInfo[] filters = deployment.getMatchingFilters(
                    targetPath, match.getServletInfo().getName(), DispatcherType.ASYNC);
            if (vsr != null) {
                vsr.setAsyncSupported(
                        ServletDeployment.isAsyncSupported(match.getServletInfo(), filters));
            }

            // While the dispatched servlet runs, the request is back "inside" service() as far as
            // the spec is concerned, so a complete() from within it must not close the response
            // out from under it.
            serviceReturned.set(false);
            try {
                new VertxFilterChain(filters, match.getServletInfo()).doFilter(request, response);
            } finally {
                serviceReturned.set(true);
            }

            // The dispatched servlet may itself have started async processing again; if it did, the
            // new AsyncContext owns the exchange from here and we must not finish it.
            if (vsr != null && vsr.isAsyncStarted()) {
                VertxAsyncContext next = (VertxAsyncContext) vsr.getAsyncContext();
                next.onServiceReturned();
                return;
            }

            if (completed.get()) {
                finishNow();
            } else {
                complete();
            }
        } catch (Exception e) {
            log.error("Error during async dispatch", e);
            notifyError(e);
            if (!completed.get()) {
                if (response instanceof VertxServletResponse vsr && !vsr.isCommitted()) {
                    try {
                        vsr.sendError(500);
                    } catch (Exception ignored) {
                        // the response is already going away; nothing useful left to do
                    }
                }
                complete();
            }
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * When called from inside {@code service()} the response is deliberately left open until the
     * servlet returns: the spec keeps the request in async mode "until the dispatch returns to the
     * container", and applications legitimately write to the response after calling this.
     */
    @Override
    public void complete() {
        if (completed.getAndSet(true)) {
            return;
        }
        cancelTimeout();
        if (serviceReturned.get()) {
            finishNow();
        }
    }

    private void finishNow() {
        if (finished.getAndSet(true)) {
            return;
        }
        for (Registration reg : listeners) {
            try {
                reg.listener().onComplete(reg.event(this, null));
            } catch (Exception e) {
                log.warn("Error in AsyncListener.onComplete", e);
            }
        }

        Callbacks cb = callbacks;
        if (cb != null) {
            cb.finish();
        }
    }

    @Override
    public void start(Runnable run) {
        if (vertx != null) {
            vertx.executeBlocking(() -> {
                run.run();
                return null;
            }, false);
        } else {
            Thread.startVirtualThread(run);
        }
    }

    @Override
    public void addListener(AsyncListener listener) {
        listeners.add(new Registration(listener, request, response));
    }

    @Override
    public void addListener(AsyncListener listener, ServletRequest req, ServletResponse resp) {
        listeners.add(new Registration(listener, req, resp));
    }

    @Override
    public <T extends AsyncListener> T createListener(Class<T> clazz) throws ServletException {
        try {
            return clazz.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new ServletException("Cannot create listener: " + clazz, e);
        }
    }

    @Override
    public void setTimeout(long timeout) {
        this.timeout = timeout;
        if (!completed.get() && !dispatchRequested.get()) {
            startTimeout();
        }
    }

    @Override
    public long getTimeout() {
        return timeout;
    }

    /**
     * Fires the listeners for a re-started async cycle. The spec re-registers listeners on
     * {@code startAsync}, so this is invoked by the request when async starts again.
     */
    void notifyStartAsync(AsyncContext newContext) {
        for (Registration reg : listeners) {
            try {
                reg.listener().onStartAsync(reg.event(newContext, null));
            } catch (Exception e) {
                log.warn("Error in AsyncListener.onStartAsync", e);
            }
        }
    }

    private void onTimeoutFired() {
        timerId = null;
        if (completed.get() || dispatchRequested.get()) {
            return;
        }
        Callbacks cb = callbacks;
        if (cb != null) {
            cb.execute(this::runTimeout);
        } else {
            runTimeout();
        }
    }

    private void runTimeout() {
        if (completed.get() || dispatchRequested.get()) {
            return;
        }
        for (Registration reg : listeners) {
            try {
                reg.listener().onTimeout(reg.event(this, null));
            } catch (Exception e) {
                log.warn("Error in AsyncListener.onTimeout", e);
            }
        }
        // A listener may have dispatched in response to the timeout, in which case the dispatch
        // owns the exchange from here and must not be finished out from under it.
        if (dispatchRequested.get()) {
            return;
        }
        // If no listener completed the exchange, the container completes it itself with an error.
        if (!completed.get()) {
            if (response instanceof VertxServletResponse vsr) {
                try {
                    if (!vsr.isCommitted()) {
                        vsr.sendError(500, "Async operation timed out");
                    }
                } catch (Exception e) {
                    log.warn("Error sending timeout error", e);
                }
            }
            completed.set(true);
        }
        // A timeout is raised by the container, not the application, so the response is finished
        // straight away - even when a listener called complete(). Waiting for service() to return
        // would defeat the point of the timeout: the servlet may well still be blocked, which is
        // why it timed out. finishNow() is idempotent, so the later service() return is harmless.
        cancelTimeout();
        finishNow();
    }

    /**
     * Ends the async cycle because the client connection was closed before the application ever
     * completed it - the normal way a Server-Sent Events stream stops. The registered listeners get
     * {@code onError} followed by {@code onComplete}, and the request state is released, instead of
     * the {@link AsyncContext} staying registered and leaking until the deployment shuts down.
     * <p>
     * A cycle the application already completed or dispatched owns its own ending, so a disconnect
     * seen after that point is ignored.
     */
    public void onClientDisconnect() {
        if (completed.get() || dispatchRequested.get() || finished.get()) {
            return;
        }
        Callbacks cb = callbacks;
        if (cb != null) {
            cb.execute(this::runDisconnect);
        } else {
            runDisconnect();
        }
    }

    private void runDisconnect() {
        if (completed.get() || dispatchRequested.get() || finished.get()) {
            return;
        }
        notifyError(new IOException("Client disconnected before the async cycle completed"));
        // A listener may have dispatched in response to the error, in which case the dispatch owns
        // the exchange from here and must not be finished out from under it.
        if (dispatchRequested.get()) {
            return;
        }
        // The connection is gone, so there is no error response to write - just fire onComplete via
        // finishNow() and release the request state. finishNow() is idempotent.
        completed.set(true);
        cancelTimeout();
        finishNow();
    }

    private void notifyError(Throwable t) {
        for (Registration reg : listeners) {
            try {
                reg.listener().onError(reg.event(this, t));
            } catch (Exception e) {
                log.warn("Error in AsyncListener.onError", e);
            }
        }
    }

    /**
     * Peels off any {@link jakarta.servlet.ServletRequestWrapper}s the application layered on, to
     * reach the container's own request.
     */
    private static VertxServletRequest unwrap(ServletRequest request) {
        ServletRequest current = request;
        while (current instanceof jakarta.servlet.ServletRequestWrapper wrapper) {
            current = wrapper.getRequest();
        }
        return (current instanceof VertxServletRequest vsr) ? vsr : null;
    }

    private void cancelTimeout() {
        Long id = timerId;
        if (id != null && vertx != null) {
            vertx.cancelTimer(id);
            timerId = null;
        }
    }
}
