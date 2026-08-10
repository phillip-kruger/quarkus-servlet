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
        return urlSpecificity(path) >= 0;
    }

    /**
     * How specifically this constraint's most specific matching pattern matches {@code path}, or
     * {@code -1} when none of its patterns match.
     */
    public int urlSpecificity(String path) {
        int best = -1;
        for (String pattern : urlPatterns) {
            best = Math.max(best, UrlPatternMatcher.specificity(pattern, path));
        }
        return best;
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

    // These two are read by matchesMethod above, so nothing in this class needs them. They exist
    // because the Quarkus bytecode recorder reconstructs this object from its constructor and
    // reads each parameter's value through the matching getter - without them, recording a
    // <security-constraint> fails the build outright.
    public Set<String> getHttpMethods() {
        return httpMethods;
    }

    public Set<String> getHttpMethodOmissions() {
        return httpMethodOmissions;
    }

    public EmptyRoleSemantic getEmptyRoleSemantic() {
        return emptyRoleSemantic;
    }

    public TransportGuarantee getTransportGuarantee() {
        return transportGuarantee;
    }
}
