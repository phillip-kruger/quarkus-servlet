package io.quarkiverse.servlet.deployment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.enterprise.context.SessionScoped;

import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.AnnotationValue;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;
import org.jboss.metadata.web.spec.FilterMappingMetaData;
import org.jboss.metadata.web.spec.FilterMetaData;
import org.jboss.metadata.web.spec.FiltersMetaData;
import org.jboss.metadata.web.spec.ServletMappingMetaData;
import org.jboss.metadata.web.spec.ServletMetaData;
import org.jboss.metadata.web.spec.WebMetaData;

import io.quarkiverse.servlet.runtime.HttpSessionContext;
import io.quarkiverse.servlet.runtime.ServletDeployment;
import io.quarkiverse.servlet.runtime.ServletProducer;
import io.quarkiverse.servlet.runtime.ServletRecorder;
import io.quarkiverse.servlet.runtime.ServletSecurityConstraint;
import io.quarkiverse.servlet.runtime.ServletSecurityPolicy;
import io.quarkiverse.servlet.spi.FilterBuildItem;
import io.quarkiverse.servlet.spi.ListenerBuildItem;
import io.quarkiverse.servlet.spi.ServletBuildItem;
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
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.BuildSteps;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.LaunchModeBuildItem;
import io.quarkus.deployment.builditem.ServiceStartBuildItem;
import io.quarkus.deployment.builditem.ShutdownContextBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.runtime.RuntimeValue;
import io.quarkus.vertx.http.deployment.RouteBuildItem;
import io.quarkus.vertx.http.deployment.devmode.NotFoundPageDisplayableEndpointBuildItem;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;

@BuildSteps
public class ServletProcessor {

    private static final String FEATURE = "servlet";

    private static final DotName WEB_SERVLET = DotName.createSimple(
            "jakarta.servlet.annotation.WebServlet");
    private static final DotName WEB_FILTER = DotName.createSimple(
            "jakarta.servlet.annotation.WebFilter");
    private static final DotName WEB_LISTENER = DotName.createSimple(
            "jakarta.servlet.annotation.WebListener");
    private static final DotName RUN_ON_VIRTUAL_THREAD = DotName.createSimple(
            "io.smallrye.common.annotation.RunOnVirtualThread");
    private static final DotName WEB_INIT_PARAM = DotName.createSimple(
            "jakarta.servlet.annotation.WebInitParam");

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
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
            BuildProducer<ServletBuildItem> servletProducer,
            BuildProducer<FilterBuildItem> filterProducer) {

        IndexView index = combinedIndex.getIndex();

        for (AnnotationInstance ann : index.getAnnotations(WEB_SERVLET)) {
            if (ann.target().kind() != AnnotationTarget.Kind.CLASS) {
                continue;
            }
            ClassInfo clazz = ann.target().asClass();
            String className = clazz.name().toString();

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

            boolean runOnVirtualThread = clazz.hasDeclaredAnnotation(RUN_ON_VIRTUAL_THREAD);

            servletProducer.produce(new ServletBuildItem(
                    name, className, List.of(urlPatterns), initParams,
                    loadOnStartup, asyncSupported, runOnVirtualThread));
        }

        for (AnnotationInstance ann : index.getAnnotations(WEB_FILTER)) {
            if (ann.target().kind() != AnnotationTarget.Kind.CLASS) {
                continue;
            }
            ClassInfo clazz = ann.target().asClass();
            String className = clazz.name().toString();

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
            BuildProducer<ReflectiveClassBuildItem> reflective) {
        for (ServletBuildItem s : servlets) {
            reflective.produce(ReflectiveClassBuildItem.builder(s.getServletClass())
                    .constructors().methods().build());
        }
        for (FilterBuildItem f : filters) {
            reflective.produce(ReflectiveClassBuildItem.builder(f.getFilterClass())
                    .constructors().methods().build());
        }
    }

    @BuildStep
    void processWebXml(Optional<WebMetadataBuildItem> webMetadataBuildItem,
            BuildProducer<ServletBuildItem> servletProducer,
            BuildProducer<FilterBuildItem> filterProducer) {
        if (webMetadataBuildItem.isEmpty()) {
            return;
        }
        WebMetaData webMetaData = webMetadataBuildItem.get().getWebMetaData();

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

                Map<String, String> initParams = new HashMap<>();
                if (servlet.getInitParam() != null) {
                    servlet.getInitParam().forEach(p -> initParams.put(p.getParamName(), p.getParamValue()));
                }

                servletProducer.produce(new ServletBuildItem(
                        name, className, mappings, initParams, loadOnStartup, asyncSupported));
            }
        }

        Map<String, List<String>> filterUrlMappings = new HashMap<>();
        Map<String, List<String>> filterServletNameMappings = new HashMap<>();
        Map<String, java.util.Set<jakarta.servlet.DispatcherType>> filterDispatcherTypes = new HashMap<>();
        if (webMetaData.getFilterMappings() != null) {
            for (FilterMappingMetaData mapping : webMetaData.getFilterMappings()) {
                if (mapping.getUrlPatterns() != null) {
                    filterUrlMappings
                            .computeIfAbsent(mapping.getFilterName(), k -> new ArrayList<>())
                            .addAll(mapping.getUrlPatterns());
                }
                if (mapping.getServletNames() != null) {
                    filterServletNameMappings
                            .computeIfAbsent(mapping.getFilterName(), k -> new ArrayList<>())
                            .addAll(mapping.getServletNames());
                }
                if (mapping.getDispatchers() != null) {
                    java.util.Set<jakarta.servlet.DispatcherType> types = filterDispatcherTypes
                            .computeIfAbsent(mapping.getFilterName(), k -> new java.util.HashSet<>());
                    for (var d : mapping.getDispatchers()) {
                        types.add(jakarta.servlet.DispatcherType.valueOf(d.name()));
                    }
                }
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
                List<String> patterns = filterUrlMappings.getOrDefault(name, List.of("/*"));
                List<String> servletNames = filterServletNameMappings.getOrDefault(name, List.of());
                java.util.Set<jakarta.servlet.DispatcherType> dispTypes = filterDispatcherTypes.getOrDefault(name, null);
                boolean asyncSupported = filter.isAsyncSupported();

                Map<String, String> initParams = new HashMap<>();
                if (filter.getInitParam() != null) {
                    filter.getInitParam().forEach(p -> initParams.put(p.getParamName(), p.getParamValue()));
                }

                filterProducer.produce(new FilterBuildItem(
                        name, className, patterns, servletNames,
                        dispTypes, asyncSupported, initParams, 0));
            }
        }
    }

    @BuildStep
    @Record(ExecutionTime.STATIC_INIT)
    void parseSecurityConstraints(ServletRecorder recorder,
            Optional<WebMetadataBuildItem> webMetadataBuildItem,
            ServletDeploymentBuildItem deploymentBuildItem) {
        if (webMetadataBuildItem.isEmpty()) {
            return;
        }
        WebMetaData webMetaData = webMetadataBuildItem.get().getWebMetaData();
        if (webMetaData.getSecurityConstraints() == null) {
            return;
        }
        List<ServletSecurityConstraint> constraints = new ArrayList<>();
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

    @BuildStep
    void registerSecurityPolicy(BuildProducer<AdditionalBeanBuildItem> beans,
            Optional<WebMetadataBuildItem> webMetadataBuildItem) {
        beans.produce(AdditionalBeanBuildItem.unremovableOf(ServletSecurityPolicy.class));
    }

    @BuildStep
    @Record(ExecutionTime.STATIC_INIT)
    ServletDeploymentBuildItem assembleDeployment(
            ServletRecorder recorder,
            ServletBuildTimeConfig buildTimeConfig,
            List<ServletBuildItem> servlets,
            List<FilterBuildItem> filters,
            List<ServletInitParamBuildItem> initParamItems) {

        String contextPath = buildTimeConfig.contextPath();

        Map<String, String> initParams = new HashMap<>();
        for (ServletInitParamBuildItem ip : initParamItems) {
            initParams.put(ip.getKey(), ip.getValue());
        }

        RuntimeValue<ServletDeployment> deployment = recorder.createDeployment(contextPath, initParams);

        for (ServletBuildItem s : servlets) {
            recorder.registerServlet(deployment, s.getName(), s.getServletClass(),
                    s.getMappings(), s.getInitParams(), s.getLoadOnStartup(),
                    s.isAsyncSupported(), s.isRunOnVirtualThread());
        }

        for (FilterBuildItem f : filters) {
            recorder.registerFilter(deployment, f.getName(), f.getFilterClass(),
                    f.getUrlPatterns(), f.getServletNames(), f.getDispatcherTypes(),
                    f.getInitParams(), f.getPriority());
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

        for (ListenerBuildItem l : listenerItems) {
            recorder.registerListener(deploymentBuildItem.getDeployment(), l.getListenerClass());
        }

        recorder.setupSecurityPolicy(deploymentBuildItem.getDeployment());

        Handler<RoutingContext> handler = recorder.boot(
                deploymentBuildItem.getDeployment(), shutdown);

        routes.produce(RouteBuildItem.builder()
                .orderedRoute("/*", Integer.MAX_VALUE - 1)
                .handler(handler).build());

        return new ServiceStartBuildItem("servlet");
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
