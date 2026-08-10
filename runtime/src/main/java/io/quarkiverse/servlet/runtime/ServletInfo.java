package io.quarkiverse.servlet.runtime;

import java.util.List;
import java.util.Map;

import jakarta.servlet.Servlet;

public class ServletInfo {

    private final String name;
    private final String className;
    private final List<String> mappings;
    private final Map<String, String> initParams;
    private final int loadOnStartup;
    private final boolean asyncSupported;
    private final boolean runOnVirtualThread;
    private Servlet servlet;
    private boolean initialized;
    private boolean initFailed;
    private boolean permanentlyUnavailable;
    private Exception initException;

    public ServletInfo(String name, String className, List<String> mappings,
            Map<String, String> initParams, int loadOnStartup, boolean asyncSupported) {
        this(name, className, mappings, initParams, loadOnStartup, asyncSupported, false);
    }

    public ServletInfo(String name, String className, List<String> mappings,
            Map<String, String> initParams, int loadOnStartup, boolean asyncSupported,
            boolean runOnVirtualThread) {
        this.name = name;
        this.className = className;
        this.mappings = mappings;
        this.initParams = initParams;
        this.loadOnStartup = loadOnStartup;
        this.asyncSupported = asyncSupported;
        this.runOnVirtualThread = runOnVirtualThread;
    }

    public String getName() {
        return name;
    }

    public String getClassName() {
        return className;
    }

    public Servlet getServlet() {
        return servlet;
    }

    public void setServlet(Servlet servlet) {
        this.servlet = servlet;
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

    public boolean isInitialized() {
        return initialized;
    }

    public void setInitialized(boolean initialized) {
        this.initialized = initialized;
    }

    public boolean isInitFailed() {
        return initFailed;
    }

    public void setInitFailed(boolean initFailed) {
        this.initFailed = initFailed;
    }

    public boolean isPermanentlyUnavailable() {
        return permanentlyUnavailable;
    }

    public void setPermanentlyUnavailable(boolean permanentlyUnavailable) {
        this.permanentlyUnavailable = permanentlyUnavailable;
    }

    public Exception getInitException() {
        return initException;
    }

    public void setInitException(Exception initException) {
        this.initException = initException;
    }
}
