package io.quarkiverse.servlet.spi;

import java.util.List;
import java.util.Map;

import io.quarkiverse.servlet.runtime.ExecutionModel;
import io.quarkiverse.servlet.runtime.MultipartConfiguration;
import io.quarkus.builder.item.MultiBuildItem;

/**
 * Registers a servlet with the servlet container.
 */
public final class ServletBuildItem extends MultiBuildItem {

    private final String name;
    private final String servletClass;
    private final List<String> mappings;
    private final Map<String, String> initParams;
    private final int loadOnStartup;
    private final boolean asyncSupported;
    private final ExecutionModel executionModel;
    private final MultipartConfiguration multipartConfig;

    public ServletBuildItem(String name, String servletClass, List<String> mappings,
            Map<String, String> initParams, int loadOnStartup, boolean asyncSupported) {
        this(name, servletClass, mappings, initParams, loadOnStartup, asyncSupported, null);
    }

    /**
     * @param executionModel the thread the servlet runs on, or {@code null} to use the
     *        deployment-wide default from {@code quarkus.servlet.execution-model}
     */
    public ServletBuildItem(String name, String servletClass, List<String> mappings,
            Map<String, String> initParams, int loadOnStartup, boolean asyncSupported,
            ExecutionModel executionModel) {
        this(name, servletClass, mappings, initParams, loadOnStartup, asyncSupported,
                executionModel, null);
    }

    public ServletBuildItem(String name, String servletClass, List<String> mappings,
            Map<String, String> initParams, int loadOnStartup, boolean asyncSupported,
            ExecutionModel executionModel, MultipartConfiguration multipartConfig) {
        this.name = name;
        this.servletClass = servletClass;
        this.mappings = List.copyOf(mappings);
        this.initParams = initParams != null ? Map.copyOf(initParams) : Map.of();
        this.loadOnStartup = loadOnStartup;
        this.asyncSupported = asyncSupported;
        this.executionModel = executionModel;
        this.multipartConfig = multipartConfig;
    }

    public String getName() {
        return name;
    }

    public String getServletClass() {
        return servletClass;
    }

    public List<String> getMappings() {
        return mappings;
    }

    public Map<String, String> getInitParams() {
        return initParams;
    }

    public int getLoadOnStartup() {
        return loadOnStartup;
    }

    public boolean isAsyncSupported() {
        return asyncSupported;
    }

    /** The servlet's {@code @MultipartConfig}, or {@code null} if it declared none. */
    public MultipartConfiguration getMultipartConfig() {
        return multipartConfig;
    }

    /**
     * The declared execution model, or {@code null} when the deployment-wide default applies.
     */
    public ExecutionModel getExecutionModel() {
        return executionModel;
    }
}
