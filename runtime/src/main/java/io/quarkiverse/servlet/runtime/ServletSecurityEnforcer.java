package io.quarkiverse.servlet.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * Applies the deployment's {@code <security-constraint>}s and {@code <login-config>} to a request,
 * before any servlet sees it.
 * <p>
 * Constraint selection follows the spec's matching rules rather than declaration order - the most
 * specific url-pattern wins, and constraints that tie are combined - so a broad {@code /*}
 * constraint cannot accidentally override a narrower one.
 */
public final class ServletSecurityEnforcer {

    /** Where the container stashes the authenticated caller across a FORM login. */
    private static final String SESSION_PRINCIPAL = "io.quarkiverse.servlet.principal";
    /** Where the container remembers the URL the caller originally asked for. */
    private static final String SESSION_SAVED_REQUEST = "io.quarkiverse.servlet.savedRequest";

    private static final String FORM_ACTION = "/j_security_check";

    /** What the container decided to do with a request. */
    public enum Outcome {
        /** Let the request proceed to the servlet. */
        PROCEED,
        /** The response has been completed by the enforcer; do not invoke the servlet. */
        HANDLED
    }

    private ServletSecurityEnforcer() {
    }

    /**
     * Authenticates and authorises {@code request}, writing a challenge, redirect or error to
     * {@code response} when the request must not reach the servlet.
     *
     * @param path the request path relative to the context root
     */
    public static Outcome enforce(ServletDeployment deployment, VertxServletRequest request,
            VertxServletResponse response, String path, String method) throws IOException {

        LoginConfig loginConfig = deployment.getLoginConfig();

        // A FORM login submission is handled by the container itself, never by a servlet.
        if (loginConfig != null && loginConfig.isForm() && path.endsWith(FORM_ACTION)) {
            return handleFormLogin(deployment, request, response, loginConfig);
        }

        restoreAuthenticatedSession(request);

        List<ServletSecurityConstraint> applicable = selectConstraints(
                deployment.getSecurityConstraints(), path, method);
        if (applicable.isEmpty()) {
            return Outcome.PROCEED;
        }

        for (ServletSecurityConstraint constraint : applicable) {
            if (constraint.getTransportGuarantee() == ServletSecurityConstraint.TransportGuarantee.CONFIDENTIAL
                    && !request.isSecure()) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN);
                return Outcome.HANDLED;
            }
        }

        // An unchecked constraint opens the resource; an empty role list with DENY closes it.
        Set<String> rolesAllowed = new HashSet<>();
        boolean unchecked = false;
        for (ServletSecurityConstraint constraint : applicable) {
            if (constraint.getRolesAllowed().isEmpty()) {
                if (constraint.getEmptyRoleSemantic() == ServletSecurityConstraint.EmptyRoleSemantic.DENY) {
                    response.sendError(HttpServletResponse.SC_FORBIDDEN);
                    return Outcome.HANDLED;
                }
                unchecked = true;
            } else {
                rolesAllowed.addAll(constraint.getRolesAllowed());
            }
        }
        if (unchecked) {
            return Outcome.PROCEED;
        }

        // The resource needs a role, so the caller has to be authenticated first.
        ServletSecurityContext caller = request.getServletSecurityContext();
        if (caller == null) {
            caller = authenticateFromRequest(deployment, request, loginConfig);
            if (caller != null) {
                request.setServletSecurityContext(caller);
            }
        }
        if (caller == null) {
            return challenge(deployment, request, response, loginConfig);
        }

        for (String role : rolesAllowed) {
            if ("*".equals(role) || caller.hasRole(role)) {
                return Outcome.PROCEED;
            }
        }
        response.sendError(HttpServletResponse.SC_FORBIDDEN);
        return Outcome.HANDLED;
    }

    /**
     * Selects the constraints whose url-pattern matches most specifically. Constraints that do not
     * cover the request's method are ignored, which is what leaves a method "uncovered".
     */
    private static List<ServletSecurityConstraint> selectConstraints(
            List<ServletSecurityConstraint> constraints, String path, String method) {

        int best = -1;
        List<ServletSecurityConstraint> applicable = new java.util.ArrayList<>();
        for (ServletSecurityConstraint constraint : constraints) {
            if (!constraint.matchesMethod(method)) {
                continue;
            }
            int specificity = constraint.urlSpecificity(path);
            if (specificity < 0 || specificity < best) {
                continue;
            }
            if (specificity > best) {
                best = specificity;
                applicable.clear();
            }
            applicable.add(constraint);
        }
        return applicable;
    }

    /** Re-establishes a caller authenticated earlier in the same session. */
    private static void restoreAuthenticatedSession(VertxServletRequest request) {
        if (request.getServletSecurityContext() != null) {
            return;
        }
        HttpSession session = request.getSession(false);
        if (session == null) {
            return;
        }
        Object stored = session.getAttribute(SESSION_PRINCIPAL);
        if (stored instanceof ServletSecurityContext context) {
            request.setServletSecurityContext(context);
        }
    }

    /** BASIC credentials travel on every request, so they are read here rather than challenged for. */
    private static ServletSecurityContext authenticateFromRequest(ServletDeployment deployment,
            VertxServletRequest request, LoginConfig loginConfig) {

        if (loginConfig == null || !loginConfig.isBasic()) {
            return null;
        }
        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Basic ", 0, 6)) {
            return null;
        }
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(header.substring(6).trim()),
                    StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
        int colon = decoded.indexOf(':');
        if (colon < 0) {
            return null;
        }
        return deployment.getIdentityStore().authenticate(
                decoded.substring(0, colon), decoded.substring(colon + 1), LoginConfig.BASIC);
    }

    private static Outcome challenge(ServletDeployment deployment, VertxServletRequest request,
            VertxServletResponse response, LoginConfig loginConfig) throws IOException {

        if (loginConfig != null && loginConfig.isForm() && loginConfig.formLoginPage() != null) {
            // Remember where the caller was heading so the login can return them to it.
            HttpSession session = request.getSession(true);
            session.setAttribute(SESSION_SAVED_REQUEST, savedRequestUrl(request));
            forward(deployment, request, response, loginConfig.formLoginPage());
            return Outcome.HANDLED;
        }

        String realm = (loginConfig != null && loginConfig.realmName() != null)
                ? loginConfig.realmName()
                : "default";
        response.setHeader("WWW-Authenticate", "Basic realm=\"" + realm + "\"");
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
        return Outcome.HANDLED;
    }

    private static Outcome handleFormLogin(ServletDeployment deployment, VertxServletRequest request,
            VertxServletResponse response, LoginConfig loginConfig) throws IOException {

        String username = request.getParameter("j_username");
        String password = request.getParameter("j_password");
        ServletSecurityContext caller = deployment.getIdentityStore()
                .authenticate(username, password, LoginConfig.FORM);

        if (caller == null) {
            if (loginConfig.formErrorPage() != null) {
                forward(deployment, request, response, loginConfig.formErrorPage());
            } else {
                response.sendError(HttpServletResponse.SC_FORBIDDEN);
            }
            return Outcome.HANDLED;
        }

        // A successful login gets a fresh session id, so a session fixed before authentication
        // cannot be reused afterwards.
        HttpSession session = request.getSession(true);
        Object saved = session.getAttribute(SESSION_SAVED_REQUEST);
        request.changeSessionId();
        session = request.getSession(true);
        session.setAttribute(SESSION_PRINCIPAL, caller);
        session.removeAttribute(SESSION_SAVED_REQUEST);
        request.setServletSecurityContext(caller);

        response.sendRedirect(saved instanceof String url ? url : request.getContextPath() + "/");
        return Outcome.HANDLED;
    }

    private static String savedRequestUrl(VertxServletRequest request) {
        String query = request.getQueryString();
        return request.getRequestURI() + (query != null ? "?" + query : "");
    }

    private static void forward(ServletDeployment deployment, VertxServletRequest request,
            VertxServletResponse response, String page) throws IOException {
        try {
            request.getRequestDispatcher(page).forward(request, response);
        } catch (Exception e) {
            throw new IOException("Failed to show the login page " + page, e);
        }
    }

    /** Clears the caller, both on the request and in the session. */
    public static void logout(VertxServletRequest request) {
        request.setServletSecurityContext(null);
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(SESSION_PRINCIPAL);
        }
    }

    /** Records a caller established through {@code HttpServletRequest.login()}. */
    public static void rememberLogin(VertxServletRequest request, ServletSecurityContext caller) {
        request.setServletSecurityContext(caller);
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.setAttribute(SESSION_PRINCIPAL, caller);
        }
    }
}
