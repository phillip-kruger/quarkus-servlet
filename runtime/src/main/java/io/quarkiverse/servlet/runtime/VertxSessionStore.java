package io.quarkiverse.servlet.runtime;

import java.util.Collections;
import java.util.Enumeration;
import java.util.EventListener;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionAttributeListener;
import jakarta.servlet.http.HttpSessionBindingEvent;
import jakarta.servlet.http.HttpSessionBindingListener;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;

public class VertxSessionStore {

    /** How often idle sessions are swept out, in milliseconds. */
    private static final long REAPER_INTERVAL_MS = 60_000;

    private final ConcurrentHashMap<String, SessionImpl> sessions = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicBoolean reaperStarted = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile Long reaperTimerId;
    private volatile io.vertx.core.Vertx vertx;

    /**
     * Starts the periodic sweep of timed-out sessions. Without it, sessions belonging to clients
     * that never return would accumulate for the lifetime of the process and their
     * {@code sessionDestroyed} events would never fire.
     * <p>
     * Idempotent, because it is called from the request path: the Vert.x instance is not available
     * when the deployment boots.
     */
    public void startExpiryReaper(io.vertx.core.Vertx vertx) {
        if (vertx == null || !reaperStarted.compareAndSet(false, true)) {
            return;
        }
        this.vertx = vertx;
        this.reaperTimerId = vertx.setPeriodic(REAPER_INTERVAL_MS, id -> reapExpiredSessions());
    }

    public void stopExpiryReaper() {
        Long id = reaperTimerId;
        if (id != null && vertx != null) {
            vertx.cancelTimer(id);
            reaperTimerId = null;
        }
    }

    /**
     * Removes every session whose idle timeout has elapsed, firing {@code sessionDestroyed} for
     * each one just as an explicit {@code invalidate()} would.
     */
    void reapExpiredSessions() {
        for (SessionImpl session : sessions.values()) {
            if (!session.isValid()) {
                expire(session);
            }
        }
    }

    private void expire(SessionImpl session) {
        if (sessions.remove(session.getId(), session)) {
            try {
                session.expire();
            } catch (RuntimeException e) {
                // a misbehaving listener must not stop the sweep
                java.util.logging.Logger.getLogger(VertxSessionStore.class.getName())
                        .log(java.util.logging.Level.WARNING, "Error expiring session", e);
            }
        }
    }

    public HttpSession getSession(String id) {
        if (id == null) {
            return null;
        }
        SessionImpl session = sessions.get(id);
        if (session != null && session.isValid()) {
            session.markNotNew();
            return session;
        }
        if (session != null) {
            expire(session);
        }
        return null;
    }

    public void endAccess(HttpSession session) {
        if (session instanceof SessionImpl si) {
            si.access();
        }
    }

    public void changeSessionId(String oldId, String newId) {
        SessionImpl session = sessions.remove(oldId);
        if (session != null) {
            session.setId(newId);
            sessions.put(newId, session);
        }
    }

    public HttpSession createSession(ServletContext servletContext) {
        String id = UUID.randomUUID().toString();
        SessionImpl session = new SessionImpl(id, servletContext);
        if (servletContext instanceof VertxServletContext vsc) {
            session.setMaxInactiveInterval(vsc.getSessionTimeout() * 60);
        }
        sessions.put(id, session);
        // Notify HttpSessionListener.sessionCreated
        if (servletContext instanceof VertxServletContext vsc) {
            HttpSessionEvent event = new HttpSessionEvent(session);
            for (EventListener l : vsc.getListeners()) {
                if (l instanceof HttpSessionListener hsl) {
                    hsl.sessionCreated(event);
                }
            }
        }
        return session;
    }

    public void removeSession(String id) {
        sessions.remove(id);
    }

    public int getSessionCount() {
        return sessions.size();
    }

    public java.util.Collection<SessionImpl> getAllSessions() {
        return sessions.values();
    }

    public static class SessionImpl implements HttpSession {

        private String id;
        private final long creationTime;
        private final ServletContext servletContext;
        private final ConcurrentHashMap<String, Object> attributes = new ConcurrentHashMap<>();
        private volatile long lastAccessedTime;
        private volatile int maxInactiveInterval = 1800;
        private volatile boolean valid = true;
        private volatile boolean isNew = true;
        private final java.util.concurrent.atomic.AtomicBoolean destroyed = new java.util.concurrent.atomic.AtomicBoolean();

        SessionImpl(String id, ServletContext servletContext) {
            this.id = id;
            this.creationTime = System.currentTimeMillis();
            this.lastAccessedTime = creationTime;
            this.servletContext = servletContext;
        }

        void setId(String id) {
            this.id = id;
        }

        void markNotNew() {
            isNew = false;
        }

        void access() {
            lastAccessedTime = System.currentTimeMillis();
        }

        boolean isValid() {
            if (!valid) {
                return false;
            }
            if (maxInactiveInterval > 0) {
                long elapsed = (System.currentTimeMillis() - lastAccessedTime) / 1000;
                return elapsed < maxInactiveInterval;
            }
            return true;
        }

        @Override
        public long getCreationTime() {
            checkValid();
            return creationTime;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public long getLastAccessedTime() {
            checkValid();
            return lastAccessedTime;
        }

        @Override
        public ServletContext getServletContext() {
            return servletContext;
        }

        @Override
        public void setMaxInactiveInterval(int interval) {
            this.maxInactiveInterval = interval;
        }

        @Override
        public int getMaxInactiveInterval() {
            return maxInactiveInterval;
        }

        @Override
        public Object getAttribute(String name) {
            checkValid();
            return attributes.get(name);
        }

        @Override
        public Enumeration<String> getAttributeNames() {
            checkValid();
            return Collections.enumeration(attributes.keySet());
        }

        @Override
        public void setAttribute(String name, Object value) {
            checkValid();
            if (value == null) {
                removeAttribute(name);
                return;
            }
            Object old = attributes.put(name, value);
            // Notify binding listeners
            if (value instanceof HttpSessionBindingListener hsbl) {
                hsbl.valueBound(new HttpSessionBindingEvent(this, name, value));
            }
            if (old instanceof HttpSessionBindingListener hsbl) {
                hsbl.valueUnbound(new HttpSessionBindingEvent(this, name, old));
            }
            // Notify attribute listeners
            if (servletContext instanceof VertxServletContext vsc) {
                HttpSessionBindingEvent event = new HttpSessionBindingEvent(this, name, old != null ? old : value);
                for (EventListener l : vsc.getListeners()) {
                    if (l instanceof HttpSessionAttributeListener hsal) {
                        if (old == null) {
                            hsal.attributeAdded(event);
                        } else {
                            hsal.attributeReplaced(event);
                        }
                    }
                }
            }
        }

        @Override
        public void removeAttribute(String name) {
            checkValid();
            Object old = attributes.remove(name);
            if (old != null) {
                // Notify binding listener
                if (old instanceof HttpSessionBindingListener hsbl) {
                    hsbl.valueUnbound(new HttpSessionBindingEvent(this, name, old));
                }
                // Notify attribute listeners
                if (servletContext instanceof VertxServletContext vsc) {
                    HttpSessionBindingEvent event = new HttpSessionBindingEvent(this, name, old);
                    for (EventListener l : vsc.getListeners()) {
                        if (l instanceof HttpSessionAttributeListener hsal) {
                            hsal.attributeRemoved(event);
                        }
                    }
                }
            }
        }

        @Override
        public void invalidate() {
            checkValid();
            destroy();
        }

        /**
         * Destroys a session that timed out rather than being explicitly invalidated. Listeners see
         * exactly what they would for {@link #invalidate()}.
         */
        void expire() {
            destroy();
        }

        /**
         * Servlet 6.1, 11.3.3: {@code sessionDestroyed} is delivered <em>before</em> the session is
         * invalidated, and attribute unbinding follows it. The order is not cosmetic - a listener
         * is expected to read the session during {@code sessionDestroyed}, and the CDI session
         * context does exactly that to find the beans it has to destroy. Invalidating first makes
         * every one of those reads throw.
         */
        private void destroy() {
            if (!destroyed.compareAndSet(false, true)) {
                return;
            }
            if (servletContext instanceof VertxServletContext vsc) {
                HttpSessionEvent event = new HttpSessionEvent(this);
                var listeners = vsc.getListeners();
                for (int i = listeners.size() - 1; i >= 0; i--) {
                    if (listeners.get(i) instanceof HttpSessionListener hsl) {
                        hsl.sessionDestroyed(event);
                    }
                }
            }

            valid = false;

            // Unbind all attributes that implement HttpSessionBindingListener
            for (var entry : attributes.entrySet()) {
                if (entry.getValue() instanceof HttpSessionBindingListener hsbl) {
                    hsbl.valueUnbound(new HttpSessionBindingEvent(this, entry.getKey(), entry.getValue()));
                }
            }
            // Notify attribute listeners for each removed attribute
            if (servletContext instanceof VertxServletContext vsc) {
                for (var entry : attributes.entrySet()) {
                    HttpSessionBindingEvent event = new HttpSessionBindingEvent(this, entry.getKey(), entry.getValue());
                    for (EventListener l : vsc.getListeners()) {
                        if (l instanceof HttpSessionAttributeListener hsal) {
                            hsal.attributeRemoved(event);
                        }
                    }
                }
            }
            attributes.clear();
        }

        @Override
        public boolean isNew() {
            checkValid();
            return isNew;
        }

        private void checkValid() {
            if (!valid) {
                throw new IllegalStateException("Session has been invalidated");
            }
        }
    }
}
