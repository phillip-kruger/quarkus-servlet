package io.quarkiverse.servlet.runtime;

import java.util.Optional;

import jakarta.servlet.http.HttpSession;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.quarkus.vertx.http.runtime.FormAuthConfig;
import io.quarkus.vertx.http.runtime.security.FormAuthenticationMechanism;
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
 */
public class ServletFormAuthenticationMechanism extends FormAuthenticationMechanism {

    /**
     * Above {@link io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism#DEFAULT_PRIORITY}
     * so this is chosen over the basic mechanism, which Quarkus installs by default whenever its
     * own form authentication is off - as it must be here, since this replaces it.
     */
    private static final int PRIORITY = 2000;

    private static final String SAVED_REQUEST = "io.quarkiverse.servlet.savedRequestUrl";

    public ServletFormAuthenticationMechanism(FormAuthConfig config, Optional<String> encryptionKey) {
        super(config, encryptionKey);
    }

    @Override
    public int getPriority() {
        return PRIORITY;
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
