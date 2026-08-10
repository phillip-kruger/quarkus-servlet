package io.quarkiverse.servlet.spi;

import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.DispatcherType;

import io.quarkus.builder.item.MultiBuildItem;

public final class FilterBuildItem extends MultiBuildItem {

    private final String name;
    private final String filterClass;
    private final List<String> urlPatterns;
    private final List<String> servletNames;
    private final Set<DispatcherType> dispatcherTypes;
    private final boolean asyncSupported;
    private final Map<String, String> initParams;
    private final int priority;

    public FilterBuildItem(String name, String filterClass, List<String> urlPatterns,
            Set<DispatcherType> dispatcherTypes, boolean asyncSupported,
            Map<String, String> initParams, int priority) {
        this(name, filterClass, urlPatterns, List.of(), dispatcherTypes, asyncSupported,
                initParams, priority);
    }

    public FilterBuildItem(String name, String filterClass, List<String> urlPatterns,
            List<String> servletNames, Set<DispatcherType> dispatcherTypes,
            boolean asyncSupported, Map<String, String> initParams, int priority) {
        this.name = name;
        this.filterClass = filterClass;
        this.urlPatterns = urlPatterns != null ? List.copyOf(urlPatterns) : List.of();
        this.servletNames = servletNames != null ? List.copyOf(servletNames) : List.of();
        this.dispatcherTypes = dispatcherTypes != null ? Set.copyOf(dispatcherTypes)
                : Set.of(DispatcherType.REQUEST);
        this.asyncSupported = asyncSupported;
        this.initParams = initParams != null ? Map.copyOf(initParams) : Map.of();
        this.priority = priority;
    }

    public String getName() {
        return name;
    }

    public String getFilterClass() {
        return filterClass;
    }

    public List<String> getUrlPatterns() {
        return urlPatterns;
    }

    public Set<DispatcherType> getDispatcherTypes() {
        return dispatcherTypes;
    }

    public boolean isAsyncSupported() {
        return asyncSupported;
    }

    public Map<String, String> getInitParams() {
        return initParams;
    }

    public List<String> getServletNames() {
        return servletNames;
    }

    public int getPriority() {
        return priority;
    }
}
