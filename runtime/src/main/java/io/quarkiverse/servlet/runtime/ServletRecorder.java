package io.quarkiverse.servlet.runtime;

import java.util.ArrayList;
import java.util.EventListener;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletContainerInitializer;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpSession;

import org.jboss.logging.Logger;

import io.quarkus.arc.Arc;
import io.quarkus.arc.InjectableContext;
import io.quarkus.arc.ManagedContext;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.ShutdownContext;
import io.quarkus.runtime.annotations.Recorder;
import io.quarkus.security.identity.CurrentIdentityAssociation;
import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.quarkus.virtual.threads.VirtualThreadsRecorder;
import io.vertx.core.Context;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.RoutingContext;

@Recorder
public class ServletRecorder {

    private static final Logger log = Logger.getLogger(ServletRecorder.class);

    /** Key under which the request's originating Vert.x context is stashed on the RoutingContext. */
    private static final String VERTX_CONTEXT_KEY = "io.quarkiverse.servlet.vertxContext";

    /** Name of the container's static-resource servlet. */
    private static final String DEFAULT_SERVLET_NAME = "default";

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
            boolean asyncSupported, ExecutionModel executionModel) {
        registerServlet(deployment, name, className, mappings, initParams, loadOnStartup,
                asyncSupported, executionModel, null);
    }

    public void registerServlet(RuntimeValue<ServletDeployment> deployment,
            String name, String className, List<String> mappings,
            Map<String, String> initParams, int loadOnStartup,
            boolean asyncSupported, ExecutionModel executionModel,
            MultipartConfiguration multipartConfig) {
        ServletInfo info = new ServletInfo(name, className, mappings, initParams,
                loadOnStartup, asyncSupported, executionModel, multipartConfig);
        deployment.getValue().addServlet(info);
        log.debugf("Registered servlet metadata: %s -> %s (executionModel=%s)",
                name, mappings, executionModel);
    }

    /**
     * Registers a {@code <security-role-ref>}: the alias a servlet uses in {@code isUserInRole}
     * mapped to the deployment role it stands for. A no-op if the servlet is not present.
     */
    public void addSecurityRoleRef(RuntimeValue<ServletDeployment> deployment,
            String servletName, String roleName, String roleLink) {
        ServletInfo info = deployment.getValue().getServlets().get(servletName);
        if (info != null) {
            info.addSecurityRoleRef(roleName, roleLink);
        }
    }

    public void registerFilter(RuntimeValue<ServletDeployment> deployment,
            String name, String className, List<String> urlPatterns,
            List<String> servletNames, Set<DispatcherType> dispatcherTypes,
            Map<String, String> initParams, int priority) {
        registerFilter(deployment, name, className, urlPatterns, servletNames, dispatcherTypes,
                initParams, priority, true);
    }

    public void registerFilter(RuntimeValue<ServletDeployment> deployment,
            String name, String className, List<String> urlPatterns,
            List<String> servletNames, Set<DispatcherType> dispatcherTypes,
            Map<String, String> initParams, int priority, boolean asyncSupported) {
        // A filter may declare several <filter-mapping> entries, each with its own dispatcher set and
        // target. They arrive as separate registrations but must share one FilterInfo so the filter
        // runs once per request and each mapping keeps its own dispatcher/target association.
        FilterInfo existing = deployment.getValue().getFilter(name);
        if (existing != null) {
            existing.addMapping(urlPatterns, servletNames, dispatcherTypes);
        } else {
            FilterInfo info = new FilterInfo(name, className, urlPatterns, servletNames,
                    dispatcherTypes, initParams, priority, asyncSupported);
            deployment.getValue().addFilter(info);
        }
        log.debugf("Registered filter metadata: %s -> %s (servletNames=%s)", name, urlPatterns, servletNames);
    }

    public void registerSecurityConstraints(RuntimeValue<ServletDeployment> deployment,
            List<ServletSecurityConstraint> constraints) {
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
                policy.setDenyUncoveredHttpMethods(dep.isDenyUncoveredHttpMethods());
            }
        }
        // BASIC and FORM login are verified by the container, but the users are the application's.
        // Nothing else installs a store, so leaving this out means every credential is rejected.
        if (dep.getIdentityStore() == ServletIdentityStore.EMPTY) {
            IdentityProviderManager identityProviderManager = Arc.container()
                    .instance(IdentityProviderManager.class).get();
            if (identityProviderManager != null) {
                dep.setIdentityStore(new QuarkusIdentityStore(identityProviderManager));
            }
        }
    }

    public void addContainerInitializer(RuntimeValue<ServletDeployment> deployment,
            String className, Set<String> handledTypeNames) {
        deployment.getValue().addContainerInitializer(
                new ServletContainerInitializerInfo(className, handledTypeNames));
        log.debugf("Registered ServletContainerInitializer: %s (%d handled types)",
                className, handledTypeNames.size());
    }

    public void registerListener(RuntimeValue<ServletDeployment> deployment, String className) {
        registerListener(deployment, className, false);
    }

    public void registerListener(RuntimeValue<ServletDeployment> deployment, String className,
            boolean restricted) {
        try {
            Class<?> clazz = Thread.currentThread().getContextClassLoader().loadClass(className);
            Object instance = Arc.container().instance(clazz).get();
            if (instance == null) {
                instance = clazz.getDeclaredConstructor().newInstance();
            }
            deployment.getValue().getServletContext()
                    .addRegisteredListener((java.util.EventListener) instance, restricted);
            log.debugf("Registered listener: %s", className);
        } catch (Exception e) {
            throw new RuntimeException("Failed to register listener: " + className, e);
        }
    }

    public void setWelcomeFiles(RuntimeValue<ServletDeployment> deployment, List<String> welcomeFiles) {
        deployment.getValue().setWelcomeFiles(welcomeFiles);
    }

    public void addErrorPage(RuntimeValue<ServletDeployment> deployment, int statusCode, String location) {
        deployment.getValue().addErrorPage(statusCode, location);
    }

    public void addExceptionErrorPage(RuntimeValue<ServletDeployment> deployment,
            String exceptionType, String location) {
        deployment.getValue().addExceptionErrorPage(exceptionType, location);
    }

    /**
     * Supplies the FORM mechanism as a bean. Built here rather than declared because the login and
     * error pages come from web.xml and only reach configuration as runtime defaults.
     */
    public java.util.function.Supplier<ServletFormAuthenticationMechanism> formAuthenticationMechanism() {
        return () -> {
            io.smallrye.config.SmallRyeConfig config = org.eclipse.microprofile.config.ConfigProvider
                    .getConfig().unwrap(io.smallrye.config.SmallRyeConfig.class);
            return new ServletFormAuthenticationMechanism(
                    config.getConfigMapping(io.quarkus.vertx.http.runtime.VertxHttpConfig.class)
                            .auth().form(),
                    config.getOptionalValue("quarkus.http.auth.session.encryption-key", String.class));
        };
    }

    public void setLoginConfig(RuntimeValue<ServletDeployment> deployment, String authMethod,
            String realmName, String formLoginPage, String formErrorPage) {
        deployment.getValue().setLoginConfig(
                new LoginConfig(authMethod, realmName, formLoginPage, formErrorPage));
    }

    public void setEffectiveVersion(RuntimeValue<ServletDeployment> deployment, int major, int minor) {
        deployment.getValue().getServletContext().setEffectiveVersion(major, minor);
    }

    public void setSessionTimeout(RuntimeValue<ServletDeployment> deployment, int minutes) {
        deployment.getValue().getServletContext().setDeploymentSessionTimeout(minutes);
    }

    public void setDisplayName(RuntimeValue<ServletDeployment> deployment, String displayName) {
        deployment.getValue().getServletContext().setDisplayName(displayName);
    }

    public void setDenyUncoveredHttpMethods(RuntimeValue<ServletDeployment> deployment, boolean deny) {
        deployment.getValue().setDenyUncoveredHttpMethods(deny);
    }

    public void setLocaleEncodingMappings(RuntimeValue<ServletDeployment> deployment,
            Map<String, String> mappings) {
        deployment.getValue().getServletContext().setLocaleEncodingMappings(mappings);
    }

    public Handler<RoutingContext> boot(RuntimeValue<ServletDeployment> deploymentValue,
            ShutdownContext shutdown) {
        ServletDeployment deployment = deploymentValue.getValue();

        ServletRuntimeConfig config = org.eclipse.microprofile.config.ConfigProvider.getConfig()
                .unwrap(io.smallrye.config.SmallRyeConfig.class)
                .getConfigMapping(ServletRuntimeConfig.class);

        deployment.setMaxParameters(config.maxParameters());
        deployment.setDefaultExecutionModel(config.defaultExecutionModel());

        // Context initialization comes first: the spec requires ServletContainerInitializers and
        // ServletContextListeners to have run, and any servlets or filters they registered to
        // exist, before a single servlet or filter is initialized.
        initializeContext(deployment);

        instantiateServlets(deployment);
        instantiateFilters(deployment);
        deployment.initServlets();

        deployment.initCdiCache(
                Arc.container().requestContext(),
                Arc.container().instance(CurrentVertxRequest.class).get(),
                Arc.container().instance(CurrentIdentityAssociation.class).get());

        currentDeployment = deployment;
        shutdown.addShutdownTask(() -> {
            deployment.getSessionStore().stopExpiryReaper();
            deployment.destroy();
            destroyContext(deployment);
            currentDeployment = null;
        });

        Set<String> disallowed = config.disallowedMethods().orElse(null);
        return new ServletHandler(deployment, disallowed);
    }

    /**
     * Runs the context initialization phase: container initializers, then context listeners, with
     * programmatic registration allowed throughout and shut off at the end.
     * <p>
     * The window matters as much as the order. Servlet 6.1 only permits
     * {@code ServletContext.addServlet}/{@code addFilter}/{@code addListener} and friends during
     * initialization, and requires {@link IllegalStateException} afterwards; a listener that was
     * itself added programmatically is further restricted and may not register anything at all.
     * Nothing else opens this window, so without this the context is permanently closed and every
     * programmatic registration fails.
     */
    private void initializeContext(ServletDeployment deployment) {
        VertxServletContext servletContext = deployment.getServletContext();
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        servletContext.setInitializing(true);
        try {
            // Everything registered so far came from web.xml or an annotation, so anything added
            // beyond this point was added programmatically.
            int declaredListenerCount = servletContext.getListeners().size();

            for (ServletContainerInitializerInfo info : deployment.getContainerInitializers()) {
                runContainerInitializer(info, servletContext, cl);
            }

            // Whatever the initializers registered has to become real before listeners run, so a
            // listener sees the same context the servlets will.
            finalizeRegistrations(deployment);

            // Listeners registered by an initializer are themselves restricted, so the boundary
            // has to be captured before they are added rather than derived afterwards.
            List<EventListener> listeners = new ArrayList<>(servletContext.getListeners());
            ServletContextEvent event = new ServletContextEvent(servletContext);
            for (int i = 0; i < listeners.size(); i++) {
                if (listeners.get(i) instanceof ServletContextListener listener) {
                    // Position identifies the listeners added during this phase; the explicit flag
                    // covers those registered at build time that the descriptor never declared,
                    // such as a listener found in a TLD, which sit below the boundary.
                    notifyContextInitialized(servletContext, listener, event,
                            i >= declaredListenerCount || servletContext.isRestrictedListener(listener));
                }
            }

            finalizeRegistrations(deployment);
        } finally {
            servletContext.setInitializing(false);
        }
    }

    private void runContainerInitializer(ServletContainerInitializerInfo info,
            VertxServletContext servletContext, ClassLoader cl) {
        try {
            Class<?> clazz = cl.loadClass(info.getClassName());
            Object instance = Arc.container().instance(clazz).get();
            if (instance == null) {
                instance = clazz.getDeclaredConstructor().newInstance();
            }
            Set<Class<?>> handled = null;
            if (!info.getHandledTypeNames().isEmpty()) {
                handled = new LinkedHashSet<>();
                for (String name : info.getHandledTypeNames()) {
                    try {
                        handled.add(cl.loadClass(name));
                    } catch (ClassNotFoundException e) {
                        log.debugf("HandlesTypes match not loadable at runtime: %s", name);
                    }
                }
            }
            ((ServletContainerInitializer) instance).onStartup(handled, servletContext);
            log.debugf("Ran ServletContainerInitializer: %s", info.getClassName());
        } catch (Exception e) {
            throw new RuntimeException(
                    "ServletContainerInitializer failed: " + info.getClassName(), e);
        }
    }

    private void notifyContextInitialized(VertxServletContext servletContext,
            ServletContextListener listener, ServletContextEvent event, boolean programmatic) {
        if (programmatic) {
            servletContext.setInitializing(false);
            servletContext.setRestrictedContext(true);
        }
        servletContext.setInListenerContext(true);
        try {
            listener.contextInitialized(event);
        } finally {
            servletContext.setInListenerContext(false);
            if (programmatic) {
                servletContext.setRestrictedContext(false);
                servletContext.setInitializing(true);
            }
        }
    }

    private void finalizeRegistrations(ServletDeployment deployment) {
        try {
            deployment.getServletContext().finalizeRegistrations(deployment);
        } catch (ServletException e) {
            throw new RuntimeException("Failed to apply programmatic registrations", e);
        }
    }

    /** Fires {@code contextDestroyed} in reverse registration order, as the spec requires. */
    private void destroyContext(ServletDeployment deployment) {
        VertxServletContext servletContext = deployment.getServletContext();
        List<EventListener> listeners = new ArrayList<>(servletContext.getListeners());
        ServletContextEvent event = new ServletContextEvent(servletContext);
        for (int i = listeners.size() - 1; i >= 0; i--) {
            if (listeners.get(i) instanceof ServletContextListener listener) {
                try {
                    listener.contextDestroyed(event);
                } catch (Exception e) {
                    log.warnf(e, "Error in contextDestroyed: %s", listener.getClass().getName());
                }
            }
        }
    }

    private void instantiateServlets(ServletDeployment deployment) {
        for (ServletInfo info : deployment.getServlets().values()) {
            try {
                deployment.instantiateServlet(info);
                log.debugf("Instantiated servlet: %s (%s)", info.getName(), info.getClassName());
            } catch (ServletException e) {
                throw new RuntimeException(e.getMessage(), e.getCause());
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

    // ---- Execution model dispatch ----

    /**
     * Runs {@code action} on the thread the given execution model calls for. The request's own
     * Vert.x context is always used for event-loop execution so that response writes stay on the
     * context that owns the connection.
     */
    static void dispatchOn(ExecutionModel model, RoutingContext rc, Runnable action) {
        switch (model) {
            case WORKER -> rc.vertx().executeBlocking(() -> {
                action.run();
                return null;
            }, false);
            case VIRTUAL_THREAD -> VirtualThreadsRecorder.getCurrent().execute(action);
            case EVENT_LOOP -> {
                Context ctx = (Context) rc.get(VERTX_CONTEXT_KEY);
                if (ctx == null) {
                    ctx = rc.vertx().getOrCreateContext();
                }
                if (Vertx.currentContext() == ctx) {
                    action.run();
                } else {
                    ctx.runOnContext(v -> action.run());
                }
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

            // Started here rather than in boot(): the Vert.x synthetic bean is not initialized yet
            // at that point. The call is idempotent.
            deployment.getSessionStore().startExpiryReaper(rc.vertx());

            // Remember the context that owns this connection before any thread hand-off, so async
            // completion can get back onto it.
            Context vertxContext = Vertx.currentContext();
            if (vertxContext != null) {
                rc.put(VERTX_CONTEXT_KEY, vertxContext);
            }

            ExecutionModel model = resolveExecutionModel(rc);
            Buffer body = rc.body() != null ? rc.body().buffer() : null;

            String method = rc.request().method().name();
            boolean mayHaveBody = !"GET".equals(method) && !"HEAD".equals(method)
                    && !"OPTIONS".equals(method) && !"TRACE".equals(method);

            if (body == null && mayHaveBody) {
                rc.request().resume();
                rc.request().body().onComplete(ar -> {
                    Buffer readBody = ar.succeeded() ? ar.result() : null;
                    dispatchOn(model, rc, () -> executeServlet(rc, deployment, readBody, model));
                });
            } else {
                dispatchOn(model, rc, () -> executeServlet(rc, deployment, body, model));
            }
        }

        private ExecutionModel resolveExecutionModel(RoutingContext rc) {
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
            ExecutionModel deploymentDefault = deployment.getDefaultExecutionModel();
            UrlPatternMatcher.MatchResult match = deployment.matchServlet(relativePath);
            if (match == null) {
                return deploymentDefault;
            }
            ServletInfo servlet = match.getServletInfo();
            // An async-supported servlet may start async processing and then block its service()
            // thread (e.g. waiting on the async timeout to fire). On the event loop that would
            // stall the very timer it is waiting for, so such a servlet runs on a worker thread
            // unless it explicitly asked for another model.
            if (servlet.getDeclaredExecutionModel() == null
                    && deploymentDefault == ExecutionModel.EVENT_LOOP
                    && servlet.isAsyncSupported()) {
                return ExecutionModel.WORKER;
            }
            return servlet.getExecutionModel(deploymentDefault);
        }
    }

    public static void executeServletDirect(RoutingContext rc, ServletDeployment deployment,
            Buffer requestBody) {
        executeServletInternal(rc, deployment, requestBody, false,
                deployment.getDefaultExecutionModel());
    }

    private static void executeServlet(RoutingContext rc, ServletDeployment deployment,
            Buffer requestBody, ExecutionModel model) {
        executeServletInternal(rc, deployment, requestBody, true, model);
    }

    private static void executeServletInternal(RoutingContext rc, ServletDeployment deployment,
            Buffer requestBody, boolean useCdi, ExecutionModel model) {
        ManagedContext requestContext = null;
        VertxServletRequest req = null;
        String currentServletName = null;
        boolean asyncHandoff = false;
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
                        // not a servable resource; try the next welcome file
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

            req = new VertxServletRequest(
                    rc, deployment.getServletContext(),
                    match.getServletPath(), match.getPathInfo(),
                    deployment.getServletContext().getContextPath(),
                    requestBody);
            req.setSessionStore(deployment.getSessionStore());
            req.setVertx(rc.vertx());
            req.setDeployment(deployment);
            // Async support is the conjunction of the servlet and every filter in its chain.
            req.setAsyncSupported(ServletDeployment.isAsyncSupported(
                    match.getServletInfo(),
                    deployment.getMatchingFilters(relativePath, match.getServletInfo().getName(),
                            DispatcherType.REQUEST)));
            req.setMapping(match.getMappingMatch(), match.getMatchedPattern(),
                    match.getServletInfo().getName());
            req.setMultipartLimits(match.getServletInfo().getMultipartConfig());
            currentServletName = match.getServletInfo().getName();

            VertxServletResponse resp = new VertxServletResponse(rc.response(), req);

            ServletRequestContext.set(req, resp, rc);

            // Attached before the servlet runs so that a startAsync() during service() already has
            // a way to finish the exchange, with no window where callbacks are missing.
            req.setAsyncCallbacks(new AsyncCallbacks(rc, deployment, req, resp,
                    requestContext, requestContext != null ? requestContext.getState() : null, model));

            // Declarative security is not enforced here. Authorization is ServletSecurityPolicy's
            // job, applied by vert.x-http before this handler runs, and authentication is
            // Quarkus's - whose form mechanism already posts to /j_security_check with
            // j_username/j_password, exactly as the servlet spec prescribes. Enforcing again here
            // would mean two layers challenging the same request, and they do not agree.

            // Notify request listeners
            for (java.util.EventListener l : deployment.getServletContext().getListeners()) {
                if (l instanceof jakarta.servlet.ServletRequestListener srl) {
                    srl.requestInitialized(new jakarta.servlet.ServletRequestEvent(deployment.getServletContext(), req));
                }
            }

            // Servlets initialise lazily on first access. Do it now that the request, response and
            // request-scoped context all exist, so a ServletException from init() is routed to a
            // matching <error-page> exactly like one thrown from service() - the outer catch below
            // handles it once req is non-null.
            try {
                deployment.ensureServletInitialized(match.getServletInfo());
            } catch (jakarta.servlet.UnavailableException ue) {
                match.getServletInfo().setInitFailed(true);
                if (!rc.response().headWritten()) {
                    rc.response().setStatusCode(404);
                }
                if (!rc.response().ended()) {
                    rc.response().end("Servlet unavailable");
                }
                return;
            }
            if (match.getServletInfo().isInitFailed()) {
                // A failure cached from an earlier request: re-surface it so it takes the same route.
                if (match.getServletInfo().isPermanentlyUnavailable()) {
                    if (!rc.response().headWritten()) {
                        rc.response().setStatusCode(404);
                    }
                    if (!rc.response().ended()) {
                        rc.response().end("Servlet unavailable");
                    }
                    return;
                }
                Exception initEx = match.getServletInfo().getInitException();
                throw initEx != null ? initEx
                        : new ServletException("Servlet init failed: " + match.getServletInfo().getName());
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
                // The servlet took ownership of the exchange. Leave the response open; the
                // AsyncContext finishes it (and releases the request context) later.
                asyncHandoff = true;
                VertxAsyncContext asyncContext = (VertxAsyncContext) req.getAsyncContext();
                // If the client goes away before the cycle completes - the normal end of a
                // Server-Sent Events stream - Vert.x closes the response with nobody listening.
                // addEndHandler reports that as a failed result (connection close or HTTP/2 reset),
                // and we end the cycle so the application's AsyncListener is notified and request
                // state is released, rather than the AsyncContext leaking until undeploy.
                rc.addEndHandler(ar -> {
                    if (ar.failed()) {
                        asyncContext.onClientDisconnect();
                    }
                });
                asyncContext.onServiceReturned();
                return;
            }

            // A miss from the static-resource servlet is not necessarily a miss for the
            // application: hand the request back to the router so other Quarkus routes still get
            // their chance. Only when a 404 error page is configured does the servlet container
            // keep ownership of the response.
            if (resp.isErrorSent() && resp.getErrorCode() == 404
                    && DEFAULT_SERVLET_NAME.equals(match.getServletInfo().getName())
                    && deployment.getErrorPage(404) == null
                    && !rc.response().headWritten()) {
                rc.next();
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
            if (asyncHandoff) {
                // Detach the request-scoped state from this thread but keep it alive for the
                // async cycle; AsyncCallbacks.finish() destroys it.
                ServletRequestContext.clear();
                if (requestContext != null && requestContext.isActive()) {
                    requestContext.deactivate();
                }
            } else {
                if (req != null) {
                    HttpSession session = req.getSession(false);
                    if (session != null) {
                        deployment.getSessionStore().endAccess(session);
                    }
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

    /**
     * Bridges an {@link VertxAsyncContext} back to the container: re-establishes the request's
     * thread-local and CDI state around async work, and performs the one-time cleanup when the
     * async cycle ends.
     */
    private static final class AsyncCallbacks implements VertxAsyncContext.Callbacks {

        private final RoutingContext rc;
        private final ServletDeployment deployment;
        private final VertxServletRequest request;
        private final VertxServletResponse response;
        private final ManagedContext requestContext;
        private final InjectableContext.ContextState contextState;
        private final ExecutionModel model;
        private final AtomicBoolean finished = new AtomicBoolean();

        AsyncCallbacks(RoutingContext rc, ServletDeployment deployment,
                VertxServletRequest request, VertxServletResponse response,
                ManagedContext requestContext, InjectableContext.ContextState contextState,
                ExecutionModel model) {
            this.rc = rc;
            this.deployment = deployment;
            this.request = request;
            this.response = response;
            this.requestContext = requestContext;
            this.contextState = contextState;
            this.model = model;
        }

        @Override
        public void execute(Runnable action) {
            dispatchOn(model, rc, () -> runScoped(action));
        }

        @Override
        public void finish() {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            runScoped(() -> {
                try {
                    HttpSession session = request.getSession(false);
                    if (session != null) {
                        deployment.getSessionStore().endAccess(session);
                    }
                    var listeners = deployment.getServletContext().getListeners();
                    for (int i = listeners.size() - 1; i >= 0; i--) {
                        if (listeners.get(i) instanceof jakarta.servlet.ServletRequestListener srl) {
                            srl.requestDestroyed(new jakarta.servlet.ServletRequestEvent(
                                    deployment.getServletContext(), request));
                        }
                    }
                    response.close();
                } catch (Exception e) {
                    log.error("Error finishing async request", e);
                    if (!rc.response().ended()) {
                        rc.response().end();
                    }
                } finally {
                    if (requestContext != null && requestContext.isActive()) {
                        requestContext.terminate();
                    }
                }
            });
        }

        /**
         * Runs {@code action} with the servlet request context and the original CDI request context
         * active, restoring the thread to its previous state afterwards.
         */
        private void runScoped(Runnable action) {
            boolean activatedHere = false;
            if (requestContext != null && !requestContext.isActive()) {
                requestContext.activate(contextState);
                activatedHere = true;
            }
            CurrentVertxRequest currentVertxRequest = deployment.getCachedCurrentVertxRequest();
            if (currentVertxRequest != null) {
                currentVertxRequest.setCurrent(rc);
            }
            ServletRequestContext.set(request, response, rc);
            try {
                action.run();
            } finally {
                ServletRequestContext.clear();
                if (activatedHere && requestContext != null && requestContext.isActive()) {
                    requestContext.deactivate();
                }
            }
        }
    }

    private static void handleErrorPage(RoutingContext rc, ServletDeployment deployment,
            VertxServletRequest req, VertxServletResponse resp,
            Buffer requestBody, String servletName) {
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
