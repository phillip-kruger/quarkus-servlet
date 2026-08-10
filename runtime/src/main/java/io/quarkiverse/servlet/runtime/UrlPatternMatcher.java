package io.quarkiverse.servlet.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.MappingMatch;

public class UrlPatternMatcher {

    private final Map<String, ServletInfo> exactMatches = new HashMap<>();
    private final List<PrefixEntry> prefixMatches = new ArrayList<>();
    private final Map<String, ServletInfo> extensionMatches = new HashMap<>();
    private ServletInfo defaultServlet;

    public void addMapping(String pattern, ServletInfo servletInfo) {
        if ("/".equals(pattern)) {
            defaultServlet = servletInfo;
        } else if (pattern.startsWith("*.")) {
            extensionMatches.putIfAbsent(pattern.substring(1), servletInfo);
        } else if (pattern.endsWith("/*")) {
            String prefix = pattern.substring(0, pattern.length() - 2);
            prefixMatches.add(new PrefixEntry(prefix, servletInfo));
            prefixMatches.sort((a, b) -> Integer.compare(b.prefix.length(), a.prefix.length()));
        } else {
            if (!pattern.startsWith("/")) {
                pattern = "/" + pattern;
            }
            exactMatches.putIfAbsent(pattern, servletInfo);
        }
    }

    public MatchResult match(String requestPath) {
        if ("/".equals(requestPath) || requestPath.isEmpty()) {
            ServletInfo servlet = exactMatches.get("/");
            if (servlet != null) {
                return new MatchResult(servlet, requestPath, null,
                        MappingMatch.CONTEXT_ROOT, "/");
            }
        }

        ServletInfo servlet = exactMatches.get(requestPath);
        if (servlet != null) {
            return new MatchResult(servlet, requestPath, null,
                    MappingMatch.EXACT, requestPath);
        }

        for (PrefixEntry entry : prefixMatches) {
            if (requestPath.equals(entry.prefix) || requestPath.startsWith(entry.prefix + "/")) {
                String pathInfo = requestPath.substring(entry.prefix.length());
                if (pathInfo.isEmpty()) {
                    pathInfo = null;
                }
                return new MatchResult(entry.servletInfo, entry.prefix, pathInfo,
                        MappingMatch.PATH, entry.prefix + "/*");
            }
        }

        int dotIndex = requestPath.lastIndexOf('.');
        if (dotIndex >= 0) {
            String extension = requestPath.substring(dotIndex);
            servlet = extensionMatches.get(extension);
            if (servlet != null) {
                return new MatchResult(servlet, requestPath, null,
                        MappingMatch.EXTENSION, "*" + extension);
            }
        }

        if (defaultServlet != null) {
            return new MatchResult(defaultServlet, "", requestPath,
                    MappingMatch.DEFAULT, "/");
        }

        return null;
    }

    public static boolean matches(String pattern, String requestPath) {
        if ("/".equals(pattern) || "/*".equals(pattern)) {
            return true;
        }
        if (pattern.startsWith("*.")) {
            String extension = pattern.substring(1);
            return requestPath.endsWith(extension);
        }
        if (pattern.endsWith("/*")) {
            String prefix = pattern.substring(0, pattern.length() - 2);
            return requestPath.equals(prefix) || requestPath.startsWith(prefix + "/");
        }
        return pattern.equals(requestPath);
    }

    private record PrefixEntry(String prefix, ServletInfo servletInfo) {
    }

    public static class MatchResult {
        private final ServletInfo servletInfo;
        private final String servletPath;
        private final String pathInfo;
        private final MappingMatch mappingMatch;
        private final String matchedPattern;

        public MatchResult(ServletInfo servletInfo, String servletPath, String pathInfo,
                MappingMatch mappingMatch, String matchedPattern) {
            this.servletInfo = servletInfo;
            this.servletPath = servletPath;
            this.pathInfo = pathInfo;
            this.mappingMatch = mappingMatch;
            this.matchedPattern = matchedPattern;
        }

        public ServletInfo getServletInfo() {
            return servletInfo;
        }

        public String getServletPath() {
            return servletPath;
        }

        public String getPathInfo() {
            return pathInfo;
        }

        public MappingMatch getMappingMatch() {
            return mappingMatch;
        }

        public String getMatchedPattern() {
            return matchedPattern;
        }
    }
}
