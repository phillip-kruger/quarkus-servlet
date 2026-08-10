package io.quarkiverse.servlet.runtime;

import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.Servlet;
import jakarta.servlet.http.HttpSession;

import org.jboss.logging.Logger;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.ShutdownContext;
import io.quarkus.runtime.annotations.Recorder;
import io.quarkus.security.identity.CurrentIdentityAssociation;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.quarkus.vertx.http.runtime.security.QuarkusHttpUser;
import io.quarkus.virtual.threads.VirtualThreadsRecorder;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;

@Recorder
public class ServletRecorder {

    private static final Logger log = Logger.getLogger(ServletRecorder.class);
    private static volatile ServletDeployment currentDeployment;

    public static ServletDeployment getCurrentDeployment() {
        return currentDeployment;
    }

    public RuntimeValue<ServletDeployment> createDeployment(String contextPath,
            Map<String, String> initParams) {
        VertxServletContext servletContext = new VertxServletContext(contextPath, initParams);
        return new RuntimeValue<>(new ServletDeployment(servletContext));
    }

    public void registerServlet(RuntimeValue<ServletDeployment> deployment,
            String name, String className, List<String> mappings,
            Map<String, String> initParams, int loadOnStartup,
            boolean asyncSupported, boolean runOnVirtualThread) {
        ServletInfo info = new ServletInfo(name, className, mappings, initParams,
                loadOnStartup, asyncSupported, runOnVirtualThread);
        deployment.getValue().addServlet(info);
        log.debugf("Registered servlet metadata: %s -> %s (virtualThread=%s)",
                name, mappings, runOnVirtualThread);
    }

    public void registerFilter(RuntimeValue<ServletDeployment> deployment,
            String name, String className, List<String> urlPatterns,
            List<String> servletNames,
            java.util.Set<jakarta.servlet.DispatcherType> dispatcherTypes,
            Map<String, String> initParams, int priority) {
        FilterInfo info = new FilterInfo(name, className, urlPatterns, servletNames,
                dispatcherTypes, initParams, priority);
        deployment.getValue().addFilter(info);
        log.debugf("Registered filter metadata: %s -> %s (servletNames=%s)", name, urlPatterns, servletNames);
    }

    public void registerSecurityConstraints(RuntimeValue<ServletDeployment> deployment,
            java.util.List<ServletSecurityConstraint> constraints) {
        deployment.getValue().setSecurityConstraints(constraints);
    }

    public void setupSecurityPolicy(RuntimeValue<ServletDeployment> deployment) {
        ServletDeployment dep = deployment.getValue();
        if (!dep.getSecurityConstraints().isEmpty()) {
            ServletSecurityPolicy policy = Arc.container()
                    .instance(ServletSecurityPolicy.class).get();
            if (policy != null) {
                policy.setConstraints(dep.getSecurityConstraints());
                policy.setContextPath(dep.getServletContext().getContextPath());
            }
        }
    }

    public void registerListener(RuntimeValue<ServletDeployment> deployment, String className) {
        try {
            Class<?> clazz = Thread.currentThread().getContextClassLoader().loadClass(className);
            Object instance = Arc.container().instance(clazz).get();
            if (instance == null) {
                instance = clazz.getDeclaredConstructor().newInstance();
            }
            deployment.getValue().getServletContext()
                    .addRegisteredListener((java.util.EventListener) instance);
            log.debugf("Registered listener: %s", className);
        } catch (Exception e) {
            throw new RuntimeException("Failed to register listener: " + className, e);
        }
    }

    public Handler<RoutingContext> boot(RuntimeValue<ServletDeployment> deploymentValue,
            ShutdownContext shutdown) {
        ServletDeployment deployment = deploymentValue.getValue();

        ServletRuntimeConfig config = org.eclipse.microprofile.config.ConfigProvider.getConfig()
                .unwrap(io.smallrye.config.SmallRyeConfig.class)
                .getConfigMapping(ServletRuntimeConfig.class);

        deployment.setMaxParameters(config.maxParameters());
        deployment.setDefaultVirtualThreads(config.virtualThreads());

        instantiateServlets(deployment);
        instantiateFilters(deployment);
        deployment.initServlets();

        deployment.initCdiCache(
                Arc.container().requestContext(),
                Arc.container().instance(CurrentVertxRequest.class).get(),
                Arc.container().instance(CurrentIdentityAssociation.class).get());

        currentDeployment = deployment;
        shutdown.addShutdownTask(() -> {
            deployment.destroy();
            currentDeployment = null;
        });

        Set<String> disallowed = config.disallowedMethods().orElse(null);
        return new ServletHandler(deployment, disallowed);
    }

    private void instantiateServlets(ServletDeployment deployment) {
        for (ServletInfo info : deployment.getServlets().values()) {
            try {
                Class<?> clazz = Thread.currentThread().getContextClassLoader()
                        .loadClass(info.getClassName());
                Object instance = Arc.container().instance(clazz).get();
                if (instance == null) {
                    instance = clazz.getDeclaredConstructor().newInstance();
                }
                info.setServlet((Servlet) instance);
                log.debugf("Instantiated servlet: %s (%s)", info.getName(), info.getClassName());
            } catch (Exception e) {
                throw new RuntimeException("Failed to instantiate servlet: " + info.getClassName(), e);
            }
        }
    }

    private void instantiateFilters(ServletDeployment deployment) {
        for (FilterInfo info : deployment.getFilters()) {
            try {
                Class<?> clazz = Thread.currentThread().getContextClassLoader()
                        .loadClass(info.getClassName());
                Object instance = Arc.container().instance(clazz).get();
                if (instance == null) {
                    instance = clazz.getDeclaredConstructor().newInstance();
                }
                info.setFilter((Filter) instance);

                SimpleFilterConfig config = new SimpleFilterConfig(
                        info.getName(), deployment.getServletContext(), info.getInitParams());
                info.getFilter().init(config);

                log.debugf("Instantiated and initialized filter: %s (%s)",
                        info.getName(), info.getClassName());
            } catch (Exception e) {
                throw new RuntimeException("Failed to instantiate filter: " + info.getClassName(), e);
            }
        }
    }

    private static class ServletHandler implements Handler<RoutingContext> {

        private final ServletDeployment deployment;
        private final Set<String> disallowedMethods;

        ServletHandler(ServletDeployment deployment, Set<String> disallowedMethods) {
            this.deployment = deployment;
            this.disallowedMethods = disallowedMethods;
        }

        @Override
        public void handle(RoutingContext rc) {
            if (disallowedMethods != null
                    && disallowedMethods.contains(rc.request().method().name())) {
                rc.response().setStatusCode(405);
                rc.response().end("Method Not Allowed");
                return;
            }
            io.vertx.core.buffer.Buffer body = rc.body() != null ? rc.body().buffer() : null;

            boolean useVirtualThread = needsVirtualThread(rc);
            String method = rc.request().method().name();
            boolean hasBody = "POST".equals(method) || "PUT".equals(method)
                    || "PATCH".equals(method);

            if (body == null && hasBody) {
                rc.request().resume();
                rc.request().body().onComplete(ar -> {
                    io.vertx.core.buffer.Buffer readBody = ar.succeeded() ? ar.result() : null;
                    if (useVirtualThread) {
                        VirtualThreadsRecorder.getCurrent().execute(() -> {
                            executeServlet(rc, deployment, readBody);
                        });
                    } else {
                        executeServlet(rc, deployment, readBody);
                    }
                });
            } else if (useVirtualThread) {
                VirtualThreadsRecorder.getCurrent().execute(() -> {
                    executeServlet(rc, deployment, body);
                });
            } else {
                executeServlet(rc, deployment, body);
            }
        }

        private boolean needsVirtualThread(RoutingContext rc) {
            String path = rc.normalizedPath();
            int semiIdx = path.indexOf(';');
            if (semiIdx >= 0) {
                path = path.substring(0, semiIdx);
            }
            String contextPath = deployment.getServletContext().getContextPath();
            String relativePath = path;
            if (!"/".equals(contextPath) && path.startsWith(contextPath)) {
                relativePath = path.substring(contextPath.length());
                if (relativePath.isEmpty()) {
                    relativePath = "/";
                }
            }
            if (deployment.isDefaultVirtualThreads()) {
                return true;
            }
            UrlPatternMatcher.MatchResult match = deployment.matchServlet(relativePath);
            return match != null && match.getServletInfo().isRunOnVirtualThread();
        }
    }

    public static void executeServletDirect(RoutingContext rc, ServletDeployment deployment,
            io.vertx.core.buffer.Buffer requestBody) {
        executeServletInternal(rc, deployment, requestBody, false);
    }

    private static void executeServlet(RoutingContext rc, ServletDeployment deployment,
            io.vertx.core.buffer.Buffer requestBody) {
        executeServletInternal(rc, deployment, requestBody, true);
    }

    private static void executeServletInternal(RoutingContext rc, ServletDeployment deployment,
            io.vertx.core.buffer.Buffer requestBody, boolean useCdi) {
        ManagedContext requestContext = null;
        VertxServletRequest req = null;
        String currentServletName = null;
        try {
            if (useCdi) {
                requestContext = deployment.getCachedRequestContext();
                requestContext.activate();

                CurrentVertxRequest currentVertxRequest = deployment.getCachedCurrentVertxRequest();
                if (currentVertxRequest != null) {
                    currentVertxRequest.setCurrent(rc);
                }

                CurrentIdentityAssociation identityAssociation = deployment.getCachedIdentityAssociation();
                if (identityAssociation != null) {
                    io.quarkus.vertx.http.runtime.security.QuarkusHttpUser user = (io.quarkus.vertx.http.runtime.security.QuarkusHttpUser) rc
                            .user();
                    if (user != null) {
                        identityAssociation.setIdentity(user.getSecurityIdentity());
                    }
                }
            }

            String path = rc.normalizedPath();
            int semiIdx = path.indexOf(';');
            if (semiIdx >= 0) {
                path = path.substring(0, semiIdx);
            }
            String contextPath = deployment.getServletContext().getContextPath();

            String relativePath = path;
            if (!"/".equals(contextPath) && path.startsWith(contextPath)) {
                relativePath = path.substring(contextPath.length());
                if (relativePath.isEmpty()) {
                    relativePath = "/";
                }
            }

            UrlPatternMatcher.MatchResult match = deployment.matchServlet(relativePath);

            // Welcome file resolution (only for directory-like paths)
            boolean isDirectoryLike = relativePath.endsWith("/") || relativePath.isEmpty()
                    || (!relativePath.contains(".") && !(match != null
                            && !"default".equals(match.getServletInfo().getName())));
            if (isDirectoryLike && (match == null || "default".equals(match.getServletInfo().getName()))
                    && !deployment.getWelcomeFiles().isEmpty()) {
                String dir = relativePath.endsWith("/") ? relativePath
                        : relativePath.isEmpty() ? "/" : relativePath + "/";
                for (String wf : deployment.getWelcomeFiles()) {
                    String welcomePath = dir + wf;
                    UrlPatternMatcher.MatchResult wfMatch = deployment.matchServlet(welcomePath);
                    if (wfMatch != null && !"default".equals(wfMatch.getServletInfo().getName())) {
                        match = wfMatch;
                        relativePath = welcomePath;
                        break;
                    }
                    try {
                        if (deployment.getServletContext().getResource(welcomePath) != null) {
                            match = deployment.matchServlet(welcomePath);
                            relativePath = welcomePath;
                            break;
                        }
                    } catch (Exception e) {
                        // ignore
                    }
                }
            }

            if (match == null) {
                if (LaunchMode.current() == LaunchMode.DEVELOPMENT) {
                    rc.next();
                } else {
                    if (!rc.response().headWritten()) {
                        rc.response().setStatusCode(404);
                    }
                    if (!rc.response().ended()) {
                        rc.response().end("Not Found");
                    }
                }
                return;
            }

            deployment.ensureServletInitialized(match.getServletInfo());

            if (match.getServletInfo().isInitFailed()) {
                int statusCode = match.getServletInfo().isPermanentlyUnavailable() ? 404 : 500;
                if (!rc.response().headWritten()) {
                    rc.response().setStatusCode(statusCode);
                }
                if (!rc.response().ended()) {
                    String body;
                    if (statusCode == 404) {
                        body = "Servlet unavailable";
                    } else {
                        Exception initEx = match.getServletInfo().getInitException();
                        body = "Status Code: 500\n" +
                                "Exception: " + (initEx != null ? initEx.toString() : "Unknown") + "\n";
                    }
                    rc.response().end(body);
                }
                return;
            }

            req = new VertxServletRequest(
                    rc, deployment.getServletContext(),
                    match.getServletPath(), match.getPathInfo(),
                    deployment.getServletContext().getContextPath(),
                    requestBody);
            req.setSessionStore(deployment.getSessionStore());
            req.setVertx(rc.vertx());
            req.setDeployment(deployment);
            req.setAsyncSupported(match.getServletInfo().isAsyncSupported());
            req.setMapping(match.getMappingMatch(), match.getMatchedPattern(),
                    match.getServletInfo().getName());
            currentServletName = match.getServletInfo().getName();

            VertxServletResponse resp = new VertxServletResponse(rc.response(), req);

            ServletRequestContext.set(req, resp, rc);

            // Notify request listeners
            for (java.util.EventListener l : deployment.getServletContext().getListeners()) {
                if (l instanceof jakarta.servlet.ServletRequestListener srl) {
                    srl.requestInitialized(new jakarta.servlet.ServletRequestEvent(deployment.getServletContext(), req));
                }
            }

            FilterInfo[] filters = deployment.getMatchingFilters(
                    relativePath, match.getServletInfo().getName(),
                    DispatcherType.REQUEST);
            VertxFilterChain chain = new VertxFilterChain(filters, match.getServletInfo());
            try {
                chain.doFilter(req, resp);
            } catch (jakarta.servlet.UnavailableException ue) {
                match.getServletInfo().setInitFailed(true);
                match.getServletInfo().setPermanentlyUnavailable(true);
                if (!rc.response().headWritten()) {
                    rc.response().setStatusCode(404);
                }
                if (!rc.response().ended()) {
                    rc.response().end("Servlet unavailable");
                }
                return;
            }

            if (req.isAsyncStarted()) {
                VertxAsyncContext ac = (VertxAsyncContext) req.getAsyncContext();
                if (ac.isDispatched()) {
                    ac.executeDispatch();
                } else if (!ac.isCompleted()) {
                    try {
                        ac.awaitCompletion();
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
                resp.close();
                return;
            }

            if (resp.isErrorSent()) {
                handleErrorPage(rc, deployment, req, resp, requestBody,
                        match.getServletInfo().getName());
            } else {
                resp.close();
            }

        } catch (Exception e) {
            log.error("Error processing servlet request", e);
            String exceptionErrorPage = deployment.getExceptionErrorPage(e);
            if (exceptionErrorPage != null && req != null && !rc.response().headWritten()) {
                try {
                    UrlPatternMatcher.MatchResult errorMatch = deployment.matchServlet(exceptionErrorPage);
                    if (errorMatch != null) {
                        deployment.ensureServletInitialized(errorMatch.getServletInfo());
                        Throwable actual = deployment.unwrapException(e);
                        rc.response().setStatusCode(500);
                        req.setAttribute("jakarta.servlet.error.status_code", 500);
                        req.setAttribute("jakarta.servlet.error.exception", actual);
                        req.setAttribute("jakarta.servlet.error.exception_type", actual.getClass());
                        req.setAttribute("jakarta.servlet.error.message",
                                actual.getMessage() != null ? actual.getMessage() : "");
                        req.setAttribute("jakarta.servlet.error.request_uri", req.getRequestURI());
                        req.setAttribute("jakarta.servlet.error.servlet_name",
                                currentServletName != null ? currentServletName : "unknown");
                        req.setDispatcherType(DispatcherType.ERROR);
                        req.setForwardedPath(
                                req.getContextPath() + exceptionErrorPage,
                                errorMatch.getServletPath(),
                                errorMatch.getPathInfo(),
                                null);
                        VertxServletResponse errorResp = new VertxServletResponse(rc.response(), req);
                        errorResp.setStatus(500);
                        FilterInfo[] errorFilters = deployment.getMatchingFilters(
                                exceptionErrorPage,
                                errorMatch.getServletInfo().getName(),
                                DispatcherType.ERROR);
                        VertxFilterChain errorChain = new VertxFilterChain(
                                errorFilters, errorMatch.getServletInfo());
                        errorChain.doFilter(req, errorResp);
                        errorResp.close();
                        return;
                    }
                } catch (Exception ex) {
                    log.error("Error dispatching to exception error page", ex);
                }
            }
            if (LaunchMode.current() == LaunchMode.DEVELOPMENT) {
                rc.fail(e);
            } else {
                if (!rc.response().headWritten()) {
                    rc.response().setStatusCode(500);
                }
                if (!rc.response().ended()) {
                    rc.response().end("Internal Server Error");
                }
            }
        } finally {
            boolean asyncActive = req != null && req.isAsyncStarted();
            if (!asyncActive) {
                // Update session last-accessed time at end of request
                if (req != null) {
                    HttpSession session = req.getSession(false);
                    if (session != null) {
                        deployment.getSessionStore().endAccess(session);
                    }
                }
                // Notify request listeners (in reverse order)
                if (req != null) {
                    var listeners = deployment.getServletContext().getListeners();
                    for (int i = listeners.size() - 1; i >= 0; i--) {
                        if (listeners.get(i) instanceof jakarta.servlet.ServletRequestListener srl) {
                            srl.requestDestroyed(
                                    new jakarta.servlet.ServletRequestEvent(deployment.getServletContext(), req));
                        }
                    }
                }
                ServletRequestContext.clear();
                if (requestContext != null) {
                    requestContext.terminate();
                }
            }
        }
    }

    private static void handleErrorPage(RoutingContext rc, ServletDeployment deployment,
            VertxServletRequest req, VertxServletResponse resp,
            io.vertx.core.buffer.Buffer requestBody, String servletName) {
        int errorCode = resp.getErrorCode();
        String errorPage = deployment.getErrorPage(errorCode);

        if (errorPage != null) {
            UrlPatternMatcher.MatchResult errorMatch = deployment.matchServlet(errorPage);
            if (errorMatch != null) {
                try {
                    deployment.ensureServletInitialized(errorMatch.getServletInfo());

                    req.setAttribute("jakarta.servlet.error.status_code", errorCode);
                    req.setAttribute("jakarta.servlet.error.message",
                            resp.getErrorMessage() != null ? resp.getErrorMessage() : "");
                    req.setAttribute("jakarta.servlet.error.request_uri", req.getRequestURI());
                    req.setAttribute("jakarta.servlet.error.servlet_name", servletName);

                    req.setDispatcherType(DispatcherType.ERROR);
                    req.setForwardedPath(
                            req.getContextPath() + errorPage,
                            errorMatch.getServletPath(),
                            errorMatch.getPathInfo(),
                            null);

                    VertxServletResponse errorResp = new VertxServletResponse(rc.response(), req);
                    errorResp.setStatus(errorCode);

                    ServletRequestContext.set(req, errorResp, rc);

                    FilterInfo[] errorFilters = deployment.getMatchingFilters(
                            errorPage, errorMatch.getServletInfo().getName(),
                            DispatcherType.ERROR);
                    VertxFilterChain errorChain = new VertxFilterChain(
                            errorFilters, errorMatch.getServletInfo());
                    errorChain.doFilter(req, errorResp);
                    errorResp.close();
                    return;
                } catch (Exception e) {
                    log.error("Error dispatching to error page: " + errorPage, e);
                }
            }
        }

        try {
            resp.writeDefaultErrorPage();
        } catch (Exception e) {
            log.error("Error writing default error page", e);
        }
    }
}
