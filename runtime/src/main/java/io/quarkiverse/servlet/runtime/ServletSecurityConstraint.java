package io.quarkiverse.servlet.runtime;

import java.util.List;
import java.util.Set;

public class ServletSecurityConstraint {

    private final List<String> urlPatterns;
    private final Set<String> httpMethods;
    private final Set<String> httpMethodOmissions;
    private final Set<String> rolesAllowed;
    private final EmptyRoleSemantic emptyRoleSemantic;
    private final TransportGuarantee transportGuarantee;

    public enum EmptyRoleSemantic {
        PERMIT,
        DENY
    }

    public enum TransportGuarantee {
        NONE,
        CONFIDENTIAL
    }

    public ServletSecurityConstraint(List<String> urlPatterns, Set<String> httpMethods,
            Set<String> httpMethodOmissions, Set<String> rolesAllowed,
            EmptyRoleSemantic emptyRoleSemantic, TransportGuarantee transportGuarantee) {
        this.urlPatterns = urlPatterns;
        this.httpMethods = httpMethods;
        this.httpMethodOmissions = httpMethodOmissions;
        this.rolesAllowed = rolesAllowed;
        this.emptyRoleSemantic = emptyRoleSemantic;
        this.transportGuarantee = transportGuarantee;
    }

    public boolean matchesUrl(String path) {
        for (String pattern : urlPatterns) {
            if (UrlPatternMatcher.matches(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    public boolean matchesMethod(String method) {
        if (!httpMethods.isEmpty()) {
            return httpMethods.contains(method);
        }
        if (!httpMethodOmissions.isEmpty()) {
            return !httpMethodOmissions.contains(method);
        }
        return true;
    }

    public List<String> getUrlPatterns() {
        return urlPatterns;
    }

    public Set<String> getRolesAllowed() {
        return rolesAllowed;
    }

    public EmptyRoleSemantic getEmptyRoleSemantic() {
        return emptyRoleSemantic;
    }

    public TransportGuarantee getTransportGuarantee() {
        return transportGuarantee;
    }
}
