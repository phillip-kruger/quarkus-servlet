package io.quarkiverse.servlet.runtime;

import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;

import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletContext;

public class SimpleFilterConfig implements FilterConfig {

    private final String filterName;
    private final ServletContext servletContext;
    private final Map<String, String> initParams;

    public SimpleFilterConfig(String filterName, ServletContext servletContext,
            Map<String, String> initParams) {
        this.filterName = filterName;
        this.servletContext = servletContext;
        this.initParams = initParams != null ? initParams : Map.of();
    }

    @Override
    public String getFilterName() {
        return filterName;
    }

    @Override
    public ServletContext getServletContext() {
        return servletContext;
    }

    @Override
    public String getInitParameter(String name) {
        return initParams.get(name);
    }

    @Override
    public Enumeration<String> getInitParameterNames() {
        return Collections.enumeration(initParams.keySet());
    }
}
