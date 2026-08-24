package io.quarkiverse.servlet.runtime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletException;

import org.jboss.logging.Logger;

import io.quarkus.arc.ManagedContext;
import io.quarkus.security.identity.CurrentIdentityAssociation;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;

public class ServletDeployment {

    private static final Logger log = Logger.getLogger(ServletDeployment.class);
    private static final FilterInfo[] EMPTY_FILTERS = new FilterInfo[0];

    private final VertxServletContext servletContext;
    private final UrlPatternMatcher urlPatternMatcher = new UrlPatternMatcher();
    private final Map<String, ServletInfo> servlets = new LinkedHashMap<>();
    private final Map<Integer, String> errorPages = new LinkedHashMap<>();
    private final Map<String, String> exceptionErrorPages = new LinkedHashMap<>();
    private final List<FilterInfo> filters = new ArrayList<>();
    private final List<ServletContainerInitializerInfo> containerInitializers = new ArrayList<>();
    private final VertxSessionStore sessionStore = new VertxSessionStore();
    private List<String> welcomeFiles = List.of();
    private int maxParameters = 1000;
    private ExecutionModel defaultExecutionModel = ExecutionModel.EVENT_LOOP;
    private List<ServletSecurityConstraint> securityConstraints = List.of();
    private boolean denyUncoveredHttpMethods;
    private LoginConfig loginConfig;
    private ServletIdentityStore identityStore = ServletIdentityStore.EMPTY;

    private ManagedContext cachedRequestContext;
    private CurrentVertxRequest cachedCurrentVertxRequest;
    private CurrentIdentityAssociation cachedIdentityAssociation;

    public ServletDeployment(VertxServletContext servletContext) {
        this.servletContext = servletContext;
        // The context needs to reach back into the deployment to resolve request dispatchers.
        // Wiring it here rather than at each call site means a caller cannot forget: without it
        // every ServletContext.getRequestDispatcher() returns a dispatcher with nothing to
        // dispatch to, and forwards fail at request time rather than at deployment time.
        servletContext.setDeployment(this);
    }

    public int getMaxParameters() {
        return maxParameters;
    }

    public void setMaxParameters(int maxParameters) {
        this.maxParameters = maxParameters;
    }

    public ExecutionModel getDefaultExecutionModel() {
        return defaultExecutionModel;
    }

    public void setDefaultExecutionModel(ExecutionModel defaultExecutionModel) {
        this.defaultExecutionModel = defaultExecutionModel;
    }

    public List<ServletSecurityConstraint> getSecurityConstraints() {
        return securityConstraints;
    }

    public void setSecurityConstraints(List<ServletSecurityConstraint> securityConstraints) {
        this.securityConstraints = securityConstraints;
    }

    /**
     * When {@code <deny-uncovered-http-methods/>} is declared, a request whose method is not
     * covered by any constraint on an otherwise-constrained url-pattern is denied rather than
     * allowed through.
     */
    public boolean isDenyUncoveredHttpMethods() {
        return denyUncoveredHttpMethods;
    }

    public void setDenyUncoveredHttpMethods(boolean denyUncoveredHttpMethods) {
        this.denyUncoveredHttpMethods = denyUncoveredHttpMethods;
    }

    /** The deployment's {@code <login-config>}, or {@code null} if it declared none. */
    public LoginConfig getLoginConfig() {
        return loginConfig;
    }

    public void setLoginConfig(LoginConfig loginConfig) {
        this.loginConfig = loginConfig;
    }

    public ServletIdentityStore getIdentityStore() {
        return identityStore;
    }

    public void setIdentityStore(ServletIdentityStore identityStore) {
        this.identityStore = identityStore != null ? identityStore : ServletIdentityStore.EMPTY;
    }

    public void initCdiCache(ManagedContext requestContext, CurrentVertxRequest currentVertxRequest,
            CurrentIdentityAssociation identityAssociation) {
        this.cachedRequestContext = requestContext;
        this.cachedCurrentVertxRequest = currentVertxRequest;
        this.cachedIdentityAssociation = identityAssociation;
    }

    public ManagedContext getCachedRequestContext() {
        return cachedRequestContext;
    }

    public CurrentVertxRequest getCachedCurrentVertxRequest() {
        return cachedCurrentVertxRequest;
    }

    public CurrentIdentityAssociation getCachedIdentityAssociation() {
        return cachedIdentityAssociation;
    }

    public VertxSessionStore getSessionStore() {
        return sessionStore;
    }

    public void addServlet(ServletInfo servletInfo) {
        servlets.put(servletInfo.getName(), servletInfo);
        for (String mapping : servletInfo.getMappings()) {
            urlPatternMatcher.addMapping(mapping, servletInfo);
        }
    }

    public void addFilter(FilterInfo filterInfo) {
        filters.add(filterInfo);
        filters.sort(Comparator.comparingInt(FilterInfo::getPriority));
    }

    /**
     * Returns the already-registered filter with the given name, or {@code null}. Used when a filter
     * declares several {@code <filter-mapping>} entries: each mapping keeps its own dispatcher set and
     * target, so they are merged onto one {@link FilterInfo} rather than producing duplicate filters.
     */
    public FilterInfo getFilter(String name) {
        for (FilterInfo filter : filters) {
            if (filter.getName().equals(name)) {
                return filter;
            }
        }
        return null;
    }

    public UrlPatternMatcher.MatchResult matchServlet(String path) {
        return urlPatternMatcher.match(path);
    }

    /**
     * Bounded because the cache is keyed by request path, which is attacker-controlled: an
     * unbounded map would grow forever under a stream of distinct URLs.
     */
    private static final int FILTER_CACHE_MAX_ENTRIES = 4096;

    private final ConcurrentHashMap<FilterCacheKey, FilterInfo[]> filterCache = new ConcurrentHashMap<>();

    public FilterInfo[] getMatchingFilters(String path, DispatcherType dispatcherType) {
        return getMatchingFilters(path, null, dispatcherType);
    }

    public FilterInfo[] getMatchingFilters(String path, String servletName,
            DispatcherType dispatcherType) {
        if (filters.isEmpty()) {
            return EMPTY_FILTERS;
        }
        FilterCacheKey key = new FilterCacheKey(path, servletName, dispatcherType);
        FilterInfo[] cached = filterCache.get(key);
        if (cached != null) {
            return cached;
        }
        List<FilterInfo> matching = new ArrayList<>();
        for (FilterInfo filter : filters) {
            if (filter.matches(path, servletName, dispatcherType)) {
                matching.add(filter);
            }
        }
        FilterInfo[] result = matching.isEmpty() ? EMPTY_FILTERS : matching.toArray(new FilterInfo[0]);
        if (filterCache.size() < FILTER_CACHE_MAX_ENTRIES) {
            filterCache.putIfAbsent(key, result);
        }
        return result;
    }

    private record FilterCacheKey(String path, String servletName, DispatcherType dispatcherType) {
    }

    /**
     * A request supports async only if the target servlet and every filter applied to it declare
     * async support, as required by the servlet spec.
     */
    public static boolean isAsyncSupported(ServletInfo servlet, FilterInfo[] filters) {
        if (!servlet.isAsyncSupported()) {
            return false;
        }
        for (FilterInfo filter : filters) {
            if (!filter.isAsyncSupported()) {
                return false;
            }
        }
        return true;
    }

    public void initServlets() {
        List<ServletInfo> loadOnStartup = servlets.values().stream()
                .filter(s -> s.getLoadOnStartup() >= 0)
                .sorted(Comparator.comparingInt(ServletInfo::getLoadOnStartup))
                .toList();

        for (ServletInfo info : loadOnStartup) {
            try {
                initServlet(info);
            } catch (ServletException e) {
                log.warnf("Servlet init failed (load-on-startup): %s - %s",
                        info.getName(), e.getMessage());
            }
        }
    }

    /**
     * Creates the servlet instance if it does not have one yet.
     * <p>
     * Most servlets are instantiated in one pass when the deployment boots, but a servlet can also
     * be registered after that - programmatically, or by name from a descriptor the container
     * reaches later - and the first thing to touch it may well be a forward rather than that pass.
     * Instantiating on demand means such a servlet works instead of failing with a null instance.
     */
    public void instantiateServlet(ServletInfo info) throws ServletException {
        if (info.getServlet() != null) {
            return;
        }
        try {
            Class<?> clazz = Thread.currentThread().getContextClassLoader()
                    .loadClass(info.getClassName());
            Object instance = io.quarkus.arc.Arc.container().instance(clazz).get();
            if (instance == null) {
                instance = clazz.getDeclaredConstructor().newInstance();
            }
            info.setServlet((jakarta.servlet.Servlet) instance);
        } catch (Exception | LinkageError e) {
            // LinkageError as well as Exception: a servlet whose class is broken or whose
            // dependencies are missing surfaces as NoClassDefFoundError, and letting that escape
            // takes down the whole application instead of marking one servlet unavailable.
            throw new ServletException("Failed to instantiate servlet: " + info.getClassName(), e);
        }
    }

    private void initServlet(ServletInfo info) throws ServletException {
        if (!info.isInitialized() && !info.isInitFailed()) {
            try {
                instantiateServlet(info);
                SimpleServletConfig config = new SimpleServletConfig(
                        info.getName(), servletContext, info.getInitParams());
                info.getServlet().init(config);
                info.setInitialized(true);
                log.debugf("Initialized servlet: %s", info.getName());
            } catch (Exception e) {
                info.setInitFailed(true);
                info.setInitException(e);
                if (e instanceof jakarta.servlet.UnavailableException) {
                    info.setPermanentlyUnavailable(true);
                }
                log.warnf("Servlet init failed: %s - %s", info.getName(), e.getMessage());
                if (e instanceof ServletException) {
                    throw (ServletException) e;
                }
                throw new ServletException("Failed to initialize servlet: " + info.getName(), e);
            }
        }
    }

    public void ensureServletInitialized(ServletInfo info) throws ServletException {
        if (!info.isInitialized()) {
            initServlet(info);
        }
    }

    public void destroy() {
        for (FilterInfo filter : filters) {
            try {
                filter.getFilter().destroy();
            } catch (Exception e) {
                log.warnf(e, "Error destroying filter: %s", filter.getName());
            }
        }
        for (ServletInfo servlet : servlets.values()) {
            if (servlet.isInitialized()) {
                try {
                    servlet.getServlet().destroy();
                } catch (Exception e) {
                    log.warnf(e, "Error destroying servlet: %s", servlet.getName());
                }
            }
        }
    }

    public VertxServletContext getServletContext() {
        return servletContext;
    }

    public Map<String, ServletInfo> getServlets() {
        return servlets;
    }

    public List<FilterInfo> getFilters() {
        return filters;
    }

    public void addContainerInitializer(ServletContainerInitializerInfo info) {
        containerInitializers.add(info);
    }

    public List<ServletContainerInitializerInfo> getContainerInitializers() {
        return containerInitializers;
    }

    public void addErrorPage(int statusCode, String location) {
        errorPages.put(statusCode, location);
    }

    public String getErrorPage(int statusCode) {
        return errorPages.get(statusCode);
    }

    public Map<Integer, String> getErrorPages() {
        return errorPages;
    }

    public void setWelcomeFiles(List<String> welcomeFiles) {
        this.welcomeFiles = welcomeFiles;
    }

    public List<String> getWelcomeFiles() {
        return welcomeFiles;
    }

    public void addExceptionErrorPage(String exceptionType, String location) {
        exceptionErrorPages.put(exceptionType, location);
    }

    public String getExceptionErrorPage(Throwable t) {
        if (t == null) {
            return null;
        }
        // Unwrap ServletException to get the root cause
        Throwable actual = t;
        if (t instanceof jakarta.servlet.ServletException se && se.getRootCause() != null) {
            actual = se.getRootCause();
        }
        Class<?> clazz = actual.getClass();
        while (clazz != null) {
            String page = exceptionErrorPages.get(clazz.getName());
            if (page != null) {
                return page;
            }
            clazz = clazz.getSuperclass();
        }
        // Also try the wrapper itself if different from actual
        if (actual != t) {
            clazz = t.getClass();
            while (clazz != null) {
                String page = exceptionErrorPages.get(clazz.getName());
                if (page != null) {
                    return page;
                }
                clazz = clazz.getSuperclass();
            }
        }
        if (actual.getCause() != null && actual.getCause() != actual) {
            return getExceptionErrorPage(actual.getCause());
        }
        return null;
    }

    public Throwable unwrapException(Throwable t) {
        if (t instanceof jakarta.servlet.ServletException se && se.getRootCause() != null) {
            return se.getRootCause();
        }
        return t;
    }
}
