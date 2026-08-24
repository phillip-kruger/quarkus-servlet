package io.quarkiverse.servlet.runtime;

import java.util.Optional;

import jakarta.servlet.http.HttpSession;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.vertx.http.runtime.FormAuthConfig;
import io.quarkus.vertx.http.runtime.security.FormAuthenticationMechanism;
import io.smallrye.mutiny.Uni;
import io.vertx.core.MultiMap;
import io.vertx.ext.web.RoutingContext;

/**
 * FORM authentication that returns the caller to the page they originally asked for.
 * <p>
 * Quarkus and Servlet 6.1 agree on almost all of this: the caller is redirected to the login page,
 * posts {@code j_username} and {@code j_password} to {@code j_security_check}, and is redirected on
 * once authenticated. They disagree only on where the interrupted request is remembered. Quarkus
 * puts it in a dedicated cookie; the spec puts it in the session, and a client that carries only
 * the session cookie - which is all the spec obliges anyone to carry - never returns the other one.
 * When it does not come back, Quarkus can only fall back to its landing page, so the caller is
 * quietly delivered somewhere they never asked for.
 * <p>
 * Overriding the two hooks that read and write that location is the whole change. Credential
 * verification, the encrypted login cookie and the redirect itself are all inherited.
 * <p>
 * The identity is where the TCK's form tests fail against the inherited mechanism alone. Quarkus
 * remembers the authenticated caller in a dedicated {@code quarkus-credential} cookie and reads it
 * back with a case-sensitive lookup. The TCK's HTTP client folds every cookie name it stores to
 * upper case, so the credential comes back as {@code QUARKUS-CREDENTIAL}, the lookup misses it and
 * every request after login is re-challenged. ({@code JSESSIONID} survives only because it is
 * already upper case.) The cookie value itself - the encrypted, still-valid credential - is intact;
 * only its name is wrong. So restore the expected name on the way in and let the inherited flow,
 * which holds the decryption key, verify it as usual.
 */
public class ServletFormAuthenticationMechanism extends FormAuthenticationMechanism {

    /**
     * Above {@link io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism#DEFAULT_PRIORITY}
     * so this is chosen over the basic mechanism, which Quarkus installs by default whenever its
     * own form authentication is off - as it must be here, since this replaces it.
     */
    private static final int PRIORITY = 2000;

    private static final String SAVED_REQUEST = "io.quarkiverse.servlet.savedRequestUrl";

    /** Default name of the encrypted credential cookie Quarkus writes on FORM login. */
    private static final String CREDENTIAL_COOKIE = "quarkus-credential";

    public ServletFormAuthenticationMechanism(FormAuthConfig config, Optional<String> encryptionKey) {
        super(config, encryptionKey);
    }

    @Override
    public int getPriority() {
        return PRIORITY;
    }

    /**
     * Undoes the TCK client's upper-casing of the credential cookie name before the inherited
     * cookie-based flow reads it, then delegates unchanged.
     */
    @Override
    public Uni<SecurityIdentity> authenticate(RoutingContext context, IdentityProviderManager identityProviderManager) {
        restoreCredentialCookieName(context);
        return super.authenticate(context, identityProviderManager);
    }

    /**
     * If the request carries the credential cookie under an upper-cased name but not the exact name
     * the inherited lookup expects, add the expected name back with the same value. The cookies are
     * still raw in the {@code Cookie} header at this point - the inherited {@code restore} is the
     * first to parse them - so rewriting the header is enough for its case-sensitive lookup to hit.
     */
    private void restoreCredentialCookieName(RoutingContext context) {
        MultiMap headers = context.request().headers();
        String header = headers.get(HttpHeaderNames.COOKIE);
        if (header == null || header.contains(CREDENTIAL_COOKIE + "=")) {
            return;
        }
        String mangled = CREDENTIAL_COOKIE.toUpperCase() + "=";
        if (!header.contains(mangled)) {
            return;
        }
        headers.set(HttpHeaderNames.COOKIE, header.replace(mangled, CREDENTIAL_COOKIE + "="));
    }

    @Override
    protected void storeInitialLocation(RoutingContext exchange) {
        HttpSession session = session(exchange, true);
        if (session == null) {
            super.storeInitialLocation(exchange);
            return;
        }
        session.setAttribute(SAVED_REQUEST, exchange.request().absoluteURI());
    }

    @Override
    protected void handleRedirectBack(RoutingContext exchange) {
        HttpSession session = session(exchange, false);
        Object saved = session == null ? null : session.getAttribute(SAVED_REQUEST);
        if (saved == null) {
            // Nothing was interrupted - the caller went to the login page directly - so the
            // inherited landing-page behaviour is the right answer.
            super.handleRedirectBack(exchange);
            return;
        }
        session.removeAttribute(SAVED_REQUEST);
        exchange.response().setStatusCode(302);
        exchange.response().headers().add(HttpHeaderNames.LOCATION, saved.toString());
        exchange.response().end();
    }

    private static HttpSession session(RoutingContext exchange, boolean create) {
        ServletDeployment deployment = ServletRecorder.getCurrentDeployment();
        return deployment == null ? null : ServletSessions.sessionFor(deployment, exchange, create);
    }
}
