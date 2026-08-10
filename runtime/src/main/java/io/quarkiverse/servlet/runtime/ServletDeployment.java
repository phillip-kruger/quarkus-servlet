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
    private final VertxSessionStore sessionStore = new VertxSessionStore();
    private List<String> welcomeFiles = List.of();
    private int maxParameters = 1000;
    private boolean defaultVirtualThreads;
    private List<ServletSecurityConstraint> securityConstraints = List.of();

    private ManagedContext cachedRequestContext;
    private CurrentVertxRequest cachedCurrentVertxRequest;
    private CurrentIdentityAssociation cachedIdentityAssociation;

    public ServletDeployment(VertxServletContext servletContext) {
        this.servletContext = servletContext;
    }

    public int getMaxParameters() {
        return maxParameters;
    }

    public void setMaxParameters(int maxParameters) {
        this.maxParameters = maxParameters;
    }

    public boolean isDefaultVirtualThreads() {
        return defaultVirtualThreads;
    }

    public void setDefaultVirtualThreads(boolean defaultVirtualThreads) {
        this.defaultVirtualThreads = defaultVirtualThreads;
    }

    public List<ServletSecurityConstraint> getSecurityConstraints() {
        return securityConstraints;
    }

    public void setSecurityConstraints(List<ServletSecurityConstraint> securityConstraints) {
        this.securityConstraints = securityConstraints;
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

    public UrlPatternMatcher.MatchResult matchServlet(String path) {
        return urlPatternMatcher.match(path);
    }

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
        return filterCache.computeIfAbsent(key, k -> {
            List<FilterInfo> matching = new ArrayList<>();
            for (FilterInfo filter : filters) {
                if (filter.matches(path, servletName, dispatcherType)) {
                    matching.add(filter);
                }
            }
            return matching.isEmpty() ? EMPTY_FILTERS : matching.toArray(new FilterInfo[0]);
        });
    }

    private record FilterCacheKey(String path, String servletName, DispatcherType dispatcherType) {
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

    private void initServlet(ServletInfo info) throws ServletException {
        if (!info.isInitialized() && !info.isInitFailed()) {
            try {
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
