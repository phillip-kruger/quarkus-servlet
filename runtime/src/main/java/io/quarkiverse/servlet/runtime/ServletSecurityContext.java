package io.quarkiverse.servlet.runtime;

import java.security.Principal;
import java.util.Set;

/**
 * The authenticated caller for a request, as the servlet container sees it.
 * <p>
 * This is deliberately independent of Quarkus security: the container needs a principal it can
 * establish itself for declarative {@code <login-config>} authentication, while
 * {@link VertxServletRequest} still falls back to the Quarkus {@code SecurityIdentity} when the
 * application is authenticated by Quarkus instead.
 *
 * @param principal the caller, never {@code null}
 * @param roles roles granted to the caller
 * @param authType one of {@code BASIC}, {@code FORM}, {@code CLIENT_CERT} or {@code DIGEST}
 */
public record ServletSecurityContext(Principal principal, Set<String> roles, String authType) {

    public ServletSecurityContext(String name, Set<String> roles, String authType) {
        this(new SimplePrincipal(name), roles, authType);
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    /** A principal that is nothing more than a name, which is all the servlet API exposes. */
    public record SimplePrincipal(String name) implements Principal {

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String toString() {
            return name;
        }
    }
}
