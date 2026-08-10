package io.quarkiverse.servlet.runtime;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import jakarta.inject.Singleton;

import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.vertx.http.runtime.security.HttpSecurityPolicy;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;

@Singleton
public class ServletSecurityPolicy implements HttpSecurityPolicy {

    private volatile List<ServletSecurityConstraint> constraints = List.of();
    private volatile String contextPath = "/";

    public void setConstraints(List<ServletSecurityConstraint> constraints) {
        this.constraints = constraints;
    }

    public void setContextPath(String contextPath) {
        this.contextPath = contextPath;
    }

    @Override
    public Uni<CheckResult> checkPermission(RoutingContext request,
            Uni<SecurityIdentity> identity,
            AuthorizationRequestContext requestContext) {
        String path = request.normalizedPath();
        if (!"/".equals(contextPath) && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
            if (path.isEmpty()) {
                path = "/";
            }
        }
        String method = request.request().method().name();

        // The servlet spec selects the constraints whose url-pattern matches the request most
        // specifically, not the first one declared. Constraints that tie are combined.
        int bestSpecificity = -1;
        List<ServletSecurityConstraint> applicable = new ArrayList<>();
        for (ServletSecurityConstraint constraint : constraints) {
            if (!constraint.matchesMethod(method)) {
                continue;
            }
            int specificity = constraint.urlSpecificity(path);
            if (specificity < 0 || specificity < bestSpecificity) {
                continue;
            }
            if (specificity > bestSpecificity) {
                bestSpecificity = specificity;
                applicable.clear();
            }
            applicable.add(constraint);
        }

        if (applicable.isEmpty()) {
            return Uni.createFrom().item(CheckResult.PERMIT);
        }

        for (ServletSecurityConstraint constraint : applicable) {
            if (constraint.getTransportGuarantee() == ServletSecurityConstraint.TransportGuarantee.CONFIDENTIAL
                    && !request.request().isSSL()) {
                return Uni.createFrom().item(CheckResult.DENY);
            }
        }

        // An unchecked constraint (no roles, PERMIT semantic) opens the resource regardless of what
        // the others say; a DENY-semantic empty role list closes it to everyone.
        Set<String> rolesAllowed = new HashSet<>();
        boolean unchecked = false;
        for (ServletSecurityConstraint constraint : applicable) {
            if (constraint.getRolesAllowed().isEmpty()) {
                if (constraint.getEmptyRoleSemantic() == ServletSecurityConstraint.EmptyRoleSemantic.DENY) {
                    return Uni.createFrom().item(CheckResult.DENY);
                }
                unchecked = true;
            } else {
                rolesAllowed.addAll(constraint.getRolesAllowed());
            }
        }
        if (unchecked) {
            return Uni.createFrom().item(CheckResult.PERMIT);
        }

        return identity.map(id -> {
            if (id.isAnonymous()) {
                return CheckResult.DENY;
            }
            for (String role : rolesAllowed) {
                if ("*".equals(role) || id.getRoles().contains(role)) {
                    return CheckResult.PERMIT;
                }
            }
            return CheckResult.DENY;
        });
    }
}
