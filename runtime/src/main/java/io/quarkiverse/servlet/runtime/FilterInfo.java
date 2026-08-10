package io.quarkiverse.servlet.runtime;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;

public class FilterInfo {

    private final String name;
    private final String className;
    private final Map<String, String> initParams;
    private final int priority;
    private final boolean asyncSupported;
    private final List<FilterMapping> mappings = new ArrayList<>();
    private Filter filter;

    public FilterInfo(String name, String className, List<String> urlPatterns,
            Map<String, String> initParams, int priority) {
        this(name, className, urlPatterns, List.of(), EnumSet.of(DispatcherType.REQUEST), initParams, priority);
    }

    public FilterInfo(String name, String className, List<String> urlPatterns,
            List<String> servletNames, Set<DispatcherType> dispatcherTypes,
            Map<String, String> initParams, int priority) {
        this(name, className, urlPatterns, servletNames, dispatcherTypes, initParams, priority, true);
    }

    public FilterInfo(String name, String className, List<String> urlPatterns,
            List<String> servletNames, Set<DispatcherType> dispatcherTypes,
            Map<String, String> initParams, int priority, boolean asyncSupported) {
        this.asyncSupported = asyncSupported;
        this.name = name;
        this.className = className;
        this.initParams = initParams;
        this.priority = priority;
        Set<DispatcherType> types = dispatcherTypes != null && !dispatcherTypes.isEmpty()
                ? EnumSet.copyOf(dispatcherTypes)
                : EnumSet.of(DispatcherType.REQUEST);
        this.mappings.add(new FilterMapping(
                urlPatterns != null ? urlPatterns : List.of(),
                servletNames != null ? servletNames : List.of(),
                types));
    }

    public void addMapping(List<String> urlPatterns, List<String> servletNames,
            Set<DispatcherType> dispatcherTypes) {
        Set<DispatcherType> types = dispatcherTypes != null && !dispatcherTypes.isEmpty()
                ? EnumSet.copyOf(dispatcherTypes)
                : EnumSet.of(DispatcherType.REQUEST);
        this.mappings.add(new FilterMapping(
                urlPatterns != null ? urlPatterns : List.of(),
                servletNames != null ? servletNames : List.of(),
                types));
    }

    /**
     * Whether this filter declared async support. A request only supports async when every filter
     * in its chain and the target servlet all do.
     */
    public boolean isAsyncSupported() {
        return asyncSupported;
    }

    public String getName() {
        return name;
    }

    public String getClassName() {
        return className;
    }

    public Filter getFilter() {
        return filter;
    }

    public void setFilter(Filter filter) {
        this.filter = filter;
    }

    public List<String> getUrlPatterns() {
        List<String> all = new ArrayList<>();
        for (FilterMapping m : mappings) {
            all.addAll(m.urlPatterns());
        }
        return all;
    }

    public List<String> getServletNames() {
        List<String> all = new ArrayList<>();
        for (FilterMapping m : mappings) {
            all.addAll(m.servletNames());
        }
        return all;
    }

    public Set<DispatcherType> getDispatcherTypes() {
        Set<DispatcherType> all = EnumSet.noneOf(DispatcherType.class);
        for (FilterMapping m : mappings) {
            all.addAll(m.dispatcherTypes());
        }
        return all;
    }

    public Map<String, String> getInitParams() {
        return initParams;
    }

    public int getPriority() {
        return priority;
    }

    public boolean matches(String path, String servletName, DispatcherType dispatchType) {
        for (FilterMapping m : mappings) {
            if (!m.dispatcherTypes().contains(dispatchType)) {
                continue;
            }
            for (String pattern : m.urlPatterns()) {
                if (UrlPatternMatcher.matches(pattern, path)) {
                    return true;
                }
            }
            if (servletName != null) {
                for (String sn : m.servletNames()) {
                    if (sn.equals(servletName)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public boolean matchesPath(String requestPath) {
        for (FilterMapping m : mappings) {
            for (String pattern : m.urlPatterns()) {
                if (UrlPatternMatcher.matches(pattern, requestPath)) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean matchesDispatcherType(DispatcherType type) {
        for (FilterMapping m : mappings) {
            if (m.dispatcherTypes().contains(type)) {
                return true;
            }
        }
        return false;
    }

    public record FilterMapping(List<String> urlPatterns, List<String> servletNames,
            Set<DispatcherType> dispatcherTypes) {
    }
}
