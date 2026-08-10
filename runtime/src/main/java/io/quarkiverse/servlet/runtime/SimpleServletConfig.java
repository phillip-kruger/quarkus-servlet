package io.quarkiverse.servlet.runtime;

import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;

public class SimpleServletConfig implements ServletConfig {

    private final String servletName;
    private final ServletContext servletContext;
    private final Map<String, String> initParams;

    public SimpleServletConfig(String servletName, ServletContext servletContext,
            Map<String, String> initParams) {
        this.servletName = servletName;
        this.servletContext = servletContext;
        this.initParams = initParams != null ? initParams : Map.of();
    }

    @Override
    public String getServletName() {
        return servletName;
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
