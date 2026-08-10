package io.quarkiverse.servlet.tck;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EventListener;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamReader;

import jakarta.servlet.Filter;
import jakarta.servlet.ServletContainerInitializer;

import org.jboss.arquillian.container.spi.client.container.DeployableContainer;
import org.jboss.arquillian.container.spi.client.container.DeploymentException;
import org.jboss.arquillian.container.spi.client.container.LifecycleException;
import org.jboss.arquillian.container.spi.client.protocol.ProtocolDescription;
import org.jboss.arquillian.container.spi.client.protocol.metadata.HTTPContext;
import org.jboss.arquillian.container.spi.client.protocol.metadata.ProtocolMetaData;
import org.jboss.metadata.parser.servlet.WebMetaDataParser;
import org.jboss.metadata.parser.util.MetaDataElementParser;
import org.jboss.metadata.property.PropertyReplacers;
import org.jboss.metadata.web.spec.FilterMappingMetaData;
import org.jboss.metadata.web.spec.FilterMetaData;
import org.jboss.metadata.web.spec.FiltersMetaData;
import org.jboss.metadata.web.spec.ServletMappingMetaData;
import org.jboss.metadata.web.spec.ServletMetaData;
import org.jboss.metadata.web.spec.WebMetaData;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.exporter.ExplodedExporter;
import org.jboss.shrinkwrap.descriptor.api.Descriptor;

import io.quarkiverse.servlet.runtime.FilterInfo;
import io.quarkiverse.servlet.runtime.ServletDeployment;
import io.quarkiverse.servlet.runtime.ServletInfo;
import io.quarkiverse.servlet.runtime.ServletRecorder;
import io.quarkiverse.servlet.runtime.SimpleFilterConfig;
import io.quarkiverse.servlet.runtime.SimpleServletConfig;
import io.quarkiverse.servlet.runtime.VertxServletContext;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

public class ServletTckDeployableContainer implements DeployableContainer<ServletTckContainerConfig> {

    private Vertx vertx;
    private HttpServer server;
    private int port;
    private Path deploymentDir;
    private ClassLoader originalClassLoader;
    private VertxServletContext currentContext;

    @Override
    public Class<ServletTckContainerConfig> getConfigurationClass() {
        return ServletTckContainerConfig.class;
    }

    @Override
    public void setup(ServletTckContainerConfig configuration) {
    }

    @Override
    public ProtocolDescription getDefaultProtocol() {
        return new ProtocolDescription("Servlet 6.0");
    }

    @Override
    public void start() throws LifecycleException {
        vertx = Vertx.vertx();
    }

    @Override
    public void stop() throws LifecycleException {
        if (server != null) {
            server.close().toCompletionStage().toCompletableFuture().join();
            server = null;
        }
        if (vertx != null) {
            vertx.close().toCompletionStage().toCompletableFuture().join();
            vertx = null;
        }
    }

    @Override
    public ProtocolMetaData deploy(Archive<?> archive) throws DeploymentException {
        String archiveName = archive.getName();
        System.out.println("[SERVLET-TCK] Deploying: " + archiveName);

        try {
            deploymentDir = Files.createTempDirectory("servlet-tck-");
            archive.as(ExplodedExporter.class).exportExplodedInto(deploymentDir.toFile());

            String contextPath = "/" + archiveName.replace(".war", "");

            Path classesDir = deploymentDir.resolve("WEB-INF/classes");
            Path webXml = deploymentDir.resolve("WEB-INF/web.xml");

            List<String> classNames = new ArrayList<>();
            if (Files.exists(classesDir)) {
                try (var stream = Files.walk(classesDir)) {
                    stream.filter(p -> p.toString().endsWith(".class"))
                            .forEach(p -> {
                                String relative = classesDir.relativize(p).toString();
                                String className = relative.replace('/', '.').replace('\\', '.')
                                        .replace(".class", "");
                                classNames.add(className);
                            });
                }
            }

            originalClassLoader = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(
                    new WarClassLoader(classesDir, deploymentDir.resolve("WEB-INF/lib"),
                            originalClassLoader));

            Map<String, String> initParams = new HashMap<>();
            String displayName = null;
            Map<String, String> mimeMappings = new HashMap<>();

            if (Files.exists(webXml)) {
                WebMetaData webMetaData = parseWebXml(webXml);
                if (webMetaData.getContextParams() != null) {
                    webMetaData.getContextParams()
                            .forEach(p -> initParams.put(p.getParamName(), p.getParamValue()));
                }
                if (webMetaData.getDescriptionGroup() != null) {
                    displayName = webMetaData.getDescriptionGroup().getDisplayName();
                }
                if (webMetaData.getMimeMappings() != null) {
                    webMetaData.getMimeMappings()
                            .forEach(m -> mimeMappings.put(m.getExtension(), m.getMimeType()));
                }
            }

            VertxServletContext servletContext = new VertxServletContext(contextPath, initParams);
            if (displayName != null) {
                servletContext.setDisplayName(displayName);
            }
            servletContext.setMimeMappings(mimeMappings);
            servletContext.setDeploymentDir(deploymentDir);

            Path tempDir = Files.createTempDirectory("servlet-tck-tmp-");
            servletContext.setAttribute("jakarta.servlet.context.tempdir", tempDir.toFile());

            ServletDeployment deployment = new ServletDeployment(servletContext);
            servletContext.setDeployment(deployment);

            // Register default servlet for static file serving
            io.quarkiverse.servlet.runtime.DefaultServlet defaultServlet = new io.quarkiverse.servlet.runtime.DefaultServlet();
            ServletInfo defaultInfo = new ServletInfo("default", io.quarkiverse.servlet.runtime.DefaultServlet.class.getName(),
                    List.of("/"), Map.of(), -1, false);
            defaultInfo.setServlet(defaultServlet);
            defaultInfo.setInitialized(true);
            deployment.addServlet(defaultInfo);

            // Enable programmatic registration during initialization phase
            servletContext.setInitializing(true);

            if (Files.exists(webXml)) {
                WebMetaData webMetaData2 = parseWebXml(webXml);
                registerFromWebXml(webMetaData2, deployment, servletContext);
                parseErrorPages(webMetaData2, deployment);
                parseSessionConfig(webMetaData2, servletContext);
                parseLocaleEncodingMappings(webMetaData2, servletContext);
                parseWelcomeFiles(webMetaData2, deployment, servletContext);
                if (webMetaData2.getVersion() != null) {
                    try {
                        String ver = webMetaData2.getVersion();
                        String[] parts = ver.split("\\.");
                        servletContext.setEffectiveVersion(
                                Integer.parseInt(parts[0]),
                                parts.length > 1 ? Integer.parseInt(parts[1]) : 0);
                    } catch (Exception e) {
                        // ignore parse errors
                    }
                }
            }

            // Scan for @WebServlet, @WebFilter, @WebListener annotations
            for (String className : classNames) {
                try {
                    Class<?> clazz = Thread.currentThread().getContextClassLoader().loadClass(className);

                    // @WebServlet
                    jakarta.servlet.annotation.WebServlet wsAnn = clazz.getAnnotation(
                            jakarta.servlet.annotation.WebServlet.class);
                    if (wsAnn != null && jakarta.servlet.http.HttpServlet.class.isAssignableFrom(clazz)) {
                        String name = wsAnn.name().isEmpty() ? className : wsAnn.name();
                        String[] urlPatterns = wsAnn.urlPatterns().length > 0 ? wsAnn.urlPatterns() : wsAnn.value();
                        if (urlPatterns.length > 0 && !deployment.getServlets().containsKey(name)) {
                            jakarta.servlet.Servlet instance = (jakarta.servlet.Servlet) clazz
                                    .getDeclaredConstructor().newInstance();
                            Map<String, String> annInitParams = new HashMap<>();
                            for (jakarta.servlet.annotation.WebInitParam p : wsAnn.initParams()) {
                                annInitParams.put(p.name(), p.value());
                            }
                            ServletInfo info = new ServletInfo(name, className,
                                    List.of(urlPatterns), annInitParams, wsAnn.loadOnStartup(),
                                    wsAnn.asyncSupported());
                            info.setServlet(instance);
                            SimpleServletConfig config = new SimpleServletConfig(name, servletContext, annInitParams);
                            try {
                                instance.init(config);
                                info.setInitialized(true);
                            } catch (Exception e) {
                                info.setInitFailed(true);
                                info.setInitException(e);
                            }
                            deployment.addServlet(info);
                            System.out.println(
                                    "[SERVLET-TCK]   @WebServlet: " + name + " -> " + List.of(urlPatterns));
                        }
                    }

                    // @WebFilter
                    jakarta.servlet.annotation.WebFilter wfAnn = clazz.getAnnotation(
                            jakarta.servlet.annotation.WebFilter.class);
                    if (wfAnn != null && jakarta.servlet.Filter.class.isAssignableFrom(clazz)) {
                        String name = wfAnn.filterName().isEmpty() ? className : wfAnn.filterName();
                        String[] urlPatterns = wfAnn.urlPatterns().length > 0 ? wfAnn.urlPatterns() : wfAnn.value();
                        String[] annServletNames = wfAnn.servletNames();
                        if (urlPatterns.length == 0 && annServletNames.length == 0) {
                            urlPatterns = new String[] { "/*" };
                        }
                        jakarta.servlet.Filter filterInstance = (jakarta.servlet.Filter) clazz
                                .getDeclaredConstructor().newInstance();
                        Map<String, String> annInitParams = new HashMap<>();
                        for (jakarta.servlet.annotation.WebInitParam p : wfAnn.initParams()) {
                            annInitParams.put(p.name(), p.value());
                        }
                        java.util.Set<jakarta.servlet.DispatcherType> annDispTypes = null;
                        if (wfAnn.dispatcherTypes().length > 0) {
                            annDispTypes = java.util.EnumSet.noneOf(jakarta.servlet.DispatcherType.class);
                            for (jakarta.servlet.DispatcherType dt : wfAnn.dispatcherTypes()) {
                                annDispTypes.add(dt);
                            }
                        }
                        FilterInfo fInfo = new FilterInfo(name, className, List.of(urlPatterns),
                                List.of(annServletNames), annDispTypes, annInitParams, 0);
                        fInfo.setFilter(filterInstance);
                        SimpleFilterConfig fConfig = new SimpleFilterConfig(name, servletContext, annInitParams);
                        filterInstance.init(fConfig);
                        deployment.addFilter(fInfo);
                        System.out.println(
                                "[SERVLET-TCK]   @WebFilter: " + name + " -> " + List.of(urlPatterns)
                                        + " servletNames=" + List.of(annServletNames));
                    }

                    // @WebListener
                    jakarta.servlet.annotation.WebListener wlAnn = clazz.getAnnotation(
                            jakarta.servlet.annotation.WebListener.class);
                    if (wlAnn != null && java.util.EventListener.class.isAssignableFrom(clazz)) {
                        EventListener listener = (EventListener) clazz.getDeclaredConstructor().newInstance();
                        servletContext.addRegisteredListener(listener);
                        System.out.println("[SERVLET-TCK]   @WebListener: " + className);
                    }
                } catch (ClassNotFoundException e) {
                    // Skip classes that can't be loaded
                } catch (Exception e) {
                    System.out.println(
                            "[SERVLET-TCK]   Annotation scan failed for " + className + ": " + e.getMessage());
                }
            }

            // Remember listener count before SCIs/TLDs add more
            int preScisListenerCount = servletContext.getListeners().size();

            // Scan for and invoke ServletContainerInitializers from WEB-INF/lib JARs
            scanAndInvokeScis(deploymentDir, servletContext);

            // Scan TLD files in WEB-INF/lib for listener declarations (after SCIs)
            scanTldListeners(deploymentDir, servletContext);

            // Save context for undeploy
            this.currentContext = servletContext;

            // Finalize any programmatically added servlets/filters from SCIs
            servletContext.finalizeRegistrations(deployment);

            // Notify context listeners (still in initialization phase so they can register)
            jakarta.servlet.ServletContextEvent sce = new jakarta.servlet.ServletContextEvent(servletContext);
            List<EventListener> allListeners = new ArrayList<>(servletContext.getListeners());
            for (int i = 0; i < allListeners.size(); i++) {
                EventListener l = allListeners.get(i);
                if (l instanceof jakarta.servlet.ServletContextListener scl) {
                    boolean isProgrammatic = (i >= preScisListenerCount);
                    if (isProgrammatic) {
                        servletContext.setInitializing(false);
                        servletContext.setRestrictedContext(true);
                    }
                    servletContext.setInListenerContext(true);
                    try {
                        scl.contextInitialized(sce);
                    } catch (Exception e) {
                        System.err.println("Listener contextInitialized failed: "
                                + l.getClass().getName() + " - " + e.getMessage());
                    }
                    servletContext.setInListenerContext(false);
                    if (isProgrammatic) {
                        servletContext.setRestrictedContext(false);
                        servletContext.setInitializing(true);
                    }
                }
            }

            // End initialization phase
            servletContext.setInitializing(false);

            // Finalize any additional registrations from web.xml listeners
            servletContext.finalizeRegistrations(deployment);

            deployment.initServlets();

            Handler<RoutingContext> handler = createHandler(deployment);

            Router router = Router.router(vertx);
            router.route(contextPath + "/*").handler(handler);
            router.route(contextPath).handler(handler);

            server = vertx.createHttpServer()
                    .requestHandler(router)
                    .listen(0)
                    .toCompletionStage()
                    .toCompletableFuture()
                    .join();

            port = server.actualPort();
            System.out.println("[SERVLET-TCK] Server started on port " + port + " with context " + contextPath);

            ProtocolMetaData metadata = new ProtocolMetaData();
            HTTPContext httpContext = new HTTPContext("localhost", port);
            httpContext.add(new org.jboss.arquillian.container.spi.client.protocol.metadata.Servlet("default",
                    contextPath));
            metadata.addContext(httpContext);
            return metadata;

        } catch (Exception e) {
            System.err.println("[SERVLET-TCK] Deploy failed: " + e.getMessage());
            e.printStackTrace();
            throw new DeploymentException("Failed to deploy " + archiveName, e);
        }
    }

    @Override
    public void undeploy(Archive<?> archive) throws DeploymentException {
        // Notify context listeners (in reverse order)
        if (currentContext != null) {
            jakarta.servlet.ServletContextEvent sce = new jakarta.servlet.ServletContextEvent(currentContext);
            var listeners = currentContext.getListeners();
            for (int i = listeners.size() - 1; i >= 0; i--) {
                if (listeners.get(i) instanceof jakarta.servlet.ServletContextListener scl) {
                    scl.contextDestroyed(sce);
                }
            }
            currentContext = null;
        }
        if (server != null) {
            server.close().toCompletionStage().toCompletableFuture().join();
            server = null;
        }
        if (originalClassLoader != null) {
            Thread.currentThread().setContextClassLoader(originalClassLoader);
            originalClassLoader = null;
        }
        if (deploymentDir != null) {
            try {
                deleteDir(deploymentDir);
            } catch (Exception e) {
                System.err.println("[SERVLET-TCK] Cleanup failed: " + e.getMessage());
            }
            deploymentDir = null;
        }
    }

    @Override
    public void deploy(Descriptor descriptor) throws DeploymentException {
    }

    @Override
    public void undeploy(Descriptor descriptor) throws DeploymentException {
    }

    private WebMetaData parseWebXml(Path webXml) throws Exception {
        try (InputStream is = Files.newInputStream(webXml)) {
            XMLInputFactory factory = XMLInputFactory.newInstance();
            factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
            XMLStreamReader reader = factory.createXMLStreamReader(is);
            return WebMetaDataParser.parse(reader,
                    new MetaDataElementParser.DTDInfo(), PropertyReplacers.noop());
        }
    }

    private void registerFromWebXml(WebMetaData webMetaData, ServletDeployment deployment,
            VertxServletContext servletContext) throws Exception {
        Map<String, List<String>> servletMappings = new HashMap<>();
        if (webMetaData.getServletMappings() != null) {
            for (ServletMappingMetaData mapping : webMetaData.getServletMappings()) {
                servletMappings.computeIfAbsent(mapping.getServletName(), k -> new ArrayList<>())
                        .addAll(mapping.getUrlPatterns());
            }
        }

        if (webMetaData.getServlets() != null) {
            for (ServletMetaData servlet : webMetaData.getServlets()) {
                String className = servlet.getServletClass();
                if (className == null)
                    continue;

                Class<?> clazz = Thread.currentThread().getContextClassLoader().loadClass(className);
                jakarta.servlet.Servlet instance = (jakarta.servlet.Servlet) clazz.getDeclaredConstructor()
                        .newInstance();

                List<String> mappings = servletMappings.getOrDefault(servlet.getServletName(), List.of());
                Map<String, String> initParams = new HashMap<>();
                if (servlet.getInitParam() != null) {
                    servlet.getInitParam()
                            .forEach(p -> initParams.put(p.getParamName(), p.getParamValue()));
                }

                int loadOnStartup = servlet.getLoadOnStartupInt();
                ServletInfo info = new ServletInfo(servlet.getServletName(), className,
                        mappings, initParams, loadOnStartup, servlet.isAsyncSupported());
                info.setServlet(instance);

                deployment.addServlet(info);

                try {
                    SimpleServletConfig config = new SimpleServletConfig(
                            servlet.getServletName(), servletContext, initParams);
                    instance.init(config);
                    info.setInitialized(true);
                } catch (Exception e) {
                    info.setInitFailed(true);
                    info.setInitException(e);
                    if (e instanceof jakarta.servlet.UnavailableException) {
                        info.setPermanentlyUnavailable(true);
                    }
                    System.out.println("[SERVLET-TCK]   Servlet init failed (non-fatal): "
                            + servlet.getServletName() + " - " + e.getMessage());
                }
                System.out.println("[SERVLET-TCK]   Servlet: " + servlet.getServletName()
                        + " (" + className + ") -> " + mappings);
            }
        }

        // Collect per-mapping entries for each filter
        Map<String, List<FilterMappingMetaData>> filterMappingsByName = new java.util.LinkedHashMap<>();
        if (webMetaData.getFilterMappings() != null) {
            for (FilterMappingMetaData mapping : webMetaData.getFilterMappings()) {
                filterMappingsByName.computeIfAbsent(mapping.getFilterName(), k -> new ArrayList<>())
                        .add(mapping);
            }
        }

        FiltersMetaData filters = webMetaData.getFilters();
        if (filters != null) {
            for (FilterMetaData filter : filters) {
                String className = filter.getFilterClass();
                if (className == null)
                    continue;

                Class<?> clazz = Thread.currentThread().getContextClassLoader().loadClass(className);
                Filter instance = (Filter) clazz.getDeclaredConstructor().newInstance();

                Map<String, String> initParams = new HashMap<>();
                if (filter.getInitParam() != null) {
                    filter.getInitParam()
                            .forEach(p -> initParams.put(p.getParamName(), p.getParamValue()));
                }

                List<FilterMappingMetaData> mappings = filterMappingsByName.get(filter.getFilterName());
                FilterInfo info;
                if (mappings != null && !mappings.isEmpty()) {
                    // Create first mapping as the constructor mapping
                    FilterMappingMetaData first = mappings.get(0);
                    List<String> firstPatterns = first.getUrlPatterns() != null
                            ? new ArrayList<>(first.getUrlPatterns())
                            : List.of();
                    List<String> firstServletNames = first.getServletNames() != null
                            ? new ArrayList<>(first.getServletNames())
                            : List.of();
                    java.util.Set<jakarta.servlet.DispatcherType> firstDispTypes = parseDispatchers(first);

                    info = new FilterInfo(filter.getFilterName(), className,
                            firstPatterns, firstServletNames, firstDispTypes, initParams, 0);

                    // Add remaining mappings
                    for (int i = 1; i < mappings.size(); i++) {
                        FilterMappingMetaData m = mappings.get(i);
                        List<String> mPatterns = m.getUrlPatterns() != null
                                ? new ArrayList<>(m.getUrlPatterns())
                                : List.of();
                        List<String> mServletNames = m.getServletNames() != null
                                ? new ArrayList<>(m.getServletNames())
                                : List.of();
                        info.addMapping(mPatterns, mServletNames, parseDispatchers(m));
                    }
                } else {
                    info = new FilterInfo(filter.getFilterName(), className,
                            List.of("/*"), List.of(), null, initParams, 0);
                }

                info.setFilter(instance);

                SimpleFilterConfig config = new SimpleFilterConfig(
                        filter.getFilterName(), servletContext, initParams);
                instance.init(config);

                deployment.addFilter(info);
                System.out.println("[SERVLET-TCK]   Filter: " + filter.getFilterName()
                        + " (" + className + ") -> " + info.getUrlPatterns()
                        + " servletNames=" + info.getServletNames());
            }
        }

        // Register listeners
        if (webMetaData.getListeners() != null) {
            for (var listener : webMetaData.getListeners()) {
                String className = listener.getListenerClass();
                if (className != null) {
                    try {
                        Class<?> clazz = Thread.currentThread().getContextClassLoader().loadClass(className);
                        EventListener instance = (EventListener) clazz.getDeclaredConstructor().newInstance();
                        servletContext.addRegisteredListener(instance);
                        System.out.println("[SERVLET-TCK]   Listener: " + className);
                    } catch (Exception e) {
                        System.out.println(
                                "[SERVLET-TCK]   Listener failed: " + className + " - " + e.getMessage());
                    }
                }
            }
        }
    }

    private java.util.Set<jakarta.servlet.DispatcherType> parseDispatchers(FilterMappingMetaData mapping) {
        if (mapping.getDispatchers() == null || mapping.getDispatchers().isEmpty()) {
            return null;
        }
        java.util.Set<jakarta.servlet.DispatcherType> types = java.util.EnumSet
                .noneOf(jakarta.servlet.DispatcherType.class);
        for (var d : mapping.getDispatchers()) {
            try {
                types.add(jakarta.servlet.DispatcherType.valueOf(d.name()));
            } catch (Exception e) {
                // skip
            }
        }
        return types;
    }

    private void parseErrorPages(WebMetaData webMetaData, ServletDeployment deployment) {
        if (webMetaData.getErrorPages() != null) {
            for (var errorPage : webMetaData.getErrorPages()) {
                if (errorPage.getErrorCode() != null) {
                    try {
                        int code = Integer.parseInt(errorPage.getErrorCode());
                        deployment.addErrorPage(code, errorPage.getLocation());
                        System.out.println("[SERVLET-TCK]   Error page: " + code + " -> "
                                + errorPage.getLocation());
                    } catch (NumberFormatException e) {
                        // skip
                    }
                }
                if (errorPage.getExceptionType() != null) {
                    deployment.addExceptionErrorPage(errorPage.getExceptionType(), errorPage.getLocation());
                    System.out.println("[SERVLET-TCK]   Error page (exception): "
                            + errorPage.getExceptionType() + " -> " + errorPage.getLocation());
                }
            }
        }
    }

    private void parseSessionConfig(WebMetaData webMetaData, VertxServletContext servletContext) {
        if (webMetaData.getSessionConfig() != null) {
            var sessionConfig = webMetaData.getSessionConfig();
            if (sessionConfig.getSessionTimeoutSet()) {
                System.out.println("[SERVLET-TCK]   Session timeout: " + sessionConfig.getSessionTimeout() + " minutes");
                servletContext.setSessionTimeout(sessionConfig.getSessionTimeout());
            }
            if (sessionConfig.getCookieConfig() != null) {
                var cookieConfig = sessionConfig.getCookieConfig();
                var scc = servletContext.getSessionCookieConfig();
                if (cookieConfig.getName() != null)
                    scc.setName(cookieConfig.getName());
                if (cookieConfig.getDomain() != null)
                    scc.setDomain(cookieConfig.getDomain());
                if (cookieConfig.getPath() != null)
                    scc.setPath(cookieConfig.getPath());
                if (cookieConfig.getComment() != null)
                    scc.setComment(cookieConfig.getComment());
                if (cookieConfig.getHttpOnlySet()) {
                    scc.setHttpOnly(cookieConfig.getHttpOnly());
                }
                if (cookieConfig.getSecureSet()) {
                    scc.setSecure(cookieConfig.getSecure());
                }
                scc.setMaxAge(cookieConfig.getMaxAge());
            }
        }
    }

    private Handler<RoutingContext> createHandler(ServletDeployment deployment) {
        return rc -> {
            rc.request().resume();
            rc.request().body().onComplete(ar -> {
                io.vertx.core.buffer.Buffer body = ar.succeeded() ? ar.result() : null;
                Thread.startVirtualThread(() -> {
                    try {
                        ServletRecorder.executeServletDirect(rc, deployment, body);
                    } catch (Exception e) {
                        if (!rc.response().ended()) {
                            rc.response().setStatusCode(500);
                            rc.response().end("Internal Server Error: " + e.getMessage());
                        }
                    }
                });
            });
        };
    }

    private void parseWelcomeFiles(WebMetaData webMetaData, ServletDeployment deployment,
            VertxServletContext servletContext) {
        if (webMetaData.getWelcomeFileList() != null
                && webMetaData.getWelcomeFileList().getWelcomeFiles() != null) {
            List<String> welcomeFiles = webMetaData.getWelcomeFileList().getWelcomeFiles();
            deployment.setWelcomeFiles(welcomeFiles);
            System.out.println("[SERVLET-TCK]   Welcome files: " + welcomeFiles);
        }
    }

    private void parseLocaleEncodingMappings(WebMetaData webMetaData, VertxServletContext servletContext) {
        if (webMetaData.getLocalEncodings() != null
                && webMetaData.getLocalEncodings().getMappings() != null) {
            Map<String, String> mappings = new HashMap<>();
            for (var entry : webMetaData.getLocalEncodings().getMappings()) {
                mappings.put(entry.getLocale(), entry.getEncoding());
            }
            if (!mappings.isEmpty()) {
                servletContext.setLocaleEncodingMappings(mappings);
                System.out.println("[SERVLET-TCK]   Locale-encoding mappings: " + mappings);
            }
        }
    }

    private void scanTldListeners(Path deploymentDir, VertxServletContext servletContext) {
        Path libDir = deploymentDir.resolve("WEB-INF/lib");
        if (!Files.exists(libDir)) {
            return;
        }
        try (Stream<Path> entries = Files.list(libDir)) {
            entries.forEach(jarPath -> {
                if (Files.isDirectory(jarPath)) {
                    // Exploded JAR - scan for TLD files
                    try (Stream<Path> tldFiles = Files.walk(jarPath)) {
                        tldFiles.filter(p -> p.toString().endsWith(".tld")).forEach(tldPath -> {
                            try (InputStream is = Files.newInputStream(tldPath)) {
                                parseTldListeners(is, tldPath.getFileName().toString(), servletContext);
                            } catch (Exception e) {
                                System.out.println("[SERVLET-TCK]   TLD scan failed: "
                                        + tldPath + " - " + e.getMessage());
                            }
                        });
                    } catch (IOException e) {
                        // ignore
                    }
                    return;
                }
                if (!jarPath.toString().endsWith(".jar")) {
                    return;
                }
                try (JarFile jarFile = new JarFile(jarPath.toFile())) {
                    jarFile.stream()
                            .filter(e -> e.getName().endsWith(".tld"))
                            .forEach(tldEntry -> {
                                try (InputStream is = jarFile.getInputStream(tldEntry)) {
                                    parseTldListeners(is, tldEntry.getName(), servletContext);
                                } catch (Exception e) {
                                    System.out.println("[SERVLET-TCK]   TLD scan failed: "
                                            + tldEntry.getName() + " - " + e.getMessage());
                                }
                            });
                } catch (IOException e) {
                    System.out.println("[SERVLET-TCK]   Failed to scan JAR for TLDs: "
                            + jarPath.getFileName() + " - " + e.getMessage());
                }
            });
        } catch (IOException e) {
            System.out.println("[SERVLET-TCK]   Failed to list WEB-INF/lib: " + e.getMessage());
        }
    }

    private void parseTldListeners(InputStream is, String source,
            VertxServletContext servletContext) throws Exception {
        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, false);
        var reader = factory.createXMLStreamReader(is);
        boolean inListener = false;
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == javax.xml.stream.XMLStreamConstants.START_ELEMENT
                    && "listener-class".equals(reader.getLocalName())) {
                inListener = true;
            } else if (event == javax.xml.stream.XMLStreamConstants.CHARACTERS && inListener) {
                String className = reader.getText().trim();
                if (!className.isEmpty()) {
                    try {
                        Class<?> clazz = Thread.currentThread()
                                .getContextClassLoader().loadClass(className);
                        EventListener instance = (EventListener) clazz
                                .getDeclaredConstructor().newInstance();
                        servletContext.addRegisteredListener(instance);
                        System.out.println("[SERVLET-TCK]   TLD Listener: " + className
                                + " (from " + source + ")");
                    } catch (Exception e) {
                        System.out.println("[SERVLET-TCK]   TLD Listener failed: "
                                + className + " - " + e.getMessage());
                    }
                }
                inListener = false;
            } else if (event == javax.xml.stream.XMLStreamConstants.END_ELEMENT) {
                inListener = false;
            }
        }
    }

    private void scanAndInvokeScis(Path deploymentDir, VertxServletContext servletContext) {
        // First scan WEB-INF/classes for SCI service files
        Path classesService = deploymentDir
                .resolve("WEB-INF/classes/META-INF/services/jakarta.servlet.ServletContainerInitializer");
        if (Files.exists(classesService)) {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(Files.newInputStream(classesService)))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    try {
                        Class<?> sciClass = Thread.currentThread()
                                .getContextClassLoader().loadClass(line);
                        ServletContainerInitializer sci = (ServletContainerInitializer) sciClass
                                .getDeclaredConstructor().newInstance();
                        System.out.println("[SERVLET-TCK]   SCI: " + line + " (from WEB-INF/classes)");
                        sci.onStartup(null, servletContext);
                    } catch (Exception e) {
                        System.out.println("[SERVLET-TCK]   SCI failed: " + line + " - " + e.getMessage());
                    }
                }
            } catch (IOException e) {
                System.out.println("[SERVLET-TCK]   Failed to read SCI service file: " + e.getMessage());
            }
        }

        // Also scan exploded JAR directories in WEB-INF/lib
        Path libDir = deploymentDir.resolve("WEB-INF/lib");
        if (!Files.exists(libDir)) {
            return;
        }

        try (Stream<Path> entries = Files.list(libDir)) {
            entries.forEach(entryPath -> {
                Path sciFile;
                if (Files.isDirectory(entryPath)) {
                    sciFile = entryPath
                            .resolve("META-INF/services/jakarta.servlet.ServletContainerInitializer");
                } else if (entryPath.toString().endsWith(".jar")) {
                    try (JarFile jarFile = new JarFile(entryPath.toFile())) {
                        JarEntry sciEntry = jarFile
                                .getJarEntry("META-INF/services/jakarta.servlet.ServletContainerInitializer");
                        if (sciEntry != null) {
                            loadScisFromReader(new BufferedReader(
                                    new InputStreamReader(jarFile.getInputStream(sciEntry))),
                                    entryPath.getFileName().toString(), servletContext);
                        }
                    } catch (IOException e) {
                        System.out.println("[SERVLET-TCK]   Failed to scan JAR: "
                                + entryPath.getFileName() + " - " + e.getMessage());
                    }
                    return;
                } else {
                    return;
                }
                if (Files.exists(sciFile)) {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(Files.newInputStream(sciFile)))) {
                        loadScisFromReader(reader, entryPath.getFileName().toString(), servletContext);
                    } catch (IOException e) {
                        System.out.println("[SERVLET-TCK]   Failed to read SCI from dir: "
                                + entryPath.getFileName() + " - " + e.getMessage());
                    }
                }
            });
        } catch (IOException e) {
            System.out.println("[SERVLET-TCK]   Failed to list WEB-INF/lib: " + e.getMessage());
        }
    }

    private void loadScisFromReader(BufferedReader reader, String source,
            VertxServletContext servletContext) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            try {
                Class<?> sciClass = Thread.currentThread()
                        .getContextClassLoader().loadClass(line);
                ServletContainerInitializer sci = (ServletContainerInitializer) sciClass
                        .getDeclaredConstructor().newInstance();
                System.out.println("[SERVLET-TCK]   SCI: " + line + " (from " + source + ")");
                sci.onStartup(null, servletContext);
            } catch (Exception e) {
                System.out.println("[SERVLET-TCK]   SCI failed: " + line + " - " + e.getMessage());
            }
        }
    }

    private void deleteDir(Path dir) throws Exception {
        if (Files.exists(dir)) {
            try (var stream = Files.walk(dir)) {
                stream.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> {
                            try {
                                Files.delete(p);
                            } catch (Exception e) {
                                // ignore
                            }
                        });
            }
        }
    }
}
