package io.quarkiverse.servlet.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.EventListener;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterRegistration;
import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextAttributeEvent;
import jakarta.servlet.ServletContextAttributeListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRegistration;
import jakarta.servlet.ServletSecurityElement;
import jakarta.servlet.SessionCookieConfig;
import jakarta.servlet.SessionTrackingMode;
import jakarta.servlet.descriptor.JspConfigDescriptor;

import org.jboss.logging.Logger;

public class VertxServletContext implements ServletContext {

    private static final Logger log = Logger.getLogger(VertxServletContext.class);
    private static final int MAJOR_VERSION = 6;
    private static final int MINOR_VERSION = 1;

    private final String contextPath;
    private final Map<String, String> initParams;
    private final ConcurrentHashMap<String, Object> attributes = new ConcurrentHashMap<>();
    private String displayName;
    private final List<EventListener> listeners = new ArrayList<>();
    private Map<String, String> mimeMappings = Map.of();
    private Map<String, String> localeEncodingMappings = Map.of();
    private Path deploymentDir;
    private ServletDeployment deployment;
    private boolean initializing;
    private boolean restrictedContext;
    private boolean inListenerContext;
    private final Map<String, SimpleServletRegistration> servletRegistrations = new LinkedHashMap<>();
    private final Map<String, SimpleFilterRegistration> filterRegistrations = new LinkedHashMap<>();
    private final SimpleSessionCookieConfig sessionCookieConfig = new SimpleSessionCookieConfig(this);
    private Set<SessionTrackingMode> sessionTrackingModes;
    private final Set<String> declaredRoles = new HashSet<>();

    public VertxServletContext(String contextPath, Map<String, String> initParams) {
        this.contextPath = contextPath;
        this.initParams = new ConcurrentHashMap<>(initParams);
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public void setLocaleEncodingMappings(Map<String, String> localeEncodingMappings) {
        this.localeEncodingMappings = localeEncodingMappings;
    }

    public String getLocaleEncodingMapping(java.util.Locale locale) {
        if (localeEncodingMappings.isEmpty()) {
            return null;
        }
        String country = locale.getCountry();
        String language = locale.getLanguage();
        if (country != null && !country.isEmpty()) {
            String mapping = localeEncodingMappings.get(language + "_" + country);
            if (mapping != null) {
                return mapping;
            }
        }
        return localeEncodingMappings.get(language);
    }

    public void setMimeMappings(Map<String, String> mimeMappings) {
        this.mimeMappings = mimeMappings;
    }

    public void setDeploymentDir(Path deploymentDir) {
        this.deploymentDir = deploymentDir;
    }

    public void setDeployment(ServletDeployment deployment) {
        this.deployment = deployment;
    }

    public void addRegisteredListener(EventListener listener) {
        listeners.add(listener);
    }

    public List<EventListener> getListeners() {
        return listeners;
    }

    public void setInitializing(boolean initializing) {
        this.initializing = initializing;
    }

    public void setRestrictedContext(boolean restricted) {
        this.restrictedContext = restricted;
    }

    public void setInListenerContext(boolean inListenerContext) {
        this.inListenerContext = inListenerContext;
    }

    public boolean isInitializing() {
        return initializing;
    }

    private void checkProgrammaticAccess() {
        if (restrictedContext) {
            throw new UnsupportedOperationException(
                    "This method cannot be called from a programmatically-added listener");
        }
    }

    private void checkInitializationPhase() {
        if (!initializing) {
            if (restrictedContext) {
                throw new UnsupportedOperationException(
                        "Cannot call this method from a programmatically-added listener");
            }
            throw new IllegalStateException(
                    "Cannot call this method outside of initialization");
        }
    }

    @Override
    public String getContextPath() {
        return contextPath;
    }

    @Override
    public ServletContext getContext(String uripath) {
        return null;
    }

    @Override
    public int getMajorVersion() {
        return MAJOR_VERSION;
    }

    @Override
    public int getMinorVersion() {
        return MINOR_VERSION;
    }

    private int effectiveMajorVersion = MAJOR_VERSION;
    private int effectiveMinorVersion = MINOR_VERSION;

    public void setEffectiveVersion(int major, int minor) {
        this.effectiveMajorVersion = major;
        this.effectiveMinorVersion = minor;
    }

    @Override
    public int getEffectiveMajorVersion() {
        return effectiveMajorVersion;
    }

    @Override
    public int getEffectiveMinorVersion() {
        return effectiveMinorVersion;
    }

    @Override
    public String getMimeType(String file) {
        if (file == null) {
            return null;
        }
        int dotIndex = file.lastIndexOf('.');
        if (dotIndex < 0) {
            return null;
        }
        String ext = file.substring(dotIndex + 1).toLowerCase();
        String mapped = mimeMappings.get(ext);
        if (mapped != null) {
            return mapped;
        }
        return switch (ext) {
            case "html", "htm" -> "text/html";
            case "css" -> "text/css";
            case "js" -> "application/javascript";
            case "json" -> "application/json";
            case "xml" -> "application/xml";
            case "txt" -> "text/plain";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "svg" -> "image/svg+xml";
            case "ico" -> "image/x-icon";
            case "pdf" -> "application/pdf";
            case "zip" -> "application/zip";
            case "jar" -> "application/java-archive";
            case "woff" -> "font/woff";
            case "woff2" -> "font/woff2";
            default -> null;
        };
    }

    @Override
    public Set<String> getResourcePaths(String path) {
        if (deploymentDir == null) {
            return null;
        }
        Path dir = deploymentDir.resolve(path.startsWith("/") ? path.substring(1) : path);
        if (!Files.isDirectory(dir)) {
            return null;
        }
        Set<String> result = new HashSet<>();
        try (Stream<Path> entries = Files.list(dir)) {
            entries.forEach(p -> {
                String name = path.endsWith("/") ? path : path + "/";
                name += p.getFileName().toString();
                if (Files.isDirectory(p)) {
                    name += "/";
                }
                result.add(name);
            });
        } catch (IOException e) {
            return null;
        }
        return result.isEmpty() ? null : result;
    }

    @Override
    public URL getResource(String path) throws MalformedURLException {
        if (path == null || !path.startsWith("/")) {
            throw new MalformedURLException("Path must start with '/' but was: " + path);
        }
        if (deploymentDir != null) {
            Path resolved = deploymentDir.resolve(path.startsWith("/") ? path.substring(1) : path);
            if (Files.exists(resolved)) {
                return resolved.toUri().toURL();
            }
        }
        URL url = Thread.currentThread().getContextClassLoader().getResource("META-INF/resources" + path);
        if (url != null) {
            return url;
        }
        return Thread.currentThread().getContextClassLoader().getResource(path.startsWith("/") ? path.substring(1) : path);
    }

    @Override
    public InputStream getResourceAsStream(String path) {
        if (deploymentDir != null) {
            Path resolved = deploymentDir.resolve(path.startsWith("/") ? path.substring(1) : path);
            if (Files.exists(resolved)) {
                try {
                    return Files.newInputStream(resolved);
                } catch (IOException e) {
                    return null;
                }
            }
        }
        InputStream is = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("META-INF/resources" + path);
        if (is != null) {
            return is;
        }
        return Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(path.startsWith("/") ? path.substring(1) : path);
    }

    @Override
    public RequestDispatcher getRequestDispatcher(String path) {
        if (path == null || !path.startsWith("/")) {
            return null;
        }
        return new VertxRequestDispatcher(path, deployment);
    }

    @Override
    public RequestDispatcher getNamedDispatcher(String name) {
        if (name == null) {
            return null;
        }
        if (deployment != null && deployment.getServlets().containsKey(name)) {
            return new VertxRequestDispatcher(null, deployment, true, name);
        }
        return null;
    }

    @Override
    public void log(String msg) {
        log.info(msg);
    }

    @Override
    public void log(String message, Throwable throwable) {
        log.error(message, throwable);
    }

    @Override
    public String getRealPath(String path) {
        if (deploymentDir != null) {
            Path resolved = deploymentDir.resolve(path.startsWith("/") ? path.substring(1) : path);
            return resolved.toAbsolutePath().toString();
        }
        return null;
    }

    @Override
    public String getServerInfo() {
        return "Quarkus Servlet/1.0";
    }

    @Override
    public String getInitParameter(String name) {
        if (name == null) {
            throw new NullPointerException("Init parameter name cannot be null");
        }
        return initParams.get(name);
    }

    @Override
    public Enumeration<String> getInitParameterNames() {
        return Collections.enumeration(initParams.keySet());
    }

    @Override
    public boolean setInitParameter(String name, String value) {
        if (name == null) {
            throw new NullPointerException("Init parameter name cannot be null");
        }
        checkInitializationPhase();
        return initParams.putIfAbsent(name, value) == null;
    }

    @Override
    public Object getAttribute(String name) {
        if (name == null) {
            throw new NullPointerException("Attribute name cannot be null");
        }
        return attributes.get(name);
    }

    @Override
    public Enumeration<String> getAttributeNames() {
        return Collections.enumeration(attributes.keySet());
    }

    @Override
    public void setAttribute(String name, Object object) {
        if (name == null) {
            throw new NullPointerException("Attribute name cannot be null");
        }
        if (object == null) {
            removeAttribute(name);
        } else {
            Object oldValue = attributes.put(name, object);
            for (EventListener l : listeners) {
                if (l instanceof ServletContextAttributeListener scal) {
                    if (oldValue == null) {
                        scal.attributeAdded(new ServletContextAttributeEvent(this, name, object));
                    } else {
                        scal.attributeReplaced(new ServletContextAttributeEvent(this, name, oldValue));
                    }
                }
            }
        }
    }

    @Override
    public void removeAttribute(String name) {
        Object oldValue = attributes.remove(name);
        if (oldValue != null) {
            for (EventListener l : listeners) {
                if (l instanceof ServletContextAttributeListener scal) {
                    scal.attributeRemoved(new ServletContextAttributeEvent(this, name, oldValue));
                }
            }
        }
    }

    @Override
    public String getServletContextName() {
        return displayName != null ? displayName : "Quarkus Servlet";
    }

    @Override
    public ServletRegistration.Dynamic addServlet(String servletName, String className) {
        checkInitializationPhase();
        if (servletRegistrations.containsKey(servletName)
                || (deployment != null && deployment.getServlets().containsKey(servletName))) {
            return null;
        }
        SimpleServletRegistration reg = new SimpleServletRegistration(servletName, className);
        reg.setContext(this);
        servletRegistrations.put(servletName, reg);
        return reg;
    }

    @Override
    public ServletRegistration.Dynamic addServlet(String servletName, Servlet servlet) {
        checkInitializationPhase();
        if (servletRegistrations.containsKey(servletName)
                || (deployment != null && deployment.getServlets().containsKey(servletName))) {
            return null;
        }
        SimpleServletRegistration reg = new SimpleServletRegistration(servletName, servlet.getClass().getName());
        reg.setServletInstance(servlet);
        reg.setContext(this);
        servletRegistrations.put(servletName, reg);
        return reg;
    }

    @Override
    public ServletRegistration.Dynamic addServlet(String servletName,
            Class<? extends Servlet> servletClass) {
        checkInitializationPhase();
        if (servletRegistrations.containsKey(servletName)
                || (deployment != null && deployment.getServlets().containsKey(servletName))) {
            return null;
        }
        SimpleServletRegistration reg = new SimpleServletRegistration(servletName, servletClass.getName());
        reg.setServletClass(servletClass);
        reg.setContext(this);
        servletRegistrations.put(servletName, reg);
        return reg;
    }

    @Override
    public ServletRegistration.Dynamic addJspFile(String servletName, String jspFile) {
        checkInitializationPhase();
        if (servletName == null || servletName.isEmpty()) {
            throw new IllegalArgumentException("Servlet name cannot be null or empty");
        }
        if (servletRegistrations.containsKey(servletName)
                || (deployment != null && deployment.getServlets().containsKey(servletName))) {
            return null;
        }
        SimpleServletRegistration reg = new SimpleServletRegistration(servletName,
                JspStubServlet.class.getName());
        reg.setContext(this);
        reg.setInitParameter("jspFile", jspFile);
        servletRegistrations.put(servletName, reg);
        return reg;
    }

    @Override
    public <T extends Servlet> T createServlet(Class<T> clazz) throws ServletException {
        checkInitializationPhase();
        try {
            return clazz.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new ServletException("Failed to create servlet: " + clazz.getName(), e);
        }
    }

    @Override
    public ServletRegistration getServletRegistration(String servletName) {
        checkProgrammaticAccess();
        SimpleServletRegistration reg = servletRegistrations.get(servletName);
        if (reg != null) {
            return reg;
        }
        if (deployment != null) {
            ServletInfo info = deployment.getServlets().get(servletName);
            if (info != null) {
                return new SimpleServletRegistration(info);
            }
        }
        return null;
    }

    @Override
    public Map<String, ? extends ServletRegistration> getServletRegistrations() {
        checkProgrammaticAccess();
        Map<String, ServletRegistration> result = new LinkedHashMap<>();
        if (deployment != null) {
            for (Map.Entry<String, ServletInfo> entry : deployment.getServlets().entrySet()) {
                result.put(entry.getKey(), new SimpleServletRegistration(entry.getValue()));
            }
        }
        result.putAll(servletRegistrations);
        return result;
    }

    @Override
    public FilterRegistration.Dynamic addFilter(String filterName, String className) {
        checkInitializationPhase();
        if (filterRegistrations.containsKey(filterName) || hasDeployedFilter(filterName)) {
            return null;
        }
        SimpleFilterRegistration reg = new SimpleFilterRegistration(filterName, className);
        filterRegistrations.put(filterName, reg);
        return reg;
    }

    @Override
    public FilterRegistration.Dynamic addFilter(String filterName, Filter filter) {
        checkInitializationPhase();
        if (filterRegistrations.containsKey(filterName) || hasDeployedFilter(filterName)) {
            return null;
        }
        SimpleFilterRegistration reg = new SimpleFilterRegistration(filterName, filter.getClass().getName());
        reg.setFilterInstance(filter);
        filterRegistrations.put(filterName, reg);
        return reg;
    }

    @Override
    public FilterRegistration.Dynamic addFilter(String filterName,
            Class<? extends Filter> filterClass) {
        checkInitializationPhase();
        if (filterRegistrations.containsKey(filterName) || hasDeployedFilter(filterName)) {
            return null;
        }
        SimpleFilterRegistration reg = new SimpleFilterRegistration(filterName, filterClass.getName());
        reg.setFilterClass(filterClass);
        filterRegistrations.put(filterName, reg);
        return reg;
    }

    private boolean hasDeployedFilter(String filterName) {
        if (deployment == null) {
            return false;
        }
        for (FilterInfo f : deployment.getFilters()) {
            if (f.getName().equals(filterName)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public <T extends Filter> T createFilter(Class<T> clazz) throws ServletException {
        checkInitializationPhase();
        try {
            return clazz.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new ServletException("Failed to create filter: " + clazz.getName(), e);
        }
    }

    @Override
    public FilterRegistration getFilterRegistration(String filterName) {
        checkProgrammaticAccess();
        SimpleFilterRegistration reg = filterRegistrations.get(filterName);
        if (reg != null) {
            return reg;
        }
        if (deployment != null) {
            for (FilterInfo info : deployment.getFilters()) {
                if (info.getName().equals(filterName)) {
                    return new SimpleFilterRegistration(info);
                }
            }
        }
        return null;
    }

    @Override
    public Map<String, ? extends FilterRegistration> getFilterRegistrations() {
        checkProgrammaticAccess();
        Map<String, FilterRegistration> result = new LinkedHashMap<>();
        if (deployment != null) {
            for (FilterInfo info : deployment.getFilters()) {
                result.put(info.getName(), new SimpleFilterRegistration(info));
            }
        }
        result.putAll(filterRegistrations);
        return result;
    }

    @Override
    public SessionCookieConfig getSessionCookieConfig() {
        checkProgrammaticAccess();
        return sessionCookieConfig;
    }

    /**
     * The session cookie configuration, without the programmatic-access restriction that applies to
     * applications. Used by the container itself when it issues the session cookie.
     */
    public SimpleSessionCookieConfig sessionCookieConfig() {
        return sessionCookieConfig;
    }

    @Override
    public void setSessionTrackingModes(Set<SessionTrackingMode> sessionTrackingModes) {
        checkInitializationPhase();
        if (sessionTrackingModes != null
                && sessionTrackingModes.contains(SessionTrackingMode.SSL)
                && sessionTrackingModes.size() > 1) {
            throw new IllegalArgumentException(
                    "SSL session tracking mode cannot be combined with other modes");
        }
        this.sessionTrackingModes = sessionTrackingModes;
    }

    @Override
    public Set<SessionTrackingMode> getDefaultSessionTrackingModes() {
        return Set.of(SessionTrackingMode.COOKIE);
    }

    @Override
    public Set<SessionTrackingMode> getEffectiveSessionTrackingModes() {
        return sessionTrackingModes != null ? sessionTrackingModes : Set.of(SessionTrackingMode.COOKIE);
    }

    @Override
    public void addListener(String className) {
        checkInitializationPhase();
        try {
            Class<?> clazz = Thread.currentThread().getContextClassLoader().loadClass(className);
            validateListenerType(clazz);
            rejectSCLFromListener(clazz);
            EventListener instance = (EventListener) clazz.getDeclaredConstructor().newInstance();
            listeners.add(instance);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to load listener class: " + className, e);
        }
    }

    @Override
    public <T extends EventListener> void addListener(T t) {
        checkInitializationPhase();
        validateListenerType(t.getClass());
        rejectSCLFromListener(t.getClass());
        listeners.add(t);
    }

    @Override
    public void addListener(Class<? extends EventListener> listenerClass) {
        checkInitializationPhase();
        validateListenerType(listenerClass);
        rejectSCLFromListener(listenerClass);
        try {
            EventListener instance = listenerClass.getDeclaredConstructor().newInstance();
            listeners.add(instance);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to instantiate listener: " + listenerClass.getName(), e);
        }
    }

    private void rejectSCLFromListener(Class<?> clazz) {
        if (inListenerContext && jakarta.servlet.ServletContextListener.class.isAssignableFrom(clazz)) {
            throw new IllegalArgumentException(
                    "ServletContextListener cannot be added from a listener's contextInitialized()");
        }
    }

    private static void validateListenerType(Class<?> clazz) {
        if (!jakarta.servlet.ServletContextListener.class.isAssignableFrom(clazz)
                && !jakarta.servlet.ServletContextAttributeListener.class.isAssignableFrom(clazz)
                && !jakarta.servlet.ServletRequestListener.class.isAssignableFrom(clazz)
                && !jakarta.servlet.ServletRequestAttributeListener.class.isAssignableFrom(clazz)
                && !jakarta.servlet.http.HttpSessionListener.class.isAssignableFrom(clazz)
                && !jakarta.servlet.http.HttpSessionIdListener.class.isAssignableFrom(clazz)
                && !jakarta.servlet.http.HttpSessionAttributeListener.class.isAssignableFrom(clazz)
                && !jakarta.servlet.http.HttpSessionBindingListener.class.isAssignableFrom(clazz)) {
            throw new IllegalArgumentException(
                    clazz.getName() + " does not implement a valid servlet listener interface");
        }
    }

    @Override
    public <T extends EventListener> T createListener(Class<T> clazz) throws ServletException {
        checkInitializationPhase();
        validateListenerType(clazz);
        try {
            return clazz.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new ServletException("Failed to create listener: " + clazz.getName(), e);
        }
    }

    @Override
    public JspConfigDescriptor getJspConfigDescriptor() {
        return null;
    }

    @Override
    public ClassLoader getClassLoader() {
        return Thread.currentThread().getContextClassLoader();
    }

    @Override
    public void declareRoles(String... roleNames) {
        if (roleNames != null) {
            for (String role : roleNames) {
                if (role != null) {
                    declaredRoles.add(role);
                }
            }
        }
    }

    @Override
    public String getVirtualServerName() {
        return "quarkus";
    }

    private int sessionTimeout = 30;

    @Override
    public int getSessionTimeout() {
        return sessionTimeout;
    }

    @Override
    public void setSessionTimeout(int sessionTimeout) {
        checkInitializationPhase();
        this.sessionTimeout = sessionTimeout;
    }

    /**
     * Applies the deployment descriptor's {@code <session-config>}. This is the container
     * configuring itself, not application code calling {@link #setSessionTimeout(int)}, so it is
     * not subject to the initialization-phase check - it runs while the deployment is still being
     * assembled, before the context exists as far as the application is concerned.
     */
    public void setDeploymentSessionTimeout(int sessionTimeout) {
        this.sessionTimeout = sessionTimeout;
    }

    private String requestCharacterEncoding;
    private String responseCharacterEncoding;

    @Override
    public String getRequestCharacterEncoding() {
        return requestCharacterEncoding;
    }

    @Override
    public void setRequestCharacterEncoding(String encoding) {
        checkInitializationPhase();
        this.requestCharacterEncoding = encoding;
    }

    @Override
    public String getResponseCharacterEncoding() {
        return responseCharacterEncoding;
    }

    @Override
    public void setResponseCharacterEncoding(String encoding) {
        checkInitializationPhase();
        this.responseCharacterEncoding = encoding;
    }

    /**
     * Finalize programmatic registrations from SCIs and listeners.
     * Creates servlet/filter instances and adds them to the deployment.
     */
    private final java.util.Set<String> finalizedServlets = new java.util.HashSet<>();
    private final java.util.Set<String> finalizedFilters = new java.util.HashSet<>();

    public void finalizeRegistrations(ServletDeployment deployment) throws ServletException {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();

        // Finalize programmatically added servlets (skip already finalized)
        for (SimpleServletRegistration reg : servletRegistrations.values()) {
            if (finalizedServlets.contains(reg.getName())) {
                continue;
            }
            try {
                Servlet servlet = reg.getServletInstance();
                if (servlet == null) {
                    Class<?> clazz = reg.getServletClass();
                    if (clazz == null) {
                        clazz = cl.loadClass(reg.getClassName());
                    }
                    servlet = (Servlet) clazz.getDeclaredConstructor().newInstance();
                }

                List<String> mappings = new ArrayList<>(reg.getMappings());
                Map<String, String> initParams = new HashMap<>(reg.getInitParameters());

                ServletInfo info = new ServletInfo(reg.getName(), reg.getClassName(),
                        mappings, initParams, reg.getLoadOnStartup(), reg.isAsyncSupported());
                info.setServlet(servlet);

                deployment.addServlet(info);

                SimpleServletConfig config = new SimpleServletConfig(reg.getName(), this, initParams);
                servlet.init(config);
                info.setInitialized(true);
                finalizedServlets.add(reg.getName());
            } catch (ServletException e) {
                throw e;
            } catch (Exception e) {
                throw new ServletException("Failed to finalize servlet: " + reg.getName(), e);
            }
        }

        // Finalize programmatically added filters (skip already finalized)
        for (SimpleFilterRegistration reg : filterRegistrations.values()) {
            if (finalizedFilters.contains(reg.getName())) {
                continue;
            }
            try {
                Filter filter = reg.getFilterInstance();
                if (filter == null) {
                    Class<?> clazz = reg.getFilterClass();
                    if (clazz == null) {
                        clazz = cl.loadClass(reg.getClassName());
                    }
                    filter = (Filter) clazz.getDeclaredConstructor().newInstance();
                }

                List<String> patterns = new ArrayList<>(reg.getUrlPatternMappings());
                List<String> servletNames = new ArrayList<>(reg.getServletNameMappings());
                Map<String, String> initParams = new HashMap<>(reg.getInitParameters());

                java.util.Set<jakarta.servlet.DispatcherType> dispTypes = reg.getDispatcherTypes();

                FilterInfo info = new FilterInfo(reg.getName(), reg.getClassName(),
                        patterns, servletNames, dispTypes, initParams, 0);
                info.setFilter(filter);

                SimpleFilterConfig config = new SimpleFilterConfig(reg.getName(), this, initParams);
                filter.init(config);

                deployment.addFilter(info);
                finalizedFilters.add(reg.getName());

                log.debugf("Finalized programmatic filter: %s (%s) -> %s",
                        reg.getName(), reg.getClassName(), patterns);
            } catch (Exception e) {
                throw new ServletException("Failed to finalize filter: " + reg.getName(), e);
            }
        }
    }

    // ---- Inner classes for programmatic registration ----

    public static class SimpleServletRegistration implements ServletRegistration.Dynamic {

        private final String name;
        private final String className;
        private final Set<String> mappings = new LinkedHashSet<>();
        private final Map<String, String> initParams = new LinkedHashMap<>();
        private int loadOnStartup = -1;
        private boolean asyncSupported;
        private String runAsRole;
        private Servlet servletInstance;
        private Class<?> servletClass;
        private MultipartConfigElement multipartConfig;
        private VertxServletContext context;

        public SimpleServletRegistration(String name, String className) {
            this.name = name;
            this.className = className;
        }

        void setContext(VertxServletContext context) {
            this.context = context;
        }

        /** Create a read-only registration backed by an existing ServletInfo. */
        SimpleServletRegistration(ServletInfo info) {
            this.name = info.getName();
            this.className = info.getClassName();
            this.mappings.addAll(info.getMappings());
            this.initParams.putAll(info.getInitParams());
            this.asyncSupported = info.isAsyncSupported();
            this.loadOnStartup = info.getLoadOnStartup();
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getClassName() {
            return className;
        }

        @Override
        public Set<String> addMapping(String... urlPatterns) {
            Set<String> conflicts = new LinkedHashSet<>();
            for (String pattern : urlPatterns) {
                if (context != null && context.deployment != null) {
                    for (ServletInfo si : context.deployment.getServlets().values()) {
                        if (si.getMappings().contains(pattern)) {
                            conflicts.add(pattern);
                            break;
                        }
                    }
                }
                if (!conflicts.contains(pattern) && context != null) {
                    for (SimpleServletRegistration reg : context.servletRegistrations.values()) {
                        if (reg != this && reg.getMappings().contains(pattern)) {
                            conflicts.add(pattern);
                            break;
                        }
                    }
                }
            }
            if (conflicts.isEmpty()) {
                Collections.addAll(mappings, urlPatterns);
            }
            return conflicts;
        }

        @Override
        public Collection<String> getMappings() {
            return Collections.unmodifiableSet(mappings);
        }

        @Override
        public String getRunAsRole() {
            return runAsRole;
        }

        @Override
        public void setRunAsRole(String roleName) {
            this.runAsRole = roleName;
        }

        @Override
        public void setLoadOnStartup(int loadOnStartup) {
            this.loadOnStartup = loadOnStartup;
        }

        public int getLoadOnStartup() {
            return loadOnStartup;
        }

        @Override
        public void setAsyncSupported(boolean isAsyncSupported) {
            this.asyncSupported = isAsyncSupported;
        }

        public boolean isAsyncSupported() {
            return asyncSupported;
        }

        @Override
        public boolean setInitParameter(String name, String value) {
            return initParams.putIfAbsent(name, value) == null;
        }

        @Override
        public String getInitParameter(String name) {
            return initParams.get(name);
        }

        @Override
        public Set<String> setInitParameters(Map<String, String> initParameters) {
            Set<String> conflicts = new HashSet<>();
            for (Map.Entry<String, String> entry : initParameters.entrySet()) {
                if (initParams.containsKey(entry.getKey())) {
                    conflicts.add(entry.getKey());
                }
            }
            if (conflicts.isEmpty()) {
                initParams.putAll(initParameters);
            }
            return conflicts;
        }

        @Override
        public Map<String, String> getInitParameters() {
            return Collections.unmodifiableMap(initParams);
        }

        @Override
        public void setMultipartConfig(MultipartConfigElement multipartConfig) {
            this.multipartConfig = multipartConfig;
        }

        public MultipartConfigElement getMultipartConfig() {
            return multipartConfig;
        }

        @Override
        public Set<String> setServletSecurity(ServletSecurityElement constraint) {
            // Store but don't enforce security constraints for now
            return Collections.emptySet();
        }

        public void setServletInstance(Servlet servlet) {
            this.servletInstance = servlet;
        }

        public Servlet getServletInstance() {
            return servletInstance;
        }

        public void setServletClass(Class<?> servletClass) {
            this.servletClass = servletClass;
        }

        public Class<?> getServletClass() {
            return servletClass;
        }
    }

    public static class SimpleFilterRegistration implements FilterRegistration.Dynamic {

        private final String name;
        private final String className;
        private final List<String> urlPatterns = new ArrayList<>();
        private final List<String> servletNames = new ArrayList<>();
        private final Map<String, String> initParams = new LinkedHashMap<>();
        private java.util.Set<DispatcherType> dispatcherTypes;
        private boolean asyncSupported;
        private Filter filterInstance;
        private Class<?> filterClass;

        public SimpleFilterRegistration(String name, String className) {
            this.name = name;
            this.className = className;
        }

        /** Create a read-only registration backed by an existing FilterInfo. */
        SimpleFilterRegistration(FilterInfo info) {
            this.name = info.getName();
            this.className = info.getClassName();
            this.urlPatterns.addAll(info.getUrlPatterns());
            this.servletNames.addAll(info.getServletNames());
            this.initParams.putAll(info.getInitParams());
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getClassName() {
            return className;
        }

        @Override
        public void addMappingForServletNames(EnumSet<DispatcherType> dispatcherTypes,
                boolean isMatchAfter, String... servletNames) {
            Collections.addAll(this.servletNames, servletNames);
            if (dispatcherTypes != null && !dispatcherTypes.isEmpty()) {
                if (this.dispatcherTypes == null) {
                    this.dispatcherTypes = EnumSet.noneOf(DispatcherType.class);
                }
                this.dispatcherTypes.addAll(dispatcherTypes);
            }
        }

        @Override
        public Collection<String> getServletNameMappings() {
            return Collections.unmodifiableList(servletNames);
        }

        @Override
        public void addMappingForUrlPatterns(EnumSet<DispatcherType> dispatcherTypes,
                boolean isMatchAfter, String... urlPatterns) {
            Collections.addAll(this.urlPatterns, urlPatterns);
            if (dispatcherTypes != null && !dispatcherTypes.isEmpty()) {
                if (this.dispatcherTypes == null) {
                    this.dispatcherTypes = EnumSet.noneOf(DispatcherType.class);
                }
                this.dispatcherTypes.addAll(dispatcherTypes);
            }
        }

        public java.util.Set<DispatcherType> getDispatcherTypes() {
            return dispatcherTypes;
        }

        @Override
        public Collection<String> getUrlPatternMappings() {
            return Collections.unmodifiableList(urlPatterns);
        }

        @Override
        public void setAsyncSupported(boolean isAsyncSupported) {
            this.asyncSupported = isAsyncSupported;
        }

        public boolean isAsyncSupported() {
            return asyncSupported;
        }

        @Override
        public boolean setInitParameter(String name, String value) {
            return initParams.putIfAbsent(name, value) == null;
        }

        @Override
        public String getInitParameter(String name) {
            return initParams.get(name);
        }

        @Override
        public Set<String> setInitParameters(Map<String, String> initParameters) {
            Set<String> conflicts = new HashSet<>();
            for (Map.Entry<String, String> entry : initParameters.entrySet()) {
                if (initParams.containsKey(entry.getKey())) {
                    conflicts.add(entry.getKey());
                }
            }
            if (conflicts.isEmpty()) {
                initParams.putAll(initParameters);
            }
            return conflicts;
        }

        @Override
        public Map<String, String> getInitParameters() {
            return Collections.unmodifiableMap(initParams);
        }

        public void setFilterInstance(Filter filter) {
            this.filterInstance = filter;
        }

        public Filter getFilterInstance() {
            return filterInstance;
        }

        public void setFilterClass(Class<?> filterClass) {
            this.filterClass = filterClass;
        }

        public Class<?> getFilterClass() {
            return filterClass;
        }
    }

    public static class SimpleSessionCookieConfig implements SessionCookieConfig {

        /**
         * The owning context, consulted so that the setters can refuse changes once the context has
         * finished initializing - the spec requires IllegalStateException at that point.
         */
        private final VertxServletContext owner;

        private String name = "JSESSIONID";
        private String domain;
        private String path;
        private String comment;
        private boolean httpOnly = true;
        private boolean secure;
        private int maxAge = -1;
        private final Map<String, String> attributes = new LinkedHashMap<>();

        SimpleSessionCookieConfig(VertxServletContext owner) {
            this.owner = owner;
        }

        private void checkMutable() {
            if (owner != null) {
                owner.checkInitializationPhase();
            }
        }

        @Override
        public void setName(String name) {
            checkMutable();
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public void setDomain(String domain) {
            checkMutable();
            this.domain = domain;
        }

        @Override
        public String getDomain() {
            return domain;
        }

        @Override
        public void setPath(String path) {
            checkMutable();
            this.path = path;
        }

        @Override
        public String getPath() {
            return path;
        }

        @Override
        @SuppressWarnings("removal")
        public void setComment(String comment) {
            checkMutable();
            this.comment = comment;
        }

        @Override
        @SuppressWarnings("removal")
        public String getComment() {
            return comment;
        }

        @Override
        public void setHttpOnly(boolean httpOnly) {
            checkMutable();
            this.httpOnly = httpOnly;
        }

        @Override
        public boolean isHttpOnly() {
            return httpOnly;
        }

        @Override
        public void setSecure(boolean secure) {
            checkMutable();
            this.secure = secure;
        }

        @Override
        public boolean isSecure() {
            return secure;
        }

        @Override
        public void setMaxAge(int maxAge) {
            checkMutable();
            this.maxAge = maxAge;
        }

        @Override
        public int getMaxAge() {
            return maxAge;
        }

        @Override
        public void setAttribute(String name, String value) {
            checkMutable();
            attributes.put(name, value);
        }

        @Override
        public String getAttribute(String name) {
            return attributes.get(name);
        }

        @Override
        public Map<String, String> getAttributes() {
            return Collections.unmodifiableMap(attributes);
        }
    }
}
