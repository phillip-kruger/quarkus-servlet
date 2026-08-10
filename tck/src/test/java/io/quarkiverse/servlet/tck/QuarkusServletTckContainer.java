package io.quarkiverse.servlet.tck;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.jboss.arquillian.container.spi.client.container.DeployableContainer;
import org.jboss.arquillian.container.spi.client.container.DeploymentException;
import org.jboss.arquillian.container.spi.client.container.LifecycleException;
import org.jboss.arquillian.container.spi.client.protocol.ProtocolDescription;
import org.jboss.arquillian.container.spi.client.protocol.metadata.HTTPContext;
import org.jboss.arquillian.container.spi.client.protocol.metadata.ProtocolMetaData;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.exporter.ExplodedExporter;
import org.jboss.shrinkwrap.descriptor.api.Descriptor;

import io.quarkus.bootstrap.app.AdditionalDependency;
import io.quarkus.bootstrap.app.CuratedApplication;
import io.quarkus.bootstrap.app.QuarkusBootstrap;
import io.quarkus.bootstrap.app.RunningQuarkusApplication;
import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.bootstrap.resolver.BootstrapAppModelResolver;
import io.quarkus.bootstrap.resolver.maven.MavenArtifactResolver;
import io.quarkus.bootstrap.workspace.ArtifactSources;
import io.quarkus.bootstrap.workspace.DefaultArtifactSources;
import io.quarkus.bootstrap.workspace.DefaultSourceDir;
import io.quarkus.bootstrap.workspace.WorkspaceModule;
import io.quarkus.bootstrap.workspace.WorkspaceModuleId;
import io.quarkus.maven.dependency.Dependency;
import io.quarkus.runner.bootstrap.AugmentActionImpl;
import io.quarkus.runner.bootstrap.StartupActionImpl;

/**
 * Arquillian container that runs each TCK deployment as a real Quarkus application.
 * <p>
 * The alternative container in this module drives the servlet runtime directly, which leaves the
 * whole {@code deployment} module - annotation scanning, web.xml processing, CDI wiring, the Vert.x
 * route - untested. This one augments and starts Quarkus per deployment instead, so the TCK
 * exercises the extension exactly as it ships.
 * <p>
 * Select it with {@code -Dservlet.tck.container=quarkus}.
 */
public class QuarkusServletTckContainer implements DeployableContainer<ServletTckContainerConfig> {

    /** {@code -Dservlet.tck.trace=true} to report each deployment's bootstrap/augment/start phases. */
    private static final boolean TRACE = Boolean.getBoolean("servlet.tck.trace");

    private Path workDir;
    private CuratedApplication curatedApplication;
    private RunningQuarkusApplication runningApplication;
    private ClassLoader originalClassLoader;
    private int port;
    /** {@code <auth-method>} of the deployment being started, or null when it declares none. */
    private String authMethod;

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
    }

    @Override
    public void stop() throws LifecycleException {
    }

    @Override
    public ProtocolMetaData deploy(Archive<?> archive) throws DeploymentException {
        String archiveName = archive.getName();
        String contextPath = "/" + archiveName.replace(".war", "");
        System.out.println("[SERVLET-TCK/quarkus] Deploying: " + archiveName);

        try {
            workDir = Files.createTempDirectory("servlet-tck-quarkus-");
            Path warDir = workDir.resolve("war");
            Files.createDirectories(warDir);
            archive.as(ExplodedExporter.class).exportExplodedInto(warDir.toFile());
            // ShrinkWrap nests the export under the archive name.
            Path exploded = warDir.resolve(archiveName);
            if (!Files.isDirectory(exploded)) {
                exploded = warDir;
            }

            Path appRoot = layOutApplicationRoot(exploded);
            authMethod = declaredAuthMethod(exploded);
            port = freePort();

            QuarkusBootstrap.Builder builder = QuarkusBootstrap.builder()
                    .setBaseName(archiveName)
                    .setApplicationRoot(appRoot)
                    // TEST, because that is the only mode AugmentActionImpl will launch in-process;
                    // NORMAL is rejected outright. Normally TEST mode would also decide the
                    // application model, and there it drags this module's entire test classpath in
                    // - the TCK runtime plus its signature-test tooling (sigtest, ct-sym: ~20MB
                    // across 270 dependencies) - which is not part of the application under test
                    // and is heavy enough to exhaust the heap during augmentation. Supplying the
                    // model below bypasses that resolution altogether, so the mode only governs
                    // the launch.
                    .setMode(QuarkusBootstrap.Mode.TEST)
                    .setExistingModel(buildApplicationModel(appRoot, archiveName))
                    .setTargetDirectory(workDir.resolve("target"))
                    .setDisableClasspathCache(true)
                    .setBuildSystemProperties(buildTimeProperties(contextPath));

            // WEB-INF/lib jars carry web fragments and ServletContainerInitializers, so they have
            // to be indexed application archives rather than plain classpath entries.
            Path libDir = exploded.resolve("WEB-INF/lib");
            if (Files.isDirectory(libDir)) {
                try (Stream<Path> jars = Files.list(libDir)) {
                    for (Path jar : jars.filter(p -> p.toString().endsWith(".jar")).toList()) {
                        builder.addAdditionalApplicationArchive(new AdditionalDependency(jar, false, true));
                    }
                }
            }

            trace("phase=bootstrap");
            curatedApplication = builder.build().bootstrap();
            trace("phase=augment");
            dumpApplicationModel();

            StartupActionImpl startupAction = new AugmentActionImpl(curatedApplication)
                    .createInitialRuntimeApplication();
            trace("phase=start");
            startupAction.overrideConfig(runtimeProperties(contextPath));

            originalClassLoader = Thread.currentThread().getContextClassLoader();
            runningApplication = startupAction.run();
            Thread.currentThread().setContextClassLoader(runningApplication.getClassLoader());

            trace("Started on port " + port + " with context " + contextPath);

            HTTPContext httpContext = new HTTPContext("localhost", port);
            // Arquillian derives the context root it injects as @ArquillianResource URL from the
            // servlets registered here. Without one the TCK believes the application is deployed at
            // the root, and then expects getContextPath() to be empty and mis-parses getRequestURL()
            // - failures that look like container bugs but are the harness describing itself wrong.
            httpContext.add(new org.jboss.arquillian.container.spi.client.protocol.metadata.Servlet(
                    "default", contextPath));
            return new ProtocolMetaData().addContext(httpContext);

        } catch (Exception e) {
            throw new DeploymentException("Failed to deploy " + archiveName + " to Quarkus", e);
        }
    }

    @Override
    public void undeploy(Archive<?> archive) throws DeploymentException {
        if (originalClassLoader != null) {
            Thread.currentThread().setContextClassLoader(originalClassLoader);
            originalClassLoader = null;
        }
        closeQuietly(runningApplication);
        runningApplication = null;
        closeQuietly(curatedApplication);
        curatedApplication = null;
        if (workDir != null) {
            deleteRecursively(workDir);
            workDir = null;
        }
    }

    @Override
    public void deploy(Descriptor descriptor) throws DeploymentException {
    }

    @Override
    public void undeploy(Descriptor descriptor) throws DeploymentException {
    }

    /**
     * Where a deployment got to before it failed. Boot, augmentation and startup fail in very
     * different ways, and a stack trace alone does not always say which one you are looking at.
     * Off by default: a full run is ~200 deployments, and three lines each is noise until something
     * breaks.
     */
    private static void trace(String message) {
        if (TRACE) {
            System.out.println("[SERVLET-TCK/quarkus] " + message);
        }
    }

    /** Reports what actually ended up in the application model. Only useful when diagnosing size. */
    private void dumpApplicationModel() {
        if (!TRACE) {
            return;
        }
        var model = curatedApplication.getApplicationModel();
        var deps = model.getDependencies();
        System.out.println("[SERVLET-TCK/quarkus] model deps=" + deps.size());
        long totalBytes = 0;
        for (var dep : deps) {
            String coords = dep.getGroupId() + ":" + dep.getArtifactId();
            long size = 0;
            for (Path path : dep.getResolvedPaths()) {
                try {
                    size += Files.isRegularFile(path) ? Files.size(path) : 0;
                } catch (IOException ignored) {
                    // best effort
                }
            }
            totalBytes += size;
            if (coords.contains("tck") || coords.contains("arquillian") || size > 5_000_000) {
                System.out.println("[SERVLET-TCK/quarkus]   " + coords
                        + " size=" + (size / 1024) + "KB"
                        + " runtimeCp=" + dep.isRuntimeCp() + " appArchive=" + dep.isReloadable());
            }
        }
        System.out.println("[SERVLET-TCK/quarkus] model total=" + (totalBytes / 1024 / 1024) + "MB");
    }

    /**
     * Builds the application model by hand rather than letting Quarkus derive one from this module.
     * <p>
     * Deriving it is what makes the TCK unbootable: this module's test classpath is the TCK itself,
     * so any model resolved from it carries 270 dependencies of test harness that the application
     * under test has nothing to do with. What the application actually consists of is the exploded
     * WAR plus {@code quarkus-servlet} and its transitives, and that is precisely what is described
     * here - a synthetic workspace module whose output directory is the WAR, with a single direct
     * dependency on the extension. Resolving that gives the same ~180-dependency model a real
     * Quarkus application would have, and augments in the same amount of heap.
     * <p>
     * Extension artifacts are resolved from the local repository, not the reactor, so the extension
     * must be installed ({@code mvn install}) before running the TCK in this mode.
     */
    private ApplicationModel buildApplicationModel(Path appRoot, String archiveName) throws Exception {
        String applicationName = archiveName.replace(".war", "");
        WorkspaceModule module = WorkspaceModule.builder()
                .setModuleId(WorkspaceModuleId.of("io.quarkiverse.servlet.tck", applicationName, "1.0"))
                .setModuleDir(workDir)
                .setBuildDir(workDir)
                .addArtifactSources(new DefaultArtifactSources(ArtifactSources.MAIN,
                        java.util.List.of(new DefaultSourceDir(appRoot, appRoot, null)),
                        java.util.List.of()))
                .addDependency(Dependency.of("io.quarkiverse.servlet", "quarkus-servlet", extensionVersion()))
                // TEST is the only launch mode that will start in-process, and ArC's test-only
                // build steps register io.quarkus.test.ActivateSessionContext unconditionally in
                // that mode. Augmentation fails to index it unless quarkus-test-common is on the
                // classpath, so it belongs in the model for the same reason it would be there in
                // any @QuarkusTest.
                .addDependency(Dependency.of("io.quarkus", "quarkus-test-common", quarkusVersion()))
                // The TCK's security tests log in as fixed users. A real application supplies those
                // through an identity provider, which is also what makes Quarkus the authenticating
                // party - see securityProperties() for the users themselves.
                .addDependency(Dependency.of("io.quarkus", "quarkus-elytron-security-properties-file",
                        quarkusVersion()))
                .build();

        MavenArtifactResolver resolver = MavenArtifactResolver.builder()
                .setWorkspaceDiscovery(false)
                .build();
        return new BootstrapAppModelResolver(resolver).resolveModel(module);
    }

    /** The reactor version of the extension, handed down by failsafe. */
    private static String extensionVersion() {
        return requiredProperty("servlet.tck.extension.version");
    }

    /** The Quarkus version the extension is built against, handed down by failsafe. */
    private static String quarkusVersion() {
        return requiredProperty("servlet.tck.quarkus.version");
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null) {
            throw new IllegalStateException(name + " is not set; the Quarkus TCK container cannot "
                    + "tell which artifacts to resolve into the application model");
        }
        return value;
    }

    /**
     * Rearranges an exploded WAR into the flat layout Quarkus expects of an application root:
     * classes at the top, {@code web.xml} under {@code META-INF}, and everything the WAR served
     * from its root under {@code META-INF/resources}.
     */
    private Path layOutApplicationRoot(Path exploded) throws IOException {
        Path appRoot = workDir.resolve("app");
        Files.createDirectories(appRoot);

        Path classes = exploded.resolve("WEB-INF/classes");
        if (Files.isDirectory(classes)) {
            copyTree(classes, appRoot, QuarkusServletTckContainer::isNotContainerApi);
        }

        Path webXml = exploded.resolve("WEB-INF/web.xml");
        if (Files.isRegularFile(webXml)) {
            Path target = appRoot.resolve("META-INF/web.xml");
            Files.createDirectories(target.getParent());
            Files.copy(webXml, target, StandardCopyOption.REPLACE_EXISTING);
        }

        // Static content: everything at the WAR root other than WEB-INF.
        Path resources = appRoot.resolve("META-INF/resources");
        try (Stream<Path> entries = Files.list(exploded)) {
            for (Path entry : entries.toList()) {
                if (entry.getFileName().toString().equals("WEB-INF")) {
                    continue;
                }
                Files.createDirectories(resources);
                Path target = resources.resolve(entry.getFileName().toString());
                if (Files.isDirectory(entry)) {
                    copyTree(entry, target);
                } else {
                    Files.copy(entry, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        return appRoot;
    }

    /** Config that must be fixed before augmentation, because the build steps read it. */
    private java.util.Properties buildTimeProperties(String contextPath) {
        java.util.Properties properties = new java.util.Properties();
        properties.setProperty("quarkus.servlet.context-path", contextPath);
        properties.setProperty("quarkus.http.port", String.valueOf(port));
        properties.setProperty("quarkus.http.test-port", String.valueOf(port));
        properties.setProperty("quarkus.banner.enabled", "false");
        // quarkus.native.builder-image defaults to ${platform.quarkus.native.builder-image}, which
        // a platform BOM would normally supply. The model built here imports no platform, so the
        // placeholder has nothing to expand to and config validation fails before any build step
        // runs. Nothing is ever built natively here, so the upstream default will do.
        properties.setProperty("platform.quarkus.native.builder-image", "mandrel");
        securityProperties().forEach(properties::setProperty);
        return properties;
    }

    /**
     * The users the Jakarta TCK authenticates as: {@code j2ee} is the authorised caller and
     * {@code javajoe} the unauthorised one, with the roles the TCK's own deployment descriptors
     * grant them. No principal holds the VP role, which several tests depend on.
     * <p>
     * These are build-time properties because the embedded realm is built during augmentation.
     */
    private Map<String, String> securityProperties() {
        Map<String, String> properties = new HashMap<>();
        // Which mechanism is active is build-time configuration that belongs to the application,
        // so the harness sets it the way an application would - from what its own web.xml asks
        // for. Enabling both at once is not equivalent: with form authentication on, basic drops
        // into silent mode and stops issuing the WWW-Authenticate challenge that BASIC requires.
        // FORM needs nothing enabled here: the extension supplies that mechanism itself when
        // web.xml asks for it. Enabling Quarkus's as well would put two on /j_security_check.
        if (!"FORM".equalsIgnoreCase(authMethod)) {
            properties.put("quarkus.http.auth.basic", "true");
        }
        properties.put("quarkus.security.users.embedded.enabled", "true");
        properties.put("quarkus.security.users.embedded.plain-text", "true");
        properties.put("quarkus.security.users.embedded.users.j2ee", "j2ee");
        properties.put("quarkus.security.users.embedded.users.javajoe", "javajoe");
        properties.put("quarkus.security.users.embedded.roles.j2ee", "Administrator,Employee");
        properties.put("quarkus.security.users.embedded.roles.javajoe", "Manager,Employee");
        return properties;
    }

    private Map<String, String> runtimeProperties(String contextPath) {
        Map<String, String> properties = new HashMap<>();
        properties.put("quarkus.http.port", String.valueOf(port));
        properties.put("quarkus.http.test-port", String.valueOf(port));
        properties.put("quarkus.servlet.context-path", contextPath);
        // TCK servlets are ordinary blocking code, so they get a worker thread rather than the
        // event loop - the same choice an application migrating from Undertow would make.
        properties.put("quarkus.servlet.execution-model", "worker");
        properties.put("quarkus.banner.enabled", "false");
        // The realm's users and roles are read when the realm is built at runtime, not during
        // augmentation, so setting them as build system properties alone leaves it empty.
        properties.putAll(securityProperties());
        if (TRACE) {
            properties.put("quarkus.log.category.\"io.quarkus.vertx.http.runtime.security\".level", "DEBUG");
            properties.put("quarkus.log.category.\"io.quarkus.security\".level", "DEBUG");
            properties.put("quarkus.log.category.\"io.quarkus.elytron\".level", "DEBUG");
            properties.put("quarkus.log.min-level", "DEBUG");
        }
        return properties;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /**
     * The {@code <auth-method>} the WAR's own {@code web.xml} declares, read straight out of the
     * XML because the deployment has not been augmented yet and this decides build-time config.
     */
    private static String declaredAuthMethod(Path exploded) {
        Path webXml = exploded.resolve("WEB-INF/web.xml");
        if (!Files.isRegularFile(webXml)) {
            return null;
        }
        try {
            String xml = Files.readString(webXml);
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("<auth-method>\\s*([A-Za-z_-]+)\\s*</auth-method>")
                    .matcher(xml);
            return matcher.find() ? matcher.group(1) : null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Whether a path from {@code WEB-INF/classes} belongs in the application root.
     * <p>
     * Some TCK archives ship their own copies of {@code jakarta.servlet} classes. Servlet 6.1
     * 10.7.2 forbids a web application's classloader from overriding the container's API classes,
     * and a real container simply ignores them. Flattening the WAR into a Quarkus application root
     * would instead promote them to application classes, so {@code jakarta.servlet.GenericServlet}
     * ends up defined twice - once in the application classloader and once in its parent - and the
     * deployment dies with {@code NoClassDefFoundError} on a class that is demonstrably present.
     */
    private static boolean isNotContainerApi(Path relative) {
        return !relative.startsWith(Path.of("jakarta", "servlet"));
    }

    private static void copyTree(Path source, Path target) throws IOException {
        copyTree(source, target, path -> true);
    }

    private static void copyTree(Path source, Path target, Predicate<Path> include) throws IOException {
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path relative = source.relativize(path);
                if (!include.test(relative)) {
                    continue;
                }
                Path destination = target.resolve(relative.toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception e) {
            System.out.println("[SERVLET-TCK/quarkus] Error closing: " + e);
        }
    }

    private static void deleteRecursively(Path path) {
        try (Stream<Path> paths = Files.walk(path)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // temp files; nothing useful to do
                }
            });
        } catch (IOException ignored) {
            // temp files; nothing useful to do
        }
    }
}
