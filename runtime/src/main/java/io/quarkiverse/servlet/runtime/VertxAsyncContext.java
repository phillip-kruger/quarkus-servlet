package io.quarkiverse.servlet.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
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

public class VertxAsyncContext implements AsyncContext {

    private static final Logger log = Logger.getLogger(VertxAsyncContext.class);

    private final ServletRequest request;
    private final ServletResponse response;
    private final boolean originalRequestAndResponse;
    private final List<AsyncListener> listeners = new ArrayList<>();
    private long timeout = 30000;
    private final AtomicBoolean completed = new AtomicBoolean(false);
    private final AtomicBoolean dispatched = new AtomicBoolean(false);
    private final CountDownLatch completionLatch = new CountDownLatch(1);
    private Long timerId;
    private final Vertx vertx;
    private final ServletDeployment deployment;

    public VertxAsyncContext(ServletRequest request, ServletResponse response,
            boolean originalRequestAndResponse, Vertx vertx, ServletDeployment deployment) {
        this.request = request;
        this.response = response;
        this.originalRequestAndResponse = originalRequestAndResponse;
        this.vertx = vertx;
        this.deployment = deployment;
    }

    public void startTimeout() {
        if (timeout > 0 && vertx != null) {
            timerId = vertx.setTimer(timeout, id -> {
                if (!completed.get() && !dispatched.get()) {
                    vertx.executeBlocking(() -> {
                        onTimeout();
                        return null;
                    });
                }
            });
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
        HttpServletRequest httpReq = (HttpServletRequest) request;
        String path = httpReq.getRequestURI();
        String contextPath = httpReq.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && !"/".equals(contextPath)
                && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        dispatch(httpReq.getServletContext(), path);
    }

    @Override
    public void dispatch(String path) {
        dispatch(request.getServletContext(), path);
    }

    @Override
    public void dispatch(ServletContext context, String path) {
        if (dispatched.getAndSet(true)) {
            throw new IllegalStateException("Async dispatch already in progress");
        }
        cancelTimeout();
        dispatchPath = path;
    }

    private volatile String dispatchPath;

    public boolean isDispatched() {
        return dispatched.get();
    }

    public boolean isCompleted() {
        return completed.get();
    }

    public void executeDispatch() {
        if (dispatchPath != null) {
            doDispatch(dispatchPath);
        }
    }

    private void doDispatch(String path) {
        try {
            if (deployment == null) {
                complete();
                return;
            }

            String dispatchPath = path;
            String dispatchQueryString = null;
            int queryIdx = dispatchPath.indexOf('?');
            if (queryIdx >= 0) {
                dispatchQueryString = dispatchPath.substring(queryIdx + 1);
                dispatchPath = dispatchPath.substring(0, queryIdx);
            }
            int semiIdx = dispatchPath.indexOf(';');
            if (semiIdx >= 0) {
                dispatchPath = dispatchPath.substring(0, semiIdx);
            }

            UrlPatternMatcher.MatchResult match = deployment.matchServlet(dispatchPath);
            if (match == null) {
                complete();
                return;
            }

            deployment.ensureServletInitialized(match.getServletInfo());

            if (request instanceof VertxServletRequest vsr) {
                vsr.setDispatcherType(DispatcherType.ASYNC);
                vsr.resetAsyncState();
                vsr.setAsyncSupported(match.getServletInfo().isAsyncSupported());
                if (dispatchQueryString != null) {
                    vsr.setDispatchQueryString(dispatchQueryString);
                }

                request.setAttribute(ASYNC_REQUEST_URI,
                        vsr.getContextPath() + dispatchPath);
                request.setAttribute(ASYNC_CONTEXT_PATH, vsr.getContextPath());
                request.setAttribute(ASYNC_SERVLET_PATH, match.getServletPath());
                request.setAttribute(ASYNC_PATH_INFO, match.getPathInfo());
                request.setAttribute(ASYNC_QUERY_STRING, dispatchQueryString);
            }

            FilterInfo[] filters = deployment.getMatchingFilters(
                    dispatchPath, match.getServletInfo().getName(),
                    DispatcherType.ASYNC);
            VertxFilterChain chain = new VertxFilterChain(filters, match.getServletInfo());
            chain.doFilter(request, response);

            if (request instanceof VertxServletRequest vsr && vsr.isAsyncStarted()) {
                VertxAsyncContext nextAc = (VertxAsyncContext) vsr.getAsyncContext();
                if (nextAc.isDispatched()) {
                    nextAc.executeDispatch();
                } else if (!nextAc.isCompleted()) {
                    try {
                        nextAc.awaitCompletion();
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
                closeResponse();
                completionLatch.countDown();
                return;
            }

            complete();
        } catch (Exception e) {
            log.error("Error during async dispatch", e);
            onError(e);
            if (!completed.get()) {
                complete();
            }
        }
    }

    @Override
    public void complete() {
        if (completed.getAndSet(true)) {
            return;
        }
        cancelTimeout();

        AsyncEvent event = new AsyncEvent(this, request, response);
        for (AsyncListener listener : listeners) {
            try {
                listener.onComplete(event);
            } catch (Exception e) {
                log.warn("Error in AsyncListener.onComplete", e);
            }
        }

        // Fire request destroyed listeners (skipped by executeServletInternal for async)
        if (deployment != null && request instanceof VertxServletRequest) {
            var ctxListeners = deployment.getServletContext().getListeners();
            for (int i = ctxListeners.size() - 1; i >= 0; i--) {
                if (ctxListeners.get(i) instanceof jakarta.servlet.ServletRequestListener srl) {
                    srl.requestDestroyed(
                            new jakarta.servlet.ServletRequestEvent(deployment.getServletContext(), request));
                }
            }
        }

        // Response close is handled by executeServletInternal or doDispatch
        completionLatch.countDown();
    }

    public void awaitCompletion() throws InterruptedException {
        completionLatch.await();
    }

    @Override
    public void start(Runnable run) {
        if (vertx != null) {
            vertx.executeBlocking(() -> {
                run.run();
                return null;
            });
        } else {
            Thread.startVirtualThread(run);
        }
    }

    @Override
    public void addListener(AsyncListener listener) {
        listeners.add(listener);
    }

    @Override
    public void addListener(AsyncListener listener, ServletRequest req, ServletResponse resp) {
        listeners.add(listener);
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
    }

    @Override
    public long getTimeout() {
        return timeout;
    }

    private void onTimeout() {
        AsyncEvent event = new AsyncEvent(this, request, response);
        for (AsyncListener listener : listeners) {
            try {
                listener.onTimeout(event);
            } catch (Exception e) {
                log.warn("Error in AsyncListener.onTimeout", e);
            }
        }
        if (!completed.get() && !dispatched.get()) {
            if (response instanceof VertxServletResponse vsr) {
                try {
                    if (!vsr.isCommitted()) {
                        vsr.sendError(500, "Async operation timed out");
                    }
                } catch (Exception e) {
                    log.warn("Error sending timeout error", e);
                }
            }
            complete();
        }
    }

    private void onError(Throwable t) {
        AsyncEvent event = new AsyncEvent(this, request, response, t);
        for (AsyncListener listener : listeners) {
            try {
                listener.onError(event);
            } catch (Exception e) {
                log.warn("Error in AsyncListener.onError", e);
            }
        }
    }

    private void closeResponse() {
        if (response instanceof VertxServletResponse vsr) {
            try {
                vsr.close();
            } catch (Exception e) {
                log.warn("Error closing response", e);
            }
        }
    }

    private void cancelTimeout() {
        if (timerId != null && vertx != null) {
            vertx.cancelTimer(timerId);
            timerId = null;
        }
    }
}
