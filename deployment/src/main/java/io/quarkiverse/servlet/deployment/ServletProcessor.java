package io.quarkiverse.servlet.deployment;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import jakarta.enterprise.context.SessionScoped;
import jakarta.inject.Singleton;

import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.AnnotationValue;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.Type;
import org.jboss.logging.Logger;
import org.jboss.metadata.javaee.spec.ParamValueMetaData;
import org.jboss.metadata.web.spec.ErrorPageMetaData;
import org.jboss.metadata.web.spec.FilterMappingMetaData;
import org.jboss.metadata.web.spec.FilterMetaData;
import org.jboss.metadata.web.spec.FiltersMetaData;
import org.jboss.metadata.web.spec.LocaleEncodingMetaData;
import org.jboss.metadata.web.spec.LoginConfigMetaData;
import org.jboss.metadata.web.spec.ServletMappingMetaData;
import org.jboss.metadata.web.spec.ServletMetaData;
import org.jboss.metadata.web.spec.SessionConfigMetaData;
import org.jboss.metadata.web.spec.WebMetaData;
import org.jboss.metadata.web.spec.WelcomeFileListMetaData;

import io.quarkiverse.servlet.runtime.DefaultServlet;
import io.quarkiverse.servlet.runtime.ExecutionModel;
import io.quarkiverse.servlet.runtime.HttpSessionContext;
import io.quarkiverse.servlet.runtime.LoginConfig;
import io.quarkiverse.servlet.runtime.MultipartConfiguration;
import io.quarkiverse.servlet.runtime.ServletDeployment;
import io.quarkiverse.servlet.runtime.ServletFormAuthenticationMechanism;
import io.quarkiverse.servlet.runtime.ServletProducer;
import io.quarkiverse.servlet.runtime.ServletRecorder;
import io.quarkiverse.servlet.runtime.ServletSecurityConstraint;
import io.quarkiverse.servlet.runtime.ServletSecurityPolicy;
import io.quarkiverse.servlet.spi.FilterBuildItem;
import io.quarkiverse.servlet.spi.ListenerBuildItem;
import io.quarkiverse.servlet.spi.ServletBuildItem;
import io.quarkiverse.servlet.spi.ServletContainerInitializerBuildItem;
import io.quarkiverse.servlet.spi.ServletContextPathBuildItem;
import io.quarkiverse.servlet.spi.ServletDeploymentBuildItem;
import io.quarkiverse.servlet.spi.ServletInitParamBuildItem;
import io.quarkiverse.servlet.spi.WebMetadataBuildItem;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.AnnotationsTransformerBuildItem;
import io.quarkus.arc.deployment.BeanDefiningAnnotationBuildItem;
import io.quarkus.arc.deployment.ContextRegistrationPhaseBuildItem;
import io.quarkus.arc.deployment.ContextRegistrationPhaseBuildItem.ContextConfiguratorBuildItem;
import io.quarkus.arc.deployment.CustomScopeBuildItem;
import io.quarkus.arc.deployment.SyntheticBeanBuildItem;
import io.quarkus.deployment.Capability;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.BuildSteps;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.CapabilityBuildItem;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.HotDeploymentWatchedFileBuildItem;
import io.quarkus.deployment.builditem.LaunchModeBuildItem;
import io.quarkus.deployment.builditem.RunTimeConfigurationDefaultBuildItem;
import io.quarkus.deployment.builditem.ServiceStartBuildItem;
import io.quarkus.deployment.builditem.ShutdownContextBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.deployment.util.ServiceUtil;
import io.quarkus.runtime.RuntimeValue;
import io.quarkus.vertx.http.deployment.RouteBuildItem;
import io.quarkus.vertx.http.deployment.devmode.NotFoundPageDisplayableEndpointBuildItem;
import io.quarkus.vertx.http.runtime.VertxHttpBuildTimeConfig;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;

@BuildSteps
public class ServletProcessor {

    private static final Logger LOG = Logger.getLogger(ServletProcessor.class);

    private static final String FEATURE = "servlet";

    /** Name of the container's static-resource servlet; matches the name the spec uses. */
    static final String DEFAULT_SERVLET_NAME = "default";

    private static final DotName WEB_SERVLET = DotName.createSimple(
            "jakarta.servlet.annotation.WebServlet");
    private static final DotName WEB_FILTER = DotName.createSimple(
            "jakarta.servlet.annotation.WebFilter");
    private static final DotName WEB_LISTENER = DotName.createSimple(
            "jakarta.servlet.annotation.WebListener");
    private static final DotName RUN_ON_VIRTUAL_THREAD = DotName.createSimple(
            "io.smallrye.common.annotation.RunOnVirtualThread");
    private static final DotName BLOCKING = DotName.createSimple(
            "io.smallrye.common.annotation.Blocking");
    private static final DotName NON_BLOCKING = DotName.createSimple(
            "io.smallrye.common.annotation.NonBlocking");
    private static final DotName WEB_INIT_PARAM = DotName.createSimple(
            "jakarta.servlet.annotation.WebInitParam");
    private static final String SCI_SERVICE_FILE = "META-INF/services/jakarta.servlet.ServletContainerInitializer";
    private static final DotName HANDLES_TYPES = DotName.createSimple(
            "jakarta.servlet.annotation.HandlesTypes");
    private static final DotName MULTIPART_CONFIG = DotName.createSimple(
            "jakarta.servlet.annotation.MultipartConfig");
    private static final DotName SERVLET_SECURITY = DotName.createSimple(
            "jakarta.servlet.annotation.ServletSecurity");

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    /**
     * Declares the servlet capability so extensions that integrate with a servlet container detect
     * this one, and so deploying it alongside quarkus-undertow fails at build time rather than
     * silently registering two competing catch-all routes.
     */
    @BuildStep
    CapabilityBuildItem capability() {
        return new CapabilityBuildItem(Capability.SERVLET, FEATURE);
    }

    @BuildStep
    void watchWebXml(BuildProducer<HotDeploymentWatchedFileBuildItem> watchedFiles) {
        watchedFiles.produce(new HotDeploymentWatchedFileBuildItem("META-INF/web.xml"));
        watchedFiles.produce(new HotDeploymentWatchedFileBuildItem("WEB-INF/web.xml"));
    }

    @BuildStep
    void beanDefiningAnnotations(BuildProducer<BeanDefiningAnnotationBuildItem> annotations) {
        annotations.produce(new BeanDefiningAnnotationBuildItem(WEB_SERVLET));
        annotations.produce(new BeanDefiningAnnotationBuildItem(WEB_FILTER));
        annotations.produce(new BeanDefiningAnnotationBuildItem(WEB_LISTENER));
    }

    @BuildStep
    AnnotationsTransformerBuildItem addTypedAnnotation() {
        DotName typed = DotName.createSimple("jakarta.enterprise.inject.Typed");
        return new AnnotationsTransformerBuildItem(
                org.jboss.jandex.AnnotationTransformation.forClasses()
                        .whenAnyMatch(WEB_SERVLET, WEB_FILTER, WEB_LISTENER)
                        .whenNoneMatch(typed)
                        .transform(ctx -> {
                            ClassInfo clazz = (ClassInfo) ctx.declaration();
                            org.jboss.jandex.Type classType = org.jboss.jandex.Type.create(
                                    clazz.name(), org.jboss.jandex.Type.Kind.CLASS);
                            ctx.add(org.jboss.jandex.AnnotationInstance.builder(typed)
                                    .value(new org.jboss.jandex.Type[] { classType })
                                    .build());
                        }));
    }

    @BuildStep
    void registerBeans(BuildProducer<AdditionalBeanBuildItem> beans,
            BuildProducer<ListenerBuildItem> listeners) {
        beans.produce(AdditionalBeanBuildItem.unremovableOf(ServletProducer.class));
        beans.produce(AdditionalBeanBuildItem.unremovableOf(HttpSessionContext.class));
        listeners.produce(new ListenerBuildItem(HttpSessionContext.class.getName()));
    }

    @BuildStep
    ContextConfiguratorBuildItem registerSessionContext(ContextRegistrationPhaseBuildItem phase) {
        return new ContextConfiguratorBuildItem(
                phase.getContext()
                        .configure(SessionScoped.class)
                        .normal()
                        .contextClass(HttpSessionContext.class));
    }

    @BuildStep
    CustomScopeBuildItem sessionScope() {
        return new CustomScopeBuildItem(DotName.createSimple(SessionScoped.class.getName()));
    }

    @BuildStep
    void scanAnnotations(CombinedIndexBuildItem combinedIndex,
            ExcludedFragmentClassesBuildItem excludedFragmentClasses,
            BuildProducer<ServletBuildItem> servletProducer,
            BuildProducer<FilterBuildItem> filterProducer,
            BuildProducer<ListenerBuildItem> listenerProducer) {

        IndexView index = combinedIndex.getIndex();
        Set<String> excluded = excludedFragmentClasses.getClassNames();

        for (AnnotationInstance ann : index.getAnnotations(WEB_LISTENER)) {
            if (ann.target().kind() != AnnotationTarget.Kind.CLASS) {
                continue;
            }
            String className = ann.target().asClass().name().toString();
            if (excluded.contains(className)) {
                continue;
            }
            listenerProducer.produce(new ListenerBuildItem(className));
        }

        for (AnnotationInstance ann : index.getAnnotations(WEB_SERVLET)) {
            if (ann.target().kind() != AnnotationTarget.Kind.CLASS) {
                continue;
            }
            ClassInfo clazz = ann.target().asClass();
            String className = clazz.name().toString();
            if (excluded.contains(className)) {
                continue;
            }

            AnnotationValue nameValue = ann.value("name");
            String name = nameValue != null && !nameValue.asString().isEmpty()
                    ? nameValue.asString()
                    : className;

            String[] urlPatterns = resolveUrlPatterns(ann);
            if (urlPatterns.length == 0) {
                continue;
            }

            AnnotationValue loadOnStartupValue = ann.value("loadOnStartup");
            int loadOnStartup = loadOnStartupValue != null ? loadOnStartupValue.asInt() : -1;

            AnnotationValue asyncValue = ann.value("asyncSupported");
            boolean asyncSupported = asyncValue != null && asyncValue.asBoolean();

            Map<String, String> initParams = extractInitParams(ann);

            servletProducer.produce(new ServletBuildItem(
                    name, className, List.of(urlPatterns), initParams,
                    loadOnStartup, asyncSupported, resolveExecutionModel(clazz),
                    resolveMultipartConfig(clazz)));
        }

        for (AnnotationInstance ann : index.getAnnotations(WEB_FILTER)) {
            if (ann.target().kind() != AnnotationTarget.Kind.CLASS) {
                continue;
            }
            ClassInfo clazz = ann.target().asClass();
            String className = clazz.name().toString();
            if (excluded.contains(className)) {
                continue;
            }

            AnnotationValue nameValue = ann.value("filterName");
            String name = nameValue != null && !nameValue.asString().isEmpty()
                    ? nameValue.asString()
                    : className;

            String[] urlPatterns = resolveUrlPatterns(ann);

            List<String> servletNames = List.of();
            AnnotationValue servletNamesValue = ann.value("servletNames");
            if (servletNamesValue != null) {
                servletNames = List.of(servletNamesValue.asStringArray());
            }

            if (urlPatterns.length == 0 && servletNames.isEmpty()) {
                urlPatterns = new String[] { "/*" };
            }

            java.util.Set<jakarta.servlet.DispatcherType> dispatcherTypes = null;
            AnnotationValue dtValue = ann.value("dispatcherTypes");
            if (dtValue != null) {
                dispatcherTypes = new java.util.HashSet<>();
                for (String dt : dtValue.asEnumArray()) {
                    dispatcherTypes.add(jakarta.servlet.DispatcherType.valueOf(dt));
                }
            }

            AnnotationValue asyncValue = ann.value("asyncSupported");
            boolean asyncSupported = asyncValue != null && asyncValue.asBoolean();

            Map<String, String> initParams = extractInitParams(ann);

            filterProducer.produce(new FilterBuildItem(
                    name, className, List.of(urlPatterns), servletNames,
                    dispatcherTypes, asyncSupported, initParams, 0));
        }
    }

    @BuildStep
    void registerForReflection(List<ServletBuildItem> servlets,
            List<FilterBuildItem> filters,
            List<ListenerBuildItem> listeners,
            BuildProducer<ReflectiveClassBuildItem> reflective) {
        for (ServletBuildItem s : servlets) {
            reflective.produce(ReflectiveClassBuildItem.builder(s.getServletClass())
                    .constructors().methods().build());
        }
        for (FilterBuildItem f : filters) {
            reflective.produce(ReflectiveClassBuildItem.builder(f.getFilterClass())
                    .constructors().methods().build());
        }
        // Listeners are instantiated reflectively when they are not CDI beans.
        for (ListenerBuildItem l : listeners) {
            reflective.produce(ReflectiveClassBuildItem.builder(l.getListenerClass())
                    .constructors().methods().build());
        }
    }

    @BuildStep
    void processWebXml(Optional<WebMetadataBuildItem> webMetadataBuildItem,
            BuildProducer<ServletBuildItem> servletProducer,
            BuildProducer<ListenerBuildItem> listenerProducer,
            BuildProducer<FilterBuildItem> filterProducer) {
        if (webMetadataBuildItem.isEmpty()) {
            return;
        }
        WebMetaData webMetaData = webMetadataBuildItem.get().getWebMetaData();

        // <listener> is not decoration: a ServletContextListener declared here is how an
        // application registers servlets and filters programmatically, so skipping these leaves
        // the deployment missing whatever they would have added.
        if (webMetaData.getListeners() != null) {
            for (var listener : webMetaData.getListeners()) {
                if (listener.getListenerClass() != null) {
                    listenerProducer.produce(new ListenerBuildItem(listener.getListenerClass()));
                }
            }
        }

        Map<String, List<String>> servletMappings = new HashMap<>();
        if (webMetaData.getServletMappings() != null) {
            for (ServletMappingMetaData mapping : webMetaData.getServletMappings()) {
                servletMappings
                        .computeIfAbsent(mapping.getServletName(), k -> new ArrayList<>())
                        .addAll(mapping.getUrlPatterns());
            }
        }

        if (webMetaData.getServlets() != null) {
            for (ServletMetaData servlet : webMetaData.getServlets()) {
                String name = servlet.getServletName();
                String className = servlet.getServletClass();
                if (className == null) {
                    continue;
                }
                List<String> mappings = servletMappings.getOrDefault(name, List.of());
                int loadOnStartup = servlet.getLoadOnStartupInt();
                boolean asyncSupported = servlet.isAsyncSupported();

                // First value wins for a repeated param name: a duplicate <init-param> in web.xml,
                // and (after fragment merging places web.xml's params first) any param a fragment
                // tried to redefine. See WebXmlParsingBuildStep.mergeServlets.
                Map<String, String> initParams = new HashMap<>();
                if (servlet.getInitParam() != null) {
                    servlet.getInitParam().forEach(p -> initParams.putIfAbsent(p.getParamName(), p.getParamValue()));
                }

                servletProducer.produce(new ServletBuildItem(
                        name, className, mappings, initParams, loadOnStartup, asyncSupported));
            }
        }

        // Each <filter-mapping> keeps its own dispatcher set and target (url-pattern or servlet-name):
        // a filter mapped to a servlet only for FORWARD must not run on a plain REQUEST to that
        // servlet. Merging every mapping of a filter into one combined tuple would lose that per-mapping
        // association, so preserve the mappings individually and in document order.
        Map<String, List<FilterMappingMetaData>> filterMappings = new HashMap<>();
        if (webMetaData.getFilterMappings() != null) {
            for (FilterMappingMetaData mapping : webMetaData.getFilterMappings()) {
                filterMappings
                        .computeIfAbsent(mapping.getFilterName(), k -> new ArrayList<>())
                        .add(mapping);
            }
        }

        FiltersMetaData filters = webMetaData.getFilters();
        if (filters != null) {
            for (FilterMetaData filter : filters) {
                String name = filter.getFilterName();
                String className = filter.getFilterClass();
                if (className == null) {
                    continue;
                }
                boolean asyncSupported = filter.isAsyncSupported();

                Map<String, String> initParams = new HashMap<>();
                if (filter.getInitParam() != null) {
                    filter.getInitParam().forEach(p -> initParams.putIfAbsent(p.getParamName(), p.getParamValue()));
                }

                List<FilterMappingMetaData> mappings = filterMappings.getOrDefault(name, List.of());
                if (mappings.isEmpty()) {
                    // Declared but unmapped: still register it so named dispatch can find it.
                    filterProducer.produce(new FilterBuildItem(
                            name, className, List.of(), List.of(), null, asyncSupported, initParams, 0));
                    continue;
                }
                // One FilterBuildItem per mapping; registerFilter merges them onto a single FilterInfo.
                for (FilterMappingMetaData mapping : mappings) {
                    List<String> patterns = mapping.getUrlPatterns() != null
                            ? mapping.getUrlPatterns()
                            : List.of();
                    List<String> servletNames = mapping.getServletNames() != null
                            ? mapping.getServletNames()
                            : List.of();
                    java.util.Set<jakarta.servlet.DispatcherType> dispTypes = null;
                    if (mapping.getDispatchers() != null) {
                        dispTypes = new java.util.HashSet<>();
                        for (var d : mapping.getDispatchers()) {
                            dispTypes.add(jakarta.servlet.DispatcherType.valueOf(d.name()));
                        }
                    }
                    filterProducer.produce(new FilterBuildItem(
                            name, className, patterns, servletNames,
                            dispTypes, asyncSupported, initParams, 0));
                }
            }
        }
    }

    @BuildStep
    @Record(ExecutionTime.STATIC_INIT)
    void parseSecurityConstraints(ServletRecorder recorder,
            CombinedIndexBuildItem combinedIndex,
            List<ServletBuildItem> servlets,
            Optional<WebMetadataBuildItem> webMetadataBuildItem,
            ServletDeploymentBuildItem deploymentBuildItem) {
        List<ServletSecurityConstraint> annotated = annotationConstraints(combinedIndex.getIndex(), servlets);
        if (webMetadataBuildItem.isEmpty()) {
            if (!annotated.isEmpty()) {
                recorder.registerSecurityConstraints(deploymentBuildItem.getDeployment(), annotated);
            }
            return;
        }
        parseSecurityConstraints(recorder, annotated, webMetadataBuildItem, deploymentBuildItem);
    }

    /**
     * Turns {@code @ServletSecurity} on a servlet class into constraints over that servlet's own
     * url-patterns, which is how the annotation is defined to behave.
     * <p>
     * The {@code httpMethodConstraints} cover the methods they name; the class-level
     * {@code @HttpConstraint} covers everything else, which is expressed as a constraint that omits
     * the named methods rather than one that lists the remainder.
     */
    private static List<ServletSecurityConstraint> annotationConstraints(IndexView index,
            List<ServletBuildItem> servlets) {
        List<ServletSecurityConstraint> constraints = new ArrayList<>();
        for (ServletBuildItem servlet : servlets) {
            ClassInfo clazz = index.getClassByName(DotName.createSimple(servlet.getServletClass()));
            if (clazz == null || servlet.getMappings().isEmpty()) {
                continue;
            }
            AnnotationInstance annotation = clazz.declaredAnnotation(SERVLET_SECURITY);
            if (annotation == null) {
                continue;
            }
            List<String> patterns = new ArrayList<>(servlet.getMappings());
            java.util.Set<String> namedMethods = new java.util.LinkedHashSet<>();

            AnnotationValue methodConstraints = annotation.value("httpMethodConstraints");
            if (methodConstraints != null) {
                for (AnnotationInstance methodConstraint : methodConstraints.asNestedArray()) {
                    String method = methodConstraint.value().asString();
                    namedMethods.add(method);
                    // On @HttpMethodConstraint the semantic is its own member; value() is the
                    // method name.
                    constraints.add(new ServletSecurityConstraint(patterns,
                            java.util.Set.of(method), java.util.Set.of(),
                            rolesOf(methodConstraint),
                            emptyRoleSemanticOf(methodConstraint.value("emptyRoleSemantic")),
                            transportGuaranteeOf(methodConstraint)));
                }
            }

            AnnotationValue classConstraint = annotation.value();
            AnnotationInstance httpConstraint = classConstraint == null ? null : classConstraint.asNested();
            // On @HttpConstraint the semantic *is* value().
            java.util.Set<String> classRoles = rolesOf(httpConstraint);
            ServletSecurityConstraint.EmptyRoleSemantic classSemantic = emptyRoleSemanticOf(
                    httpConstraint == null ? null : httpConstraint.value());
            ServletSecurityConstraint.TransportGuarantee classTransport = transportGuaranteeOf(httpConstraint);
            // An all-default @HttpConstraint (PERMIT, no roles, NONE) establishes no constraint on the
            // methods it would otherwise cover, per the @HttpConstraint javadoc. Emitting it anyway
            // would make those methods "covered", defeating <deny-uncovered-http-methods/>.
            boolean classConstraintIsDefault = classRoles.isEmpty()
                    && classSemantic == ServletSecurityConstraint.EmptyRoleSemantic.PERMIT
                    && classTransport == ServletSecurityConstraint.TransportGuarantee.NONE;
            if (!classConstraintIsDefault) {
                constraints.add(new ServletSecurityConstraint(patterns,
                        java.util.Set.of(), namedMethods,
                        classRoles, classSemantic, classTransport));
            }
        }
        return constraints;
    }

    private static java.util.Set<String> rolesOf(AnnotationInstance constraint) {
        AnnotationValue roles = constraint == null ? null : constraint.value("rolesAllowed");
        return roles == null ? java.util.Set.of() : java.util.Set.of(roles.asStringArray());
    }

    private static ServletSecurityConstraint.EmptyRoleSemantic emptyRoleSemanticOf(
            AnnotationValue value) {
        return value != null && "DENY".equals(value.asEnum())
                ? ServletSecurityConstraint.EmptyRoleSemantic.DENY
                : ServletSecurityConstraint.EmptyRoleSemantic.PERMIT;
    }

    private static ServletSecurityConstraint.TransportGuarantee transportGuaranteeOf(
            AnnotationInstance constraint) {
        AnnotationValue value = constraint == null ? null : constraint.value("transportGuarantee");
        return value != null && "CONFIDENTIAL".equals(value.asEnum())
                ? ServletSecurityConstraint.TransportGuarantee.CONFIDENTIAL
                : ServletSecurityConstraint.TransportGuarantee.NONE;
    }

    private static void parseSecurityConstraints(ServletRecorder recorder,
            List<ServletSecurityConstraint> annotated,
            Optional<WebMetadataBuildItem> webMetadataBuildItem,
            ServletDeploymentBuildItem deploymentBuildItem) {
        if (webMetadataBuildItem.isEmpty()) {
            return;
        }
        WebMetaData webMetaData = webMetadataBuildItem.get().getWebMetaData();
        if (webMetaData.getSecurityConstraints() == null) {
            if (!annotated.isEmpty()) {
                recorder.registerSecurityConstraints(deploymentBuildItem.getDeployment(), annotated);
            }
            return;
        }
        List<ServletSecurityConstraint> constraints = new ArrayList<>(
                applicableAnnotationConstraints(annotated, webMetaData));
        for (var sc : webMetaData.getSecurityConstraints()) {
            if (sc.getResourceCollections() == null) {
                continue;
            }
            for (var rc : sc.getResourceCollections()) {
                List<String> urlPatterns = rc.getUrlPatterns() != null
                        ? new ArrayList<>(rc.getUrlPatterns())
                        : List.of("/*");
                java.util.Set<String> methods = rc.getHttpMethods() != null
                        ? new java.util.HashSet<>(rc.getHttpMethods())
                        : java.util.Set.of();
                java.util.Set<String> omissions = rc.getHttpMethodOmissions() != null
                        ? new java.util.HashSet<>(rc.getHttpMethodOmissions())
                        : java.util.Set.of();
                java.util.Set<String> roles = java.util.Set.of();
                ServletSecurityConstraint.EmptyRoleSemantic emptyRole = ServletSecurityConstraint.EmptyRoleSemantic.PERMIT;
                if (sc.getAuthConstraint() != null) {
                    if (sc.getAuthConstraint().getRoleNames() != null
                            && !sc.getAuthConstraint().getRoleNames().isEmpty()) {
                        roles = new java.util.HashSet<>(sc.getAuthConstraint().getRoleNames());
                    } else {
                        emptyRole = ServletSecurityConstraint.EmptyRoleSemantic.DENY;
                    }
                }
                ServletSecurityConstraint.TransportGuarantee transport = ServletSecurityConstraint.TransportGuarantee.NONE;
                if (sc.getUserDataConstraint() != null
                        && "CONFIDENTIAL".equalsIgnoreCase(
                                sc.getUserDataConstraint().getTransportGuarantee() != null
                                        ? sc.getUserDataConstraint().getTransportGuarantee().name()
                                        : null)) {
                    transport = ServletSecurityConstraint.TransportGuarantee.CONFIDENTIAL;
                }
                constraints.add(new ServletSecurityConstraint(
                        urlPatterns, methods, omissions, roles, emptyRole, transport));
            }
        }
        if (!constraints.isEmpty()) {
            recorder.registerSecurityConstraints(deploymentBuildItem.getDeployment(), constraints);
        }
    }

    /**
     * Applies {@code <login-config>}.
     * <p>
     * Authentication itself belongs to Quarkus, and which mechanism is active is decided by
     * {@code quarkus.http.auth.*} - build-time configuration that only the application can set, so
     * an extension cannot turn it on from here. What the descriptor can usefully contribute is the
     * detail: FORM's login and error pages become defaults for Quarkus's own form authentication,
     * whose {@code /j_security_check} endpoint and {@code j_username}/{@code j_password} parameters
     * already match what the servlet spec prescribes.
     * <p>
     * When the descriptor asks for a mechanism the application has not enabled, that is reported
     * here rather than left to surface as unexplained 401s at runtime.
     */
    @BuildStep
    @Record(ExecutionTime.RUNTIME_INIT)
    void applyLoginConfig(ServletRecorder recorder,
            ServletBuildTimeConfig buildTimeConfig,
            Optional<WebMetadataBuildItem> webMetadataBuildItem,
            ServletDeploymentBuildItem deploymentBuildItem,
            VertxHttpBuildTimeConfig httpBuildTimeConfig,
            BuildProducer<SyntheticBeanBuildItem> formMechanism,
            BuildProducer<RunTimeConfigurationDefaultBuildItem> runtimeDefaults) {
        if (webMetadataBuildItem.isEmpty()) {
            return;
        }
        LoginConfigMetaData loginConfig = webMetadataBuildItem.get().getWebMetaData().getLoginConfig();
        if (loginConfig == null) {
            return;
        }
        String loginPage = null;
        String errorPage = null;
        if (loginConfig.getFormLoginConfig() != null) {
            loginPage = loginConfig.getFormLoginConfig().getLoginPage();
            errorPage = loginConfig.getFormLoginConfig().getErrorPage();
        }
        recorder.setLoginConfig(deploymentBuildItem.getDeployment(), loginConfig.getAuthMethod(),
                loginConfig.getRealmName(), loginPage, errorPage);

        String authMethod = loginConfig.getAuthMethod();
        if (LoginConfig.FORM.equalsIgnoreCase(authMethod)) {
            // web.xml states these relative to the context root, while Quarkus takes absolute
            // paths. Without the prefix the login page 404s and, worse, the POST the browser makes
            // to <context>/j_security_check never reaches the form mechanism at all, so the
            // credentials are silently ignored and the caller is bounced to the landing page.
            String contextPath = buildTimeConfig.contextPath();
            if (loginPage != null) {
                runtimeDefaults.produce(new RunTimeConfigurationDefaultBuildItem(
                        "quarkus.http.auth.form.login-page", withContextPath(contextPath, loginPage)));
            }
            if (errorPage != null) {
                runtimeDefaults.produce(new RunTimeConfigurationDefaultBuildItem(
                        "quarkus.http.auth.form.error-page", withContextPath(contextPath, errorPage)));
            }
            runtimeDefaults.produce(new RunTimeConfigurationDefaultBuildItem(
                    "quarkus.http.auth.form.post-location",
                    withContextPath(contextPath, "/j_security_check")));
            // This extension installs the form mechanism itself, so that the interrupted request
            // is remembered in the session rather than in a cookie the client need not return.
            // Quarkus's own would be a second mechanism competing for /j_security_check.
            if (httpBuildTimeConfig.auth().form()) {
                LOG.warn("quarkus.http.auth.form.enabled is true and web.xml declares FORM "
                        + "authentication. This extension provides the form mechanism; leave the "
                        + "Quarkus one disabled so the two do not compete for /j_security_check.");
            } else {
                formMechanism.produce(SyntheticBeanBuildItem
                        .configure(ServletFormAuthenticationMechanism.class)
                        .types(HttpAuthenticationMechanism.class)
                        .scope(Singleton.class)
                        .setRuntimeInit()
                        .unremovable()
                        .supplier(recorder.formAuthenticationMechanism())
                        .done());
            }
        } else if (LoginConfig.BASIC.equalsIgnoreCase(authMethod)
                && httpBuildTimeConfig.auth().basic().isPresent()
                && !httpBuildTimeConfig.auth().basic().get()) {
            LOG.warnf("web.xml declares <auth-method>BASIC</auth-method> but quarkus.http.auth.basic "
                    + "is explicitly false, so no caller can authenticate.");
        }
    }

    private static String withContextPath(String contextPath, String path) {
        if (contextPath == null || contextPath.isEmpty() || "/".equals(contextPath)) {
            return path;
        }
        return contextPath.endsWith("/")
                ? contextPath.substring(0, contextPath.length() - 1) + path
                : contextPath + path;
    }

    @BuildStep
    void registerSecurityPolicy(BuildProducer<AdditionalBeanBuildItem> beans,
            Optional<WebMetadataBuildItem> webMetadataBuildItem) {
        beans.produce(AdditionalBeanBuildItem.unremovableOf(ServletSecurityPolicy.class));
    }

    /**
     * Finds the {@code ServletContainerInitializer}s on the application's classpath and works out,
     * from the Jandex index, which classes their {@code @HandlesTypes} declarations match.
     * <p>
     * This runs at build time so nothing has to scan the classpath at boot, which is what lets the
     * result work in a native image where {@code ServiceLoader} discovery is not available.
     */
    @BuildStep
    List<ServletContainerInitializerBuildItem> containerInitializers(
            CombinedIndexBuildItem combinedIndex,
            BuildProducer<AdditionalBeanBuildItem> beans) throws IOException {

        IndexView index = combinedIndex.getIndex();
        List<ServletContainerInitializerBuildItem> result = new ArrayList<>();
        for (String className : ServiceUtil.classNamesNamedIn(
                Thread.currentThread().getContextClassLoader(), SCI_SERVICE_FILE)) {
            beans.produce(AdditionalBeanBuildItem.unremovableOf(className));
            ClassInfo sci = index.getClassByName(DotName.createSimple(className));
            AnnotationInstance handles = sci == null ? null : sci.declaredAnnotation(HANDLES_TYPES);
            result.add(new ServletContainerInitializerBuildItem(className,
                    handles == null ? Set.of() : handledTypes(index, handles)));
        }
        return result;
    }

    /**
     * Resolves one {@code @HandlesTypes} declaration. Per the spec each listed type matches by
     * inheritance when it is a class or interface, and by presence when it is an annotation - and
     * an annotation counts wherever it appears on a class, including on its members.
     */
    private static Set<String> handledTypes(IndexView index, AnnotationInstance handles) {
        Set<String> matches = new HashSet<>();
        for (Type handled : handles.value().asClassArray()) {
            DotName typeName = handled.asClassType().name();
            for (ClassInfo info : index.getAllKnownSubclasses(typeName)) {
                matches.add(info.name().toString());
            }
            for (ClassInfo info : index.getAllKnownImplementors(typeName)) {
                matches.add(info.name().toString());
            }
            for (AnnotationInstance annotation : index.getAnnotations(typeName)) {
                ClassInfo declaring = declaringClassOf(annotation.target());
                if (declaring != null) {
                    matches.add(declaring.name().toString());
                }
            }
        }
        return matches;
    }

    /**
     * Drops the {@code @ServletSecurity} constraints the descriptor has already spoken for.
     * <p>
     * Servlet 6.1, 13.4.1: the annotation applies only where web.xml declares no
     * {@code <security-constraint>} for the servlet's patterns, and not at all when the descriptor
     * is {@code metadata-complete}. Applying both is not additive but contradictory - a servlet
     * annotated {@code @HttpConstraint(DENY)} and granted to a role by the descriptor ends up
     * denied to everyone, since an empty-role DENY beats any role list.
     */
    private static List<ServletSecurityConstraint> applicableAnnotationConstraints(
            List<ServletSecurityConstraint> annotated, WebMetaData webMetaData) {
        if (annotated.isEmpty()) {
            return annotated;
        }
        if (webMetaData.isMetadataComplete()) {
            return List.of();
        }
        Set<String> declared = new HashSet<>();
        if (webMetaData.getSecurityConstraints() != null) {
            for (var sc : webMetaData.getSecurityConstraints()) {
                if (sc.getResourceCollections() == null) {
                    continue;
                }
                for (var rc : sc.getResourceCollections()) {
                    if (rc.getUrlPatterns() != null) {
                        declared.addAll(rc.getUrlPatterns());
                    }
                }
            }
        }
        List<ServletSecurityConstraint> result = new ArrayList<>();
        for (ServletSecurityConstraint constraint : annotated) {
            if (constraint.getUrlPatterns().stream().noneMatch(declared::contains)) {
                result.add(constraint);
            }
        }
        return result;
    }

    private static ClassInfo declaringClassOf(AnnotationTarget target) {
        return switch (target.kind()) {
            case CLASS -> target.asClass();
            case METHOD -> target.asMethod().declaringClass();
            case FIELD -> target.asField().declaringClass();
            case METHOD_PARAMETER -> target.asMethodParameter().method().declaringClass();
            default -> null;
        };
    }

    @BuildStep
    @Record(ExecutionTime.STATIC_INIT)
    ServletDeploymentBuildItem assembleDeployment(
            ServletRecorder recorder,
            ServletBuildTimeConfig buildTimeConfig,
            List<ServletBuildItem> servlets,
            List<FilterBuildItem> filters,
            List<ServletInitParamBuildItem> initParamItems,
            List<ServletContainerInitializerBuildItem> containerInitializers,
            Optional<WebMetadataBuildItem> webMetadataBuildItem) {

        String contextPath = buildTimeConfig.contextPath();

        Map<String, String> initParams = new HashMap<>();
        for (ServletInitParamBuildItem ip : initParamItems) {
            initParams.put(ip.getKey(), ip.getValue());
        }
        if (webMetadataBuildItem.isPresent()) {
            WebMetaData md = webMetadataBuildItem.get().getWebMetaData();
            if (md.getContextParams() != null) {
                for (ParamValueMetaData param : md.getContextParams()) {
                    initParams.put(param.getParamName(), param.getParamValue());
                }
            }
        }

        RuntimeValue<ServletDeployment> deployment = recorder.createDeployment(contextPath, initParams);

        webMetadataBuildItem.ifPresent(item -> applyWebXmlDeploymentMetadata(recorder, deployment,
                item.getWebMetaData()));

        boolean rootMapped = false;
        for (ServletBuildItem s : servlets) {
            recorder.registerServlet(deployment, s.getName(), s.getServletClass(),
                    s.getMappings(), s.getInitParams(), s.getLoadOnStartup(),
                    s.isAsyncSupported(), s.getExecutionModel(), s.getMultipartConfig());
            rootMapped |= s.getMappings().contains("/");
        }

        // The container's default servlet serves static resources from META-INF/resources. It is
        // only registered when the application has not claimed "/" itself.
        if (!rootMapped) {
            recorder.registerServlet(deployment, DEFAULT_SERVLET_NAME,
                    DefaultServlet.class.getName(), List.of("/"), Map.of(), -1, false,
                    ExecutionModel.EVENT_LOOP);
        }

        // Role-ref aliases are declared per servlet in web.xml, so they can only be applied once
        // the servlets themselves are registered.
        webMetadataBuildItem.ifPresent(item -> applyServletRoleRefs(recorder, deployment,
                item.getWebMetaData()));

        for (FilterBuildItem f : filters) {
            recorder.registerFilter(deployment, f.getName(), f.getFilterClass(),
                    f.getUrlPatterns(), f.getServletNames(), f.getDispatcherTypes(),
                    f.getInitParams(), f.getPriority(), f.isAsyncSupported());
        }

        for (ServletContainerInitializerBuildItem sci : containerInitializers) {
            recorder.addContainerInitializer(deployment, sci.getInitializerClass(),
                    sci.getHandledTypes());
        }

        return new ServletDeploymentBuildItem(deployment);
    }

    @BuildStep
    ServletContextPathBuildItem contextPath(ServletBuildTimeConfig config) {
        return new ServletContextPathBuildItem(config.contextPath());
    }

    @BuildStep
    void registerDevModeEndpoints(LaunchModeBuildItem launchMode,
            List<ServletBuildItem> servlets,
            BuildProducer<NotFoundPageDisplayableEndpointBuildItem> endpoints) {
        if (launchMode.getLaunchMode() != io.quarkus.runtime.LaunchMode.DEVELOPMENT) {
            return;
        }
        for (ServletBuildItem s : servlets) {
            for (String mapping : s.getMappings()) {
                endpoints.produce(new NotFoundPageDisplayableEndpointBuildItem(
                        mapping, "Servlet: " + s.getName()));
            }
        }
    }

    @BuildStep
    @Record(ExecutionTime.RUNTIME_INIT)
    ServiceStartBuildItem boot(
            ServletRecorder recorder,
            ServletDeploymentBuildItem deploymentBuildItem,
            List<ListenerBuildItem> listenerItems,
            BuildProducer<RouteBuildItem> routes,
            ShutdownContextBuildItem shutdown) {

        // Listeners the descriptor declared are registered first, and those it did not - a TLD's -
        // after them. A TLD listener is an addition to a deployment that is otherwise complete, so
        // it must not run before the listeners that deployment declared: those establish the state
        // it observes, and reversing the two has it looking at a context that is not yet set up.
        for (ListenerBuildItem l : listenerItems) {
            if (!l.isRestricted()) {
                recorder.registerListener(deploymentBuildItem.getDeployment(), l.getListenerClass(),
                        false);
            }
        }
        for (ListenerBuildItem l : listenerItems) {
            if (l.isRestricted()) {
                recorder.registerListener(deploymentBuildItem.getDeployment(), l.getListenerClass(),
                        true);
            }
        }

        recorder.setupSecurityPolicy(deploymentBuildItem.getDeployment());

        Handler<RoutingContext> handler = recorder.boot(
                deploymentBuildItem.getDeployment(), shutdown);

        routes.produce(RouteBuildItem.builder()
                .orderedRoute("/*", Integer.MAX_VALUE - 1)
                .handler(handler).build());

        return new ServiceStartBuildItem("servlet");
    }

    /**
     * Transfers the deployment-wide parts of web.xml (welcome files, error pages, session config)
     * to the runtime. Servlet and filter declarations are handled separately by
     * {@code processWebXml}, which turns them into build items.
     */
    /**
     * Applies each servlet's {@code <security-role-ref>} declarations so {@code isUserInRole} can
     * translate the servlet's own role alias to the deployment role it links to.
     */
    private static void applyServletRoleRefs(ServletRecorder recorder,
            RuntimeValue<ServletDeployment> deployment, WebMetaData webMetaData) {
        if (webMetaData.getServlets() == null) {
            return;
        }
        for (ServletMetaData servlet : webMetaData.getServlets()) {
            if (servlet.getSecurityRoleRefs() == null) {
                continue;
            }
            for (var ref : servlet.getSecurityRoleRefs()) {
                if (ref.getRoleName() != null && ref.getRoleLink() != null) {
                    recorder.addSecurityRoleRef(deployment, servlet.getServletName(),
                            ref.getRoleName(), ref.getRoleLink());
                }
            }
        }
    }

    private static void applyWebXmlDeploymentMetadata(ServletRecorder recorder,
            RuntimeValue<ServletDeployment> deployment, WebMetaData webMetaData) {

        // getEffectiveMajorVersion/getEffectiveMinorVersion report the version the *descriptor*
        // declares, not the version the container implements, so an application can tell which
        // rules its own web.xml is being read under.
        if (webMetaData.getVersion() != null) {
            String[] parts = webMetaData.getVersion().split("\\.");
            try {
                recorder.setEffectiveVersion(deployment, Integer.parseInt(parts[0]),
                        parts.length > 1 ? Integer.parseInt(parts[1]) : 0);
            } catch (NumberFormatException e) {
                // A malformed version attribute is not worth failing the build over; the
                // container's own version stands.
            }
        }

        WelcomeFileListMetaData welcomeFiles = webMetaData.getWelcomeFileList();
        if (welcomeFiles != null && welcomeFiles.getWelcomeFiles() != null
                && !welcomeFiles.getWelcomeFiles().isEmpty()) {
            recorder.setWelcomeFiles(deployment, List.copyOf(welcomeFiles.getWelcomeFiles()));
        }

        if (webMetaData.getErrorPages() != null) {
            for (ErrorPageMetaData errorPage : webMetaData.getErrorPages()) {
                String location = errorPage.getLocation();
                if (location == null) {
                    continue;
                }
                String errorCode = errorPage.getErrorCode();
                if (errorCode != null && !errorCode.isBlank()) {
                    recorder.addErrorPage(deployment, Integer.parseInt(errorCode.trim()), location);
                } else if (errorPage.getExceptionType() != null
                        && !errorPage.getExceptionType().isBlank()) {
                    recorder.addExceptionErrorPage(deployment,
                            errorPage.getExceptionType().trim(), location);
                }
            }
        }

        SessionConfigMetaData sessionConfig = webMetaData.getSessionConfig();
        if (sessionConfig != null && sessionConfig.getSessionTimeout() > 0) {
            recorder.setSessionTimeout(deployment, sessionConfig.getSessionTimeout());
        }

        if (Boolean.TRUE.equals(webMetaData.getDenyUncoveredHttpMethods())) {
            recorder.setDenyUncoveredHttpMethods(deployment, true);
        }

        // ServletContext.getServletContextName() returns the <display-name> from web.xml.
        if (webMetaData.getDescriptionGroup() != null
                && webMetaData.getDescriptionGroup().getDisplayName() != null
                && !webMetaData.getDescriptionGroup().getDisplayName().isBlank()) {
            recorder.setDisplayName(deployment, webMetaData.getDescriptionGroup().getDisplayName());
        }

        // <locale-encoding-mapping-list> drives the charset picked by ServletResponse.setLocale().
        if (webMetaData.getLocalEncodings() != null
                && webMetaData.getLocalEncodings().getMappings() != null) {
            Map<String, String> localeEncodings = new HashMap<>();
            for (LocaleEncodingMetaData mapping : webMetaData.getLocalEncodings().getMappings()) {
                if (mapping.getLocale() != null && mapping.getEncoding() != null) {
                    localeEncodings.put(mapping.getLocale(), mapping.getEncoding());
                }
            }
            if (!localeEncodings.isEmpty()) {
                recorder.setLocaleEncodingMappings(deployment, localeEncodings);
            }
        }
    }

    /**
     * Reads {@code @MultipartConfig} off a servlet class. Returns {@code null} when the servlet
     * declared none, in which case calling {@code getParts()} is still allowed but unbounded.
     */
    private static MultipartConfiguration resolveMultipartConfig(ClassInfo clazz) {
        AnnotationInstance ann = clazz.declaredAnnotation(MULTIPART_CONFIG);
        if (ann == null) {
            return null;
        }
        AnnotationValue maxFileSize = ann.value("maxFileSize");
        AnnotationValue maxRequestSize = ann.value("maxRequestSize");
        AnnotationValue threshold = ann.value("fileSizeThreshold");
        AnnotationValue location = ann.value("location");
        return new MultipartConfiguration(
                maxFileSize != null ? maxFileSize.asLong() : -1L,
                maxRequestSize != null ? maxRequestSize.asLong() : -1L,
                threshold != null ? threshold.asInt() : 0,
                location != null ? location.asString() : null);
    }

    /**
     * Maps the Quarkus execution-model annotations onto a servlet. Returns {@code null} when the
     * class declares none, so that the deployment-wide default applies.
     */
    private static ExecutionModel resolveExecutionModel(ClassInfo clazz) {
        boolean virtualThread = clazz.hasDeclaredAnnotation(RUN_ON_VIRTUAL_THREAD);
        boolean blocking = clazz.hasDeclaredAnnotation(BLOCKING);
        boolean nonBlocking = clazz.hasDeclaredAnnotation(NON_BLOCKING);

        if (blocking && nonBlocking) {
            throw new IllegalStateException(
                    "Servlet " + clazz.name() + " is annotated with both @Blocking and @NonBlocking");
        }
        if (virtualThread && nonBlocking) {
            throw new IllegalStateException("Servlet " + clazz.name()
                    + " is annotated with both @RunOnVirtualThread and @NonBlocking");
        }
        if (virtualThread) {
            return ExecutionModel.VIRTUAL_THREAD;
        }
        if (blocking) {
            return ExecutionModel.WORKER;
        }
        if (nonBlocking) {
            return ExecutionModel.EVENT_LOOP;
        }
        return null;
    }

    private static String[] resolveUrlPatterns(AnnotationInstance ann) {
        AnnotationValue urlPatternsValue = ann.value("urlPatterns");
        if (urlPatternsValue != null) {
            return urlPatternsValue.asStringArray();
        }
        AnnotationValue valueValue = ann.value("value");
        if (valueValue != null) {
            return valueValue.asStringArray();
        }
        return new String[0];
    }

    private static Map<String, String> extractInitParams(AnnotationInstance ann) {
        Map<String, String> params = new HashMap<>();
        AnnotationValue initParamsValue = ann.value("initParams");
        if (initParamsValue != null) {
            for (AnnotationInstance initParam : initParamsValue.asNestedArray()) {
                AnnotationValue nameVal = initParam.value("name");
                AnnotationValue valVal = initParam.value("value");
                if (nameVal != null && valVal != null) {
                    params.put(nameVal.asString(), valVal.asString());
                }
            }
        }
        return params;
    }
}
