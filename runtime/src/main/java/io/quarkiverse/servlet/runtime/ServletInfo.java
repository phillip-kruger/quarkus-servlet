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
    private final ExecutionModel executionModel;
    private final MultipartConfiguration multipartConfig;
    /** {@code <security-role-ref>}: servlet-local role name to the deployment's role name. */
    private final Map<String, String> securityRoleRefs = new java.util.HashMap<>();
    private Servlet servlet;
    private boolean initialized;
    private boolean initFailed;
    private boolean permanentlyUnavailable;
    private Exception initException;

    public ServletInfo(String name, String className, List<String> mappings,
            Map<String, String> initParams, int loadOnStartup, boolean asyncSupported) {
        this(name, className, mappings, initParams, loadOnStartup, asyncSupported, null);
    }

    public ServletInfo(String name, String className, List<String> mappings,
            Map<String, String> initParams, int loadOnStartup, boolean asyncSupported,
            ExecutionModel executionModel) {
        this(name, className, mappings, initParams, loadOnStartup, asyncSupported,
                executionModel, null);
    }

    public ServletInfo(String name, String className, List<String> mappings,
            Map<String, String> initParams, int loadOnStartup, boolean asyncSupported,
            ExecutionModel executionModel, MultipartConfiguration multipartConfig) {
        this.name = name;
        this.className = className;
        this.mappings = mappings;
        this.initParams = initParams;
        this.loadOnStartup = loadOnStartup;
        this.asyncSupported = asyncSupported;
        this.executionModel = executionModel;
        this.multipartConfig = multipartConfig;
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

    public void addSecurityRoleRef(String roleName, String roleLink) {
        if (roleName != null && roleLink != null) {
            securityRoleRefs.put(roleName, roleLink);
        }
    }

    /**
     * Resolves a role name as used inside this servlet to the deployment role it links to, leaving
     * unmapped names untouched.
     */
    public String resolveRoleRef(String roleName) {
        return securityRoleRefs.getOrDefault(roleName, roleName);
    }

    /** The servlet's {@code @MultipartConfig}, or {@code null} if it declared none. */
    public MultipartConfiguration getMultipartConfig() {
        return multipartConfig;
    }

    /**
     * The execution model declared on the servlet itself, or {@code null} when it declared none and
     * the deployment-wide default applies.
     */
    public ExecutionModel getDeclaredExecutionModel() {
        return executionModel;
    }

    public ExecutionModel getExecutionModel(ExecutionModel deploymentDefault) {
        return executionModel != null ? executionModel : deploymentDefault;
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
