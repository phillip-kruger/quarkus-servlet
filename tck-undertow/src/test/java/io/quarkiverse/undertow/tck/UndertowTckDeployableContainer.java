package io.quarkiverse.undertow.tck;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.EventListener;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletContainerInitializer;
import jakarta.servlet.annotation.HandlesTypes;

import org.jboss.arquillian.container.spi.client.container.DeployableContainer;
import org.jboss.arquillian.container.spi.client.container.DeploymentException;
import org.jboss.arquillian.container.spi.client.container.LifecycleException;
import org.jboss.arquillian.container.spi.client.protocol.ProtocolDescription;
import org.jboss.arquillian.container.spi.client.protocol.metadata.HTTPContext;
import org.jboss.arquillian.container.spi.client.protocol.metadata.ProtocolMetaData;
import org.jboss.metadata.parser.servlet.WebFragmentMetaDataParser;
import org.jboss.metadata.parser.servlet.WebMetaDataParser;
import org.jboss.metadata.parser.util.MetaDataElementParser;
import org.jboss.metadata.property.PropertyReplacers;
import org.jboss.metadata.web.spec.FilterMappingMetaData;
import org.jboss.metadata.web.spec.FilterMetaData;
import org.jboss.metadata.web.spec.FiltersMetaData;
import org.jboss.metadata.web.spec.LoginConfigMetaData;
import org.jboss.metadata.web.spec.SecurityConstraintMetaData;
import org.jboss.metadata.web.spec.ServletMappingMetaData;
import org.jboss.metadata.web.spec.ServletMetaData;
import org.jboss.metadata.web.spec.WebCommonMetaData;
import org.jboss.metadata.web.spec.WebFragmentMetaData;
import org.jboss.metadata.web.spec.WebMetaData;
import org.jboss.metadata.web.spec.WebResourceCollectionMetaData;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.exporter.ExplodedExporter;
import org.jboss.shrinkwrap.descriptor.api.Descriptor;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.undertow.httpcore.BufferAllocator;
import io.undertow.httpcore.UndertowOptionMap;
import io.undertow.server.DefaultExchangeHandler;
import io.undertow.server.HttpHandler;
import io.undertow.server.handlers.CanonicalPathHandler;
import io.undertow.server.handlers.PathHandler;
import io.undertow.server.handlers.resource.PathResourceManager;
import io.undertow.servlet.Servlets;
import io.undertow.servlet.api.DeploymentInfo;
import io.undertow.servlet.api.DeploymentManager;
import io.undertow.servlet.api.ErrorPage;
import io.undertow.servlet.api.FilterInfo;
import io.undertow.servlet.api.HttpMethodSecurityInfo;
import io.undertow.servlet.api.ListenerInfo;
import io.undertow.servlet.api.LoginConfig;
import io.undertow.servlet.api.MimeMapping;
import io.undertow.servlet.api.SecurityConstraint;
import io.undertow.servlet.api.SecurityInfo;
import io.undertow.servlet.api.ServletContainer;
import io.undertow.servlet.api.ServletContainerInitializerInfo;
import io.undertow.servlet.api.ServletInfo;
import io.undertow.servlet.api.ServletSecurityInfo;
import io.undertow.servlet.api.ServletSessionConfig;
import io.undertow.servlet.api.TransportGuaranteeType;
import io.undertow.servlet.api.WebResourceCollection;
import io.undertow.servlet.handlers.DefaultServlet;
import io.undertow.servlet.util.DefaultClassIntrospector;
import io.undertow.vertx.VertxHttpExchange;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;

/**
 * Arquillian container that runs the Jakarta Servlet TCK against the Undertow servlet engine that
 * quarkus-undertow ships ({@code quarkus-http-servlet}), bridged to Vert.x the same way the
 * extension does.
 * <p>
 * The harness parses {@code web.xml}, web fragments and annotations and turns them into an Undertow
 * {@link DeploymentInfo}; from there the Undertow engine owns the whole servlet lifecycle - SCIs,
 * listener callbacks, servlet/filter init, request dispatch, sessions and security enforcement.
 * That is precisely the code quarkus-undertow delegates to, which is what makes this the fair
 * baseline: what the engine cannot do here, quarkus-undertow cannot do either.
 */
public class UndertowTckDeployableContainer implements DeployableContainer<UndertowTckContainerConfig> {

    private static final String LOG = "[UNDERTOW-TCK] ";
    private static final AtomicInteger DEPLOY_COUNTER = new AtomicInteger();

    private Vertx vertx;
    private HttpServer server;
    private ExecutorService executor;
    private BufferAllocator allocator;
    private int port;

    private Path deploymentDir;
    private ClassLoader originalClassLoader;
    private DeploymentManager manager;

    @Override
    public Class<UndertowTckContainerConfig> getConfigurationClass() {
        return UndertowTckContainerConfig.class;
    }

    @Override
    public void setup(UndertowTckContainerConfig configuration) {
    }

    @Override
    public ProtocolDescription getDefaultProtocol() {
        return new ProtocolDescription("Servlet 6.0");
    }

    @Override
    public void start() throws LifecycleException {
        vertx = Vertx.vertx();
        executor = Executors.newCachedThreadPool();
        allocator = new NettyBufferAllocator();
    }

    @Override
    public void stop() throws LifecycleException {
        closeServer();
        if (vertx != null) {
            vertx.close().toCompletionStage().toCompletableFuture().join();
            vertx = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    @Override
    public ProtocolMetaData deploy(Archive<?> archive) throws DeploymentException {
        String archiveName = archive.getName();
        System.out.println(LOG + "Deploying: " + archiveName);

        try {
            deploymentDir = Files.createTempDirectory("undertow-tck-");
            archive.as(ExplodedExporter.class).exportExplodedInto(deploymentDir.toFile());

            String contextPath = "/" + archiveName.replace(".war", "");
            Path classesDir = deploymentDir.resolve("WEB-INF/classes");
            Path webXml = deploymentDir.resolve("WEB-INF/web.xml");

            originalClassLoader = Thread.currentThread().getContextClassLoader();
            WarClassLoader warClassLoader = new WarClassLoader(classesDir,
                    deploymentDir.resolve("WEB-INF/lib"), originalClassLoader);
            Thread.currentThread().setContextClassLoader(warClassLoader);

            DeploymentInfo di = new DeploymentInfo()
                    .setClassLoader(warClassLoader)
                    .setDeploymentName(archiveName + "-" + DEPLOY_COUNTER.incrementAndGet())
                    .setContextPath(contextPath)
                    .setClassIntrospecter(DefaultClassIntrospector.INSTANCE)
                    .setResourceManager(new PathResourceManager(deploymentDir, 1024))
                    .setEagerFilterInit(true);

            Path tempDir = Files.createTempDirectory("undertow-tck-tmp-");
            di.setTempDir(tempDir.toFile());
            di.addServletContextAttribute("jakarta.servlet.context.tempdir", tempDir.toFile());

            // Undertow's own default servlet handles static content and welcome files.
            di.addServlet(new ServletInfo("default", DefaultServlet.class).setAsyncSupported(true));

            List<String> classNames = collectClassNames(classesDir);

            // web.xml first for context params/version, then fragments, then class-level annotations.
            WebMetaData webMetaData = Files.exists(webXml) ? parseWebXml(webXml) : null;
            if (webMetaData != null) {
                if (webMetaData.getContextParams() != null) {
                    webMetaData.getContextParams()
                            .forEach(p -> di.addInitParameter(p.getParamName(), p.getParamValue()));
                }
                registerFromWebXml(webMetaData, di, warClassLoader);
                registerSecurity(webMetaData, di);
                configureSessions(webMetaData, di);
                configureErrorPages(webMetaData, di);
                configureMimeMappings(webMetaData, di);
                configureWelcomeFiles(webMetaData, di);
            }

            registerWebFragments(deploymentDir, di, warClassLoader);
            scanAnnotations(classNames, di, warClassLoader);
            registerScis(deploymentDir, classesDir, classNames, di, warClassLoader);
            scanTldListeners(deploymentDir, di, warClassLoader);

            if (di.getWelcomePages().isEmpty()) {
                di.addWelcomePages("index.html", "index.htm");
            }

            // Undertow drives the full lifecycle here: SCIs, listeners, servlet/filter init.
            ServletContainer container = Servlets.defaultContainer();
            manager = container.addDeployment(di);
            manager.deploy();
            HttpHandler deploymentHandler = manager.start();

            HttpHandler root = deploymentHandler;
            if (!contextPath.equals("/")) {
                root = new PathHandler().addPrefixPath(contextPath, root);
            }
            root = new CanonicalPathHandler(root);

            DefaultExchangeHandler exchangeHandler = new DefaultExchangeHandler(root);
            UndertowOptionMap options = UndertowOptionMap.builder().getMap();

            Router router = Router.router(vertx);
            router.route().handler(rc -> {
                if (!rc.request().isEnded()) {
                    rc.request().pause();
                }
                Buffer body = rc.body() != null ? rc.body().buffer() : null;
                VertxHttpExchange exchange = new VertxHttpExchange(rc.request(), allocator, executor, rc, body);
                exchange.setUndertowOptions(options);
                executor.execute(() -> exchangeHandler.handle(exchange));
            });

            server = vertx.createHttpServer()
                    .requestHandler(router)
                    .listen(0)
                    .toCompletionStage()
                    .toCompletableFuture()
                    .join();
            port = server.actualPort();
            System.out.println(LOG + "Server started on port " + port + " with context " + contextPath);

            HTTPContext httpContext = new HTTPContext("localhost", port);
            httpContext.add(new org.jboss.arquillian.container.spi.client.protocol.metadata.Servlet(
                    "default", contextPath));
            return new ProtocolMetaData().addContext(httpContext);

        } catch (Exception e) {
            System.err.println(LOG + "Deploy failed: " + e);
            e.printStackTrace();
            restoreClassLoader();
            throw new DeploymentException("Failed to deploy " + archiveName, e);
        }
    }

    @Override
    public void undeploy(Archive<?> archive) throws DeploymentException {
        if (manager != null) {
            try {
                manager.stop();
                manager.undeploy();
            } catch (Exception e) {
                System.err.println(LOG + "Undeploy error: " + e);
            }
            manager = null;
        }
        closeServer();
        restoreClassLoader();
        if (deploymentDir != null) {
            deleteDir(deploymentDir);
            deploymentDir = null;
        }
    }

    @Override
    public void deploy(Descriptor descriptor) throws DeploymentException {
    }

    @Override
    public void undeploy(Descriptor descriptor) throws DeploymentException {
    }

    // --- deployment construction -------------------------------------------------------------

    private static List<String> collectClassNames(Path classesDir) throws IOException {
        List<String> classNames = new ArrayList<>();
        if (Files.exists(classesDir)) {
            try (Stream<Path> stream = Files.walk(classesDir)) {
                stream.filter(p -> p.toString().endsWith(".class"))
                        .forEach(p -> {
                            String relative = classesDir.relativize(p).toString();
                            classNames.add(relative.replace('/', '.').replace('\\', '.')
                                    .replace(".class", ""));
                        });
            }
        }
        return classNames;
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

    private void registerWebFragments(Path deploymentDir, DeploymentInfo di, ClassLoader cl) {
        Path libDir = deploymentDir.resolve("WEB-INF/lib");
        if (!Files.isDirectory(libDir)) {
            return;
        }
        try (Stream<Path> entries = Files.list(libDir)) {
            for (Path entry : entries.toList()) {
                try {
                    InputStream fragment = null;
                    JarFile jar = null;
                    if (Files.isDirectory(entry)) {
                        Path f = entry.resolve("META-INF/web-fragment.xml");
                        if (Files.isRegularFile(f)) {
                            fragment = Files.newInputStream(f);
                        }
                    } else if (entry.getFileName().toString().endsWith(".jar")) {
                        jar = new JarFile(entry.toFile());
                        JarEntry je = jar.getJarEntry("META-INF/web-fragment.xml");
                        if (je != null) {
                            fragment = jar.getInputStream(je);
                        }
                    }
                    if (fragment != null) {
                        try (InputStream is = fragment) {
                            XMLInputFactory factory = XMLInputFactory.newInstance();
                            factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
                            XMLStreamReader reader = factory.createXMLStreamReader(is);
                            WebFragmentMetaData frag = WebFragmentMetaDataParser.parse(reader,
                                    PropertyReplacers.noop());
                            System.out.println(LOG + "  web-fragment.xml from " + entry.getFileName());
                            registerFromWebXml(frag, di, cl);
                        }
                    }
                    if (jar != null) {
                        jar.close();
                    }
                } catch (Exception e) {
                    System.out.println(LOG + "  Failed web-fragment from " + entry.getFileName()
                            + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.out.println(LOG + "  Failed to list WEB-INF/lib: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void registerFromWebXml(WebCommonMetaData webMetaData, DeploymentInfo di, ClassLoader cl)
            throws Exception {
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
                if (className == null || di.getServlets().containsKey(servlet.getServletName())) {
                    continue;
                }
                Class<? extends Servlet> clazz = (Class<? extends Servlet>) cl.loadClass(className);
                ServletInfo si = new ServletInfo(servlet.getServletName(), clazz);
                for (String pattern : servletMappings.getOrDefault(servlet.getServletName(), List.of())) {
                    si.addMapping(pattern);
                }
                if (servlet.getInitParam() != null) {
                    servlet.getInitParam().forEach(p -> si.addInitParam(p.getParamName(), p.getParamValue()));
                }
                int los = servlet.getLoadOnStartupInt();
                if (los >= 0) {
                    si.setLoadOnStartup(los);
                }
                si.setAsyncSupported(servlet.getAsyncSupportedSet() && servlet.isAsyncSupported());
                if (servlet.getSecurityRoleRefs() != null) {
                    servlet.getSecurityRoleRefs()
                            .forEach(ref -> si.addSecurityRoleRef(ref.getRoleName(), ref.getRoleLink()));
                }
                di.addServlet(si);
                System.out.println(LOG + "  Servlet: " + servlet.getServletName() + " -> "
                        + si.getMappings());
            }
        }

        FiltersMetaData filters = webMetaData.getFilters();
        if (filters != null) {
            for (FilterMetaData filter : filters) {
                String className = filter.getFilterClass();
                if (className == null || di.getFilters().containsKey(filter.getFilterName())) {
                    continue;
                }
                Class<? extends Filter> clazz = (Class<? extends Filter>) cl.loadClass(className);
                FilterInfo fi = new FilterInfo(filter.getFilterName(), clazz);
                if (filter.getInitParam() != null) {
                    filter.getInitParam().forEach(p -> fi.addInitParam(p.getParamName(), p.getParamValue()));
                }
                fi.setAsyncSupported(filter.getAsyncSupportedSet() && filter.isAsyncSupported());
                di.addFilter(fi);
            }
        }

        if (webMetaData.getFilterMappings() != null) {
            for (FilterMappingMetaData mapping : webMetaData.getFilterMappings()) {
                Set<DispatcherType> dispatchers = parseDispatchers(mapping);
                if (mapping.getUrlPatterns() != null) {
                    for (String pattern : mapping.getUrlPatterns()) {
                        for (DispatcherType dt : dispatchers) {
                            di.addFilterUrlMapping(mapping.getFilterName(), pattern, dt);
                        }
                    }
                }
                if (mapping.getServletNames() != null) {
                    for (String servletName : mapping.getServletNames()) {
                        for (DispatcherType dt : dispatchers) {
                            di.addFilterServletNameMapping(mapping.getFilterName(), servletName, dt);
                        }
                    }
                }
            }
        }

        if (webMetaData.getListeners() != null) {
            for (var listener : webMetaData.getListeners()) {
                String className = listener.getListenerClass();
                if (className == null) {
                    continue;
                }
                try {
                    Class<? extends EventListener> clazz = (Class<? extends EventListener>) cl.loadClass(className);
                    di.addListener(new ListenerInfo(clazz));
                    System.out.println(LOG + "  Listener: " + className);
                } catch (Exception e) {
                    System.out.println(LOG + "  Listener failed: " + className + " - " + e.getMessage());
                }
            }
        }
    }

    private Set<DispatcherType> parseDispatchers(FilterMappingMetaData mapping) {
        if (mapping.getDispatchers() == null || mapping.getDispatchers().isEmpty()) {
            return EnumSet.of(DispatcherType.REQUEST);
        }
        Set<DispatcherType> types = EnumSet.noneOf(DispatcherType.class);
        for (var d : mapping.getDispatchers()) {
            try {
                types.add(DispatcherType.valueOf(d.name()));
            } catch (Exception ignored) {
                // skip unknown dispatcher
            }
        }
        return types.isEmpty() ? EnumSet.of(DispatcherType.REQUEST) : types;
    }

    @SuppressWarnings("unchecked")
    private void scanAnnotations(List<String> classNames, DeploymentInfo di, ClassLoader cl) {
        for (String className : classNames) {
            try {
                Class<?> clazz = cl.loadClass(className);

                jakarta.servlet.annotation.WebServlet ws = clazz.getAnnotation(
                        jakarta.servlet.annotation.WebServlet.class);
                if (ws != null && Servlet.class.isAssignableFrom(clazz)) {
                    String name = ws.name().isEmpty() ? className : ws.name();
                    String[] patterns = ws.urlPatterns().length > 0 ? ws.urlPatterns() : ws.value();
                    if (patterns.length > 0 && !di.getServlets().containsKey(name)) {
                        ServletInfo si = new ServletInfo(name, (Class<? extends Servlet>) clazz);
                        for (String p : patterns) {
                            si.addMapping(p);
                        }
                        for (jakarta.servlet.annotation.WebInitParam ip : ws.initParams()) {
                            si.addInitParam(ip.name(), ip.value());
                        }
                        if (ws.loadOnStartup() >= 0) {
                            si.setLoadOnStartup(ws.loadOnStartup());
                        }
                        si.setAsyncSupported(ws.asyncSupported());
                        jakarta.servlet.annotation.ServletSecurity sec = clazz.getAnnotation(
                                jakarta.servlet.annotation.ServletSecurity.class);
                        if (sec != null) {
                            si.setServletSecurityInfo(toServletSecurityInfo(sec));
                            di.setIdentityManager(new TckIdentityManager());
                        }
                        di.addServlet(si);
                        System.out.println(LOG + "  @WebServlet: " + name + " -> " + si.getMappings());
                    }
                }

                jakarta.servlet.annotation.WebFilter wf = clazz.getAnnotation(
                        jakarta.servlet.annotation.WebFilter.class);
                if (wf != null && Filter.class.isAssignableFrom(clazz)) {
                    String name = wf.filterName().isEmpty() ? className : wf.filterName();
                    if (!di.getFilters().containsKey(name)) {
                        FilterInfo fi = new FilterInfo(name, (Class<? extends Filter>) clazz);
                        for (jakarta.servlet.annotation.WebInitParam ip : wf.initParams()) {
                            fi.addInitParam(ip.name(), ip.value());
                        }
                        fi.setAsyncSupported(wf.asyncSupported());
                        di.addFilter(fi);

                        String[] patterns = wf.urlPatterns().length > 0 ? wf.urlPatterns() : wf.value();
                        Set<DispatcherType> dispatchers = wf.dispatcherTypes().length > 0
                                ? EnumSet.copyOf(List.of(wf.dispatcherTypes()))
                                : EnumSet.of(DispatcherType.REQUEST);
                        if (patterns.length == 0 && wf.servletNames().length == 0) {
                            patterns = new String[] { "/*" };
                        }
                        for (String p : patterns) {
                            for (DispatcherType dt : dispatchers) {
                                di.addFilterUrlMapping(name, p, dt);
                            }
                        }
                        for (String sn : wf.servletNames()) {
                            for (DispatcherType dt : dispatchers) {
                                di.addFilterServletNameMapping(name, sn, dt);
                            }
                        }
                        System.out.println(LOG + "  @WebFilter: " + name);
                    }
                }

                jakarta.servlet.annotation.WebListener wl = clazz.getAnnotation(
                        jakarta.servlet.annotation.WebListener.class);
                if (wl != null && EventListener.class.isAssignableFrom(clazz)) {
                    di.addListener(new ListenerInfo((Class<? extends EventListener>) clazz));
                    System.out.println(LOG + "  @WebListener: " + className);
                }
            } catch (ClassNotFoundException e) {
                // skip unloadable class
            } catch (Throwable t) {
                System.out.println(LOG + "  Annotation scan failed for " + className + ": " + t.getMessage());
            }
        }
    }

    private ServletSecurityInfo toServletSecurityInfo(jakarta.servlet.annotation.ServletSecurity sec) {
        ServletSecurityInfo info = new ServletSecurityInfo();
        var httpConstraint = sec.value();
        info.setEmptyRoleSemantic(toEmptyRoleSemantic(httpConstraint.value()));
        info.setTransportGuaranteeType(toTransport(httpConstraint.transportGuarantee()));
        info.addRolesAllowed(List.of(httpConstraint.rolesAllowed()));
        for (var mc : sec.httpMethodConstraints()) {
            HttpMethodSecurityInfo mInfo = new HttpMethodSecurityInfo()
                    .setMethod(mc.value())
                    .setEmptyRoleSemantic(toEmptyRoleSemantic(mc.emptyRoleSemantic()))
                    .setTransportGuaranteeType(toTransport(mc.transportGuarantee()));
            mInfo.addRolesAllowed(List.of(mc.rolesAllowed()));
            info.addHttpMethodSecurityInfo(mInfo);
        }
        return info;
    }

    private static SecurityInfo.EmptyRoleSemantic toEmptyRoleSemantic(
            jakarta.servlet.annotation.ServletSecurity.EmptyRoleSemantic semantic) {
        return semantic == jakarta.servlet.annotation.ServletSecurity.EmptyRoleSemantic.DENY
                ? SecurityInfo.EmptyRoleSemantic.DENY
                : SecurityInfo.EmptyRoleSemantic.PERMIT;
    }

    private static TransportGuaranteeType toTransport(
            jakarta.servlet.annotation.ServletSecurity.TransportGuarantee guarantee) {
        return guarantee == jakarta.servlet.annotation.ServletSecurity.TransportGuarantee.CONFIDENTIAL
                ? TransportGuaranteeType.CONFIDENTIAL
                : TransportGuaranteeType.NONE;
    }

    private void registerSecurity(WebMetaData webMetaData, DeploymentInfo di) {
        LoginConfigMetaData loginConfig = webMetaData.getLoginConfig();
        boolean hasSecurity = false;
        if (loginConfig != null) {
            String loginPage = null;
            String errorPage = null;
            if (loginConfig.getFormLoginConfig() != null) {
                loginPage = loginConfig.getFormLoginConfig().getLoginPage();
                errorPage = loginConfig.getFormLoginConfig().getErrorPage();
            }
            di.setLoginConfig(new LoginConfig(loginConfig.getAuthMethod(),
                    loginConfig.getRealmName() == null ? "TCK" : loginConfig.getRealmName(),
                    loginPage, errorPage));
            hasSecurity = true;
        }

        Set<String> allRoles = new LinkedHashSet<>();
        if (webMetaData.getSecurityConstraints() != null) {
            for (SecurityConstraintMetaData sc : webMetaData.getSecurityConstraints()) {
                if (sc.getResourceCollections() == null) {
                    continue;
                }
                SecurityConstraint constraint = new SecurityConstraint();
                boolean hasAuthConstraint = sc.getAuthConstraint() != null;
                Set<String> roles = new LinkedHashSet<>();
                if (hasAuthConstraint && sc.getAuthConstraint().getRoleNames() != null) {
                    roles.addAll(sc.getAuthConstraint().getRoleNames());
                }
                allRoles.addAll(roles);
                constraint.addRolesAllowed(roles);
                constraint.setEmptyRoleSemantic(hasAuthConstraint && roles.isEmpty()
                        ? SecurityInfo.EmptyRoleSemantic.DENY
                        : SecurityInfo.EmptyRoleSemantic.PERMIT);

                if (sc.getUserDataConstraint() != null
                        && sc.getUserDataConstraint().getTransportGuarantee() != null
                        && "CONFIDENTIAL".equalsIgnoreCase(
                                sc.getUserDataConstraint().getTransportGuarantee().name())) {
                    constraint.setTransportGuaranteeType(TransportGuaranteeType.CONFIDENTIAL);
                }

                for (WebResourceCollectionMetaData collection : sc.getResourceCollections()) {
                    WebResourceCollection wrc = new WebResourceCollection();
                    if (collection.getUrlPatterns() != null) {
                        wrc.addUrlPatterns(collection.getUrlPatterns());
                    }
                    if (collection.getHttpMethods() != null) {
                        wrc.addHttpMethods(collection.getHttpMethods());
                    }
                    if (collection.getHttpMethodOmissions() != null) {
                        wrc.addHttpMethodOmissions(collection.getHttpMethodOmissions());
                    }
                    constraint.addWebResourceCollection(wrc);
                }
                di.addSecurityConstraint(constraint);
                hasSecurity = true;
            }
        }

        if (webMetaData.getSecurityRoles() != null) {
            webMetaData.getSecurityRoles().forEach(r -> allRoles.add(r.getRoleName()));
        }
        allRoles.forEach(di::addSecurityRole);

        if (hasSecurity && di.getIdentityManager() == null) {
            di.setIdentityManager(new TckIdentityManager());
        }
    }

    private void configureSessions(WebMetaData webMetaData, DeploymentInfo di) {
        if (webMetaData.getSessionConfig() == null) {
            return;
        }
        var sessionConfig = webMetaData.getSessionConfig();
        if (sessionConfig.getSessionTimeoutSet()) {
            di.setDefaultSessionTimeout(sessionConfig.getSessionTimeout() * 60);
        }
        if (sessionConfig.getCookieConfig() != null) {
            var cookieConfig = sessionConfig.getCookieConfig();
            ServletSessionConfig ssc = new ServletSessionConfig();
            if (cookieConfig.getName() != null) {
                ssc.setName(cookieConfig.getName());
            }
            if (cookieConfig.getDomain() != null) {
                ssc.setDomain(cookieConfig.getDomain());
            }
            if (cookieConfig.getPath() != null) {
                ssc.setPath(cookieConfig.getPath());
            }
            if (cookieConfig.getHttpOnlySet()) {
                ssc.setHttpOnly(cookieConfig.getHttpOnly());
            }
            if (cookieConfig.getSecureSet()) {
                ssc.setSecure(cookieConfig.getSecure());
            }
            ssc.setMaxAge(cookieConfig.getMaxAge());
            di.setServletSessionConfig(ssc);
        }
    }

    private void configureErrorPages(WebMetaData webMetaData, DeploymentInfo di) {
        if (webMetaData.getErrorPages() == null) {
            return;
        }
        for (var errorPage : webMetaData.getErrorPages()) {
            if (errorPage.getErrorCode() != null) {
                try {
                    di.addErrorPage(new ErrorPage(errorPage.getLocation(),
                            Integer.parseInt(errorPage.getErrorCode())));
                } catch (NumberFormatException ignored) {
                    // skip malformed code
                }
            }
            if (errorPage.getExceptionType() != null) {
                try {
                    @SuppressWarnings("unchecked")
                    Class<? extends Throwable> ex = (Class<? extends Throwable>) di.getClassLoader()
                            .loadClass(errorPage.getExceptionType());
                    di.addErrorPage(new ErrorPage(errorPage.getLocation(), ex));
                } catch (Exception e) {
                    System.out.println(LOG + "  Error page exception type not found: "
                            + errorPage.getExceptionType());
                }
            }
        }
    }

    private void configureMimeMappings(WebMetaData webMetaData, DeploymentInfo di) {
        if (webMetaData.getMimeMappings() != null) {
            webMetaData.getMimeMappings()
                    .forEach(m -> di.addMimeMapping(new MimeMapping(m.getExtension(), m.getMimeType())));
        }
    }

    private void configureWelcomeFiles(WebMetaData webMetaData, DeploymentInfo di) {
        if (webMetaData.getWelcomeFileList() != null
                && webMetaData.getWelcomeFileList().getWelcomeFiles() != null) {
            di.addWelcomePages(webMetaData.getWelcomeFileList().getWelcomeFiles());
        }
    }

    // --- SCIs and TLD listeners --------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void registerScis(Path deploymentDir, Path classesDir, List<String> classNames,
            DeploymentInfo di, ClassLoader cl) {
        Set<String> sciClassNames = new LinkedHashSet<>();
        Path classesService = classesDir
                .resolve("META-INF/services/jakarta.servlet.ServletContainerInitializer");
        readServiceFile(classesService, sciClassNames);

        Path libDir = deploymentDir.resolve("WEB-INF/lib");
        if (Files.isDirectory(libDir)) {
            try (Stream<Path> entries = Files.list(libDir)) {
                for (Path entry : entries.toList()) {
                    if (Files.isDirectory(entry)) {
                        readServiceFile(entry.resolve(
                                "META-INF/services/jakarta.servlet.ServletContainerInitializer"),
                                sciClassNames);
                    } else if (entry.getFileName().toString().endsWith(".jar")) {
                        try (JarFile jar = new JarFile(entry.toFile())) {
                            JarEntry je = jar.getJarEntry(
                                    "META-INF/services/jakarta.servlet.ServletContainerInitializer");
                            if (je != null) {
                                try (BufferedReader r = new BufferedReader(
                                        new InputStreamReader(jar.getInputStream(je)))) {
                                    readServiceReader(r, sciClassNames);
                                }
                            }
                        } catch (IOException e) {
                            System.out.println(LOG + "  Failed to scan JAR for SCIs: " + entry.getFileName());
                        }
                    }
                }
            } catch (IOException e) {
                System.out.println(LOG + "  Failed to list WEB-INF/lib: " + e.getMessage());
            }
        }

        for (String sciName : sciClassNames) {
            try {
                Class<? extends ServletContainerInitializer> sciClass = (Class<? extends ServletContainerInitializer>) cl
                        .loadClass(sciName);
                Set<Class<?>> handled = computeHandledTypes(sciClass, classNames, cl);
                di.addServletContainerInitializer(new ServletContainerInitializerInfo(sciClass, handled));
                System.out.println(LOG + "  SCI: " + sciName + " (handles " + handled.size() + " types)");
            } catch (Exception e) {
                System.out.println(LOG + "  SCI failed: " + sciName + " - " + e.getMessage());
            }
        }
    }

    /**
     * Resolves the {@code @HandlesTypes} of an SCI against the WAR's own classes, which is what
     * Undertow hands the initializer in {@code onStartup}. Annotation types match annotated classes;
     * class/interface types match subtypes.
     */
    private Set<Class<?>> computeHandledTypes(Class<? extends ServletContainerInitializer> sciClass,
            List<String> classNames, ClassLoader cl) {
        HandlesTypes handlesTypes = sciClass.getAnnotation(HandlesTypes.class);
        if (handlesTypes == null || handlesTypes.value().length == 0) {
            return Set.of();
        }
        Class<?>[] wanted = handlesTypes.value();
        Set<Class<?>> matches = new LinkedHashSet<>();
        for (String className : classNames) {
            Class<?> candidate;
            try {
                candidate = cl.loadClass(className);
            } catch (Throwable t) {
                continue;
            }
            for (Class<?> type : wanted) {
                boolean match = type.isAnnotation()
                        ? candidate.isAnnotationPresent((Class<? extends java.lang.annotation.Annotation>) type)
                        : type.isAssignableFrom(candidate) && !candidate.equals(type);
                if (match) {
                    matches.add(candidate);
                    break;
                }
            }
        }
        return matches;
    }

    private void readServiceFile(Path serviceFile, Set<String> out) {
        if (!Files.exists(serviceFile)) {
            return;
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(Files.newInputStream(serviceFile)))) {
            readServiceReader(reader, out);
        } catch (IOException e) {
            System.out.println(LOG + "  Failed to read service file: " + e.getMessage());
        }
    }

    private void readServiceReader(BufferedReader reader, Set<String> out) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (!line.isEmpty() && !line.startsWith("#")) {
                out.add(line);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void scanTldListeners(Path deploymentDir, DeploymentInfo di, ClassLoader cl) {
        Path libDir = deploymentDir.resolve("WEB-INF/lib");
        if (!Files.isDirectory(libDir)) {
            return;
        }
        try (Stream<Path> entries = Files.list(libDir)) {
            entries.forEach(jarPath -> {
                if (jarPath.toString().endsWith(".jar")) {
                    try (JarFile jar = new JarFile(jarPath.toFile())) {
                        jar.stream().filter(e -> e.getName().endsWith(".tld")).forEach(tld -> {
                            try (InputStream is = jar.getInputStream(tld)) {
                                for (String listener : parseTldListeners(is)) {
                                    try {
                                        di.addListener(new ListenerInfo(
                                                (Class<? extends EventListener>) cl.loadClass(listener)));
                                        System.out.println(LOG + "  TLD Listener: " + listener);
                                    } catch (Exception e) {
                                        System.out.println(LOG + "  TLD Listener failed: " + listener);
                                    }
                                }
                            } catch (Exception ignored) {
                                // skip unreadable tld
                            }
                        });
                    } catch (IOException ignored) {
                        // skip unreadable jar
                    }
                }
            });
        } catch (IOException e) {
            System.out.println(LOG + "  Failed to list WEB-INF/lib for TLDs: " + e.getMessage());
        }
    }

    private List<String> parseTldListeners(InputStream is) throws Exception {
        List<String> listeners = new ArrayList<>();
        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, false);
        XMLStreamReader reader = factory.createXMLStreamReader(is);
        boolean inListener = false;
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT && "listener-class".equals(reader.getLocalName())) {
                inListener = true;
            } else if (event == XMLStreamConstants.CHARACTERS && inListener) {
                String className = reader.getText().trim();
                if (!className.isEmpty()) {
                    listeners.add(className);
                }
                inListener = false;
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                inListener = false;
            }
        }
        return listeners;
    }

    // --- lifecycle helpers -------------------------------------------------------------------

    private void closeServer() {
        if (server != null) {
            server.close().toCompletionStage().toCompletableFuture().join();
            server = null;
        }
    }

    private void restoreClassLoader() {
        if (originalClassLoader != null) {
            Thread.currentThread().setContextClassLoader(originalClassLoader);
            originalClassLoader = null;
        }
    }

    private void deleteDir(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (Exception ignored) {
                    // best effort cleanup
                }
            });
        } catch (Exception ignored) {
            // best effort cleanup
        }
    }

    /** Bridges Undertow's buffer needs onto Netty's pooled allocator, as the extension does. */
    private static final class NettyBufferAllocator implements BufferAllocator {
        private static final int SIZE = 1024 * 16 - 20;

        @Override
        public ByteBuf allocateBuffer() {
            return ByteBufAllocator.DEFAULT.directBuffer(SIZE);
        }

        @Override
        public ByteBuf allocateBuffer(boolean direct) {
            return direct ? ByteBufAllocator.DEFAULT.directBuffer(SIZE)
                    : ByteBufAllocator.DEFAULT.heapBuffer(SIZE);
        }

        @Override
        public ByteBuf allocateBuffer(int bufferSize) {
            return ByteBufAllocator.DEFAULT.directBuffer(bufferSize);
        }

        @Override
        public ByteBuf allocateBuffer(boolean direct, int bufferSize) {
            return direct ? ByteBufAllocator.DEFAULT.directBuffer(bufferSize)
                    : ByteBufAllocator.DEFAULT.heapBuffer(bufferSize);
        }

        @Override
        public int getBufferSize() {
            return SIZE;
        }
    }
}
