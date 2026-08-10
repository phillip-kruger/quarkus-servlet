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

    private final ConcurrentHashMap<String, SessionImpl> sessions = new ConcurrentHashMap<>();

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
            sessions.remove(id);
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
            // Notify HttpSessionListener.sessionDestroyed in reverse order (LIFO)
            if (servletContext instanceof VertxServletContext vsc) {
                HttpSessionEvent event = new HttpSessionEvent(this);
                var listeners = vsc.getListeners();
                for (int i = listeners.size() - 1; i >= 0; i--) {
                    if (listeners.get(i) instanceof HttpSessionListener hsl) {
                        hsl.sessionDestroyed(event);
                    }
                }
            }
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
