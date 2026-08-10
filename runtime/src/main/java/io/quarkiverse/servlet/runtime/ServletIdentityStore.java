package io.quarkiverse.servlet.runtime;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Verifies a username/password and reports the caller's roles, for the declarative authentication
 * described by {@code <login-config>}.
 * <p>
 * Applications running on Quarkus normally authenticate through Quarkus security instead; this
 * exists so the container can satisfy {@code BASIC} and {@code FORM} login on its own.
 */
public interface ServletIdentityStore {

    /**
     * @return the authenticated caller, or {@code null} if the credentials are not valid
     */
    ServletSecurityContext authenticate(String username, String password, String authType);

    /** An identity store that rejects everything. */
    ServletIdentityStore EMPTY = (username, password, authType) -> null;

    /**
     * A fixed set of users held in memory. Useful for tests and for simple deployments that declare
     * their users up front rather than through an external identity provider.
     */
    final class InMemory implements ServletIdentityStore {

        private record User(String password, Set<String> roles) {
        }

        private final Map<String, User> users = new HashMap<>();

        public InMemory add(String username, String password, Set<String> roles) {
            users.put(username, new User(password, Collections.unmodifiableSet(Set.copyOf(roles))));
            return this;
        }

        @Override
        public ServletSecurityContext authenticate(String username, String password, String authType) {
            if (username == null || password == null) {
                return null;
            }
            User user = users.get(username);
            if (user == null || !user.password().equals(password)) {
                return null;
            }
            return new ServletSecurityContext(username, user.roles(), authType);
        }
    }
}
