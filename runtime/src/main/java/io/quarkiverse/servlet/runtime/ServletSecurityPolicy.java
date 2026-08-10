package io.quarkiverse.servlet.runtime;

import java.util.List;

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

        for (ServletSecurityConstraint constraint : constraints) {
            if (constraint.matchesUrl(path) && constraint.matchesMethod(method)) {
                if (constraint.getTransportGuarantee() == ServletSecurityConstraint.TransportGuarantee.CONFIDENTIAL
                        && !request.request().isSSL()) {
                    return Uni.createFrom().item(CheckResult.DENY);
                }

                if (constraint.getRolesAllowed().isEmpty()) {
                    if (constraint.getEmptyRoleSemantic() == ServletSecurityConstraint.EmptyRoleSemantic.DENY) {
                        return Uni.createFrom().item(CheckResult.DENY);
                    }
                    return Uni.createFrom().item(CheckResult.PERMIT);
                }

                return identity.map(id -> {
                    if (id.isAnonymous()) {
                        return CheckResult.DENY;
                    }
                    for (String role : constraint.getRolesAllowed()) {
                        if ("*".equals(role) || id.getRoles().contains(role)) {
                            return CheckResult.PERMIT;
                        }
                    }
                    return CheckResult.DENY;
                });
            }
        }

        return Uni.createFrom().item(CheckResult.PERMIT);
    }
}
