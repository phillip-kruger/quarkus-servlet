package io.quarkiverse.servlet.runtime;

import java.util.Set;
import java.util.stream.Collectors;

import org.jboss.logging.Logger;

import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.credential.PasswordCredential;
import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.UsernamePasswordAuthenticationRequest;

/**
 * Answers {@code <login-config>} authentication out of Quarkus security.
 * <p>
 * The servlet container has to verify a username and password itself for {@code BASIC} and
 * {@code FORM} login, but it has no business owning a user registry. This delegates to whichever
 * {@code IdentityProvider}s the application configured - a properties file, a database, an LDAP
 * directory - so declarative servlet security is fed by the same identity as the rest of the
 * application. Without it the container authenticates nobody and every constrained resource
 * answers 401 regardless of the credentials presented.
 */
public class QuarkusIdentityStore implements ServletIdentityStore {

    private static final Logger log = Logger.getLogger(QuarkusIdentityStore.class);

    private final IdentityProviderManager identityProviderManager;

    public QuarkusIdentityStore(IdentityProviderManager identityProviderManager) {
        this.identityProviderManager = identityProviderManager;
    }

    @Override
    public ServletSecurityContext authenticate(String username, String password, String authType) {
        if (username == null || password == null) {
            return null;
        }
        try {
            SecurityIdentity identity = identityProviderManager
                    .authenticate(new UsernamePasswordAuthenticationRequest(username,
                            new PasswordCredential(password.toCharArray())))
                    .await().indefinitely();
            if (identity == null || identity.isAnonymous()) {
                return null;
            }
            Set<String> roles = identity.getRoles() == null
                    ? Set.of()
                    : identity.getRoles().stream().collect(Collectors.toUnmodifiableSet());
            return new ServletSecurityContext(identity.getPrincipal(), roles, authType);
        } catch (AuthenticationFailedException e) {
            return null;
        } catch (RuntimeException e) {
            // Bad credentials are reported by the providers in more than one way, and a failed
            // login is not an error worth propagating to the caller as a 500.
            log.debugf(e, "Authentication failed for %s", username);
            return null;
        }
    }
}
