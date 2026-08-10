package io.quarkiverse.servlet.runtime.devui;

import java.util.EventListener;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.servlet.http.HttpSession;

import io.quarkiverse.servlet.runtime.FilterInfo;
import io.quarkiverse.servlet.runtime.ServletDeployment;
import io.quarkiverse.servlet.runtime.ServletInfo;
import io.quarkiverse.servlet.runtime.ServletRecorder;
import io.quarkiverse.servlet.runtime.VertxSessionStore;
import io.quarkus.runtime.annotations.DevMCPEnableByDefault;
import io.quarkus.runtime.annotations.JsonRpcDescription;
import io.smallrye.common.annotation.NonBlocking;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

@ApplicationScoped
public class ServletJsonRPCService {

    private ServletDeployment deployment() {
        return ServletRecorder.getCurrentDeployment();
    }

    @NonBlocking
    @DevMCPEnableByDefault
    @JsonRpcDescription("List all registered servlets with their URL mappings, class names, and status")
    public JsonArray getServlets() {
        ServletDeployment dep = deployment();
        if (dep == null) {
            return new JsonArray();
        }
        JsonArray result = new JsonArray();
        for (ServletInfo info : dep.getServlets().values()) {
            result.add(new JsonObject()
                    .put("name", info.getName())
                    .put("className", info.getClassName())
                    .put("mappings", new JsonArray(info.getMappings()))
                    .put("loadOnStartup", info.getLoadOnStartup())
                    .put("asyncSupported", info.isAsyncSupported())
                    .put("runOnVirtualThread", info.isRunOnVirtualThread())
                    .put("initialized", info.isInitialized())
                    .put("initFailed", info.isInitFailed()));
        }
        return result;
    }

    @NonBlocking
    @DevMCPEnableByDefault
    @JsonRpcDescription("List all registered servlet filters with their URL patterns and priority")
    public JsonArray getFilters() {
        ServletDeployment dep = deployment();
        if (dep == null) {
            return new JsonArray();
        }
        JsonArray result = new JsonArray();
        for (FilterInfo info : dep.getFilters()) {
            result.add(new JsonObject()
                    .put("name", info.getName())
                    .put("className", info.getClassName())
                    .put("priority", info.getPriority()));
        }
        return result;
    }

    @NonBlocking
    @DevMCPEnableByDefault
    @JsonRpcDescription("Get servlet context info including context path, init parameters, and welcome files")
    public JsonObject getServletContext() {
        ServletDeployment dep = deployment();
        if (dep == null) {
            return new JsonObject();
        }
        JsonObject result = new JsonObject()
                .put("contextPath", dep.getServletContext().getContextPath())
                .put("serverInfo", dep.getServletContext().getServerInfo())
                .put("majorVersion", dep.getServletContext().getMajorVersion())
                .put("minorVersion", dep.getServletContext().getMinorVersion())
                .put("welcomeFiles", new JsonArray(dep.getWelcomeFiles()));

        JsonObject initParams = new JsonObject();
        var paramNames = dep.getServletContext().getInitParameterNames();
        while (paramNames.hasMoreElements()) {
            String name = paramNames.nextElement();
            initParams.put(name, dep.getServletContext().getInitParameter(name));
        }
        result.put("initParameters", initParams);

        JsonObject errorPages = new JsonObject();
        for (Map.Entry<Integer, String> entry : dep.getErrorPages().entrySet()) {
            errorPages.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        result.put("errorPages", errorPages);

        JsonArray listeners = new JsonArray();
        for (EventListener l : dep.getServletContext().getListeners()) {
            listeners.add(l.getClass().getName());
        }
        result.put("listeners", listeners);

        return result;
    }

    @NonBlocking
    @JsonRpcDescription("List active HTTP sessions with their IDs, creation time, and last accessed time")
    public JsonArray getActiveSessions() {
        ServletDeployment dep = deployment();
        if (dep == null) {
            return new JsonArray();
        }
        JsonArray result = new JsonArray();
        for (VertxSessionStore.SessionImpl session : dep.getSessionStore().getAllSessions()) {
            try {
                result.add(new JsonObject()
                        .put("id", session.getId())
                        .put("creationTime", session.getCreationTime())
                        .put("lastAccessedTime", session.getLastAccessedTime())
                        .put("maxInactiveInterval", session.getMaxInactiveInterval())
                        .put("isNew", session.isNew()));
            } catch (IllegalStateException e) {
                // session invalidated concurrently
            }
        }
        return result;
    }

    @NonBlocking
    @JsonRpcDescription("Invalidate an HTTP session by its ID")
    public JsonObject invalidateSession(
            @JsonRpcDescription("The session ID to invalidate") String sessionId) {
        ServletDeployment dep = deployment();
        if (dep == null) {
            return new JsonObject().put("success", false).put("message", "No deployment");
        }
        HttpSession session = dep.getSessionStore().getSession(sessionId);
        if (session == null) {
            return new JsonObject().put("success", false).put("message", "Session not found");
        }
        session.invalidate();
        return new JsonObject().put("success", true).put("message", "Session invalidated");
    }
}
