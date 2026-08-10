package io.quarkiverse.servlet.spi;

import java.util.List;
import java.util.Map;

import io.quarkus.builder.item.MultiBuildItem;

public final class ServletBuildItem extends MultiBuildItem {

    private final String name;
    private final String servletClass;
    private final List<String> mappings;
    private final Map<String, String> initParams;
    private final int loadOnStartup;
    private final boolean asyncSupported;
    private final boolean runOnVirtualThread;

    public ServletBuildItem(String name, String servletClass, List<String> mappings,
            Map<String, String> initParams, int loadOnStartup, boolean asyncSupported) {
        this(name, servletClass, mappings, initParams, loadOnStartup, asyncSupported, false);
    }

    public ServletBuildItem(String name, String servletClass, List<String> mappings,
            Map<String, String> initParams, int loadOnStartup, boolean asyncSupported,
            boolean runOnVirtualThread) {
        this.name = name;
        this.servletClass = servletClass;
        this.mappings = List.copyOf(mappings);
        this.initParams = initParams != null ? Map.copyOf(initParams) : Map.of();
        this.loadOnStartup = loadOnStartup;
        this.asyncSupported = asyncSupported;
        this.runOnVirtualThread = runOnVirtualThread;
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

    public boolean isRunOnVirtualThread() {
        return runOnVirtualThread;
    }
}
