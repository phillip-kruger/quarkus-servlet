package io.quarkiverse.servlet.runtime;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.nio.file.Path;
import java.security.Principal;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.EventListener;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletConnection;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestAttributeEvent;
import jakarta.servlet.ServletRequestAttributeListener;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletMapping;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpUpgradeHandler;
import jakarta.servlet.http.MappingMatch;
import jakarta.servlet.http.Part;

import io.quarkus.security.identity.CurrentIdentityAssociation;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.UsernamePasswordAuthenticationRequest;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticator;
import io.quarkus.vertx.http.runtime.security.QuarkusHttpUser;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpVersion;
import io.vertx.ext.web.RoutingContext;

public class VertxServletRequest implements HttpServletRequest {

    private static final org.jboss.logging.Logger log = org.jboss.logging.Logger.getLogger(VertxServletRequest.class);

    private static final AtomicLong REQUEST_ID_COUNTER = new AtomicLong(0);

    private static final String[] HTTP_DATE_FORMATS = {
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "EEEEEE, dd-MMM-yy HH:mm:ss zzz",
            "EEE MMMM d HH:mm:ss yyyy"
    };

    private final RoutingContext routingContext;
    private final HttpServerRequest request;
    private final VertxServletContext servletContext;
    private String servletPath;
    private String pathInfo;
    private String forwardedRequestURI;
    private String forwardedQueryString;
    private final String contextPath;
    private final String requestId;
    private final Map<String, Object> attributes = new HashMap<>();
    private DispatcherType dispatcherType = DispatcherType.REQUEST;

    private String characterEncoding;
    private VertxServletInputStream servletInputStream;
    private BufferedReader reader;
    private boolean inputStreamCalled;
    private boolean readerCalled;
    private Map<String, String[]> parameterMap;
    private String dispatchQueryString;
    private final io.vertx.core.buffer.Buffer requestBody;
    private HttpSession session;
    private String requestedSessionId;
    private boolean requestedSessionIdFromURL;
    private boolean requestedSessionIdFromCookie;
    private boolean sessionIdParsed;
    private VertxSessionStore sessionStore;
    private VertxAsyncContext asyncContext;
    private VertxAsyncContext.Callbacks asyncCallbacks;
    private volatile boolean asyncStarted = false;
    private boolean asyncSupported = false;
    private Vertx vertx;
    private ServletDeployment deployment;
    private jakarta.servlet.http.MappingMatch mappingMatch;
    private String matchedPattern;
    private String servletName;
    private List<Part> multipartParts;
    private MultipartConfiguration multipartLimits;
    private ServletSecurityContext securityContext;
    /** Set by {@link #logout()} so the caller reads as anonymous for the rest of this request. */
    private boolean loggedOut;

    public VertxServletRequest(RoutingContext routingContext, VertxServletContext servletContext,
            String servletPath, String pathInfo, String contextPath,
            io.vertx.core.buffer.Buffer requestBody) {
        this.routingContext = routingContext;
        this.request = routingContext.request();
        this.servletContext = servletContext;
        this.servletPath = servletPath;
        this.pathInfo = pathInfo;
        this.contextPath = contextPath != null ? contextPath : "";
        this.requestId = Long.toHexString(Thread.currentThread().threadId()) + "-"
                + Long.toHexString(REQUEST_ID_COUNTER.incrementAndGet());
        this.requestBody = requestBody;
    }

    private void ensureSessionIdParsed() {
        if (sessionIdParsed) {
            return;
        }
        sessionIdParsed = true;
        String cookieSessionId = getSessionIdFromCookie();
        if (cookieSessionId != null) {
            this.requestedSessionId = cookieSessionId;
            this.requestedSessionIdFromCookie = true;
        }
        String uri = request.uri();
        if (uri != null) {
            int jsessionIdx = uri.toLowerCase(Locale.ROOT).indexOf(";jsessionid=");
            if (jsessionIdx >= 0) {
                String afterMarker = uri.substring(jsessionIdx + ";jsessionid=".length());
                int endIdx = afterMarker.length();
                int qIdx = afterMarker.indexOf('?');
                int sIdx = afterMarker.indexOf(';');
                if (qIdx >= 0 && qIdx < endIdx) {
                    endIdx = qIdx;
                }
                if (sIdx >= 0 && sIdx < endIdx) {
                    endIdx = sIdx;
                }
                this.requestedSessionId = afterMarker.substring(0, endIdx);
                this.requestedSessionIdFromURL = true;
                this.requestedSessionIdFromCookie = false;
            }
        }
    }

    // ---- HttpServletRequest methods ----

    @Override
    public String getAuthType() {
        if (securityContext != null) {
            return securityContext.authType();
        }
        SecurityIdentity identity = getSecurityIdentity();
        if (identity != null && !identity.isAnonymous()) {
            String authType = identity.getAttribute("quarkus.http.auth.type");
            if (authType != null) {
                return authType;
            }
            // Quarkus does not always tag the identity with the mechanism, but a deployment has a
            // single login-config, so its auth-method is what authenticated this caller.
            if (deployment != null && deployment.getLoginConfig() != null) {
                return deployment.getLoginConfig().authMethod();
            }
        }
        return null;
    }

    @Override
    public Cookie[] getCookies() {
        String cookieHeader = request.headers().get("Cookie");
        if (cookieHeader == null || cookieHeader.isEmpty()) {
            return null;
        }
        List<Cookie> cookies = new ArrayList<>();
        String[] pairs = cookieHeader.split(";");
        for (String pair : pairs) {
            pair = pair.trim();
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String name = pair.substring(0, eq).trim();
                String value = pair.substring(eq + 1).trim();
                // Skip cookie attributes like Domain, Path, etc
                if (!name.equalsIgnoreCase("Domain") && !name.equalsIgnoreCase("Path")
                        && !name.equalsIgnoreCase("Expires") && !name.equalsIgnoreCase("Max-Age")
                        && !name.equalsIgnoreCase("Secure") && !name.equalsIgnoreCase("HttpOnly")
                        && !name.equalsIgnoreCase("SameSite") && !name.startsWith("$")) {
                    cookies.add(new Cookie(name, value));
                }
            }
        }
        return cookies.isEmpty() ? null : cookies.toArray(new Cookie[0]);
    }

    @Override
    public long getDateHeader(String name) {
        String value = request.headers().get(name);
        if (value == null) {
            return -1L;
        }
        for (String format : HTTP_DATE_FORMATS) {
            try {
                SimpleDateFormat sdf = new SimpleDateFormat(format, Locale.US);
                sdf.setTimeZone(TimeZone.getTimeZone("GMT"));
                return sdf.parse(value).getTime();
            } catch (ParseException e) {
                // try next format
            }
        }
        throw new IllegalArgumentException("Cannot parse date header '" + name + "': " + value);
    }

    @Override
    public String getHeader(String name) {
        return request.headers().get(name);
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
        List<String> values = request.headers().getAll(name);
        if (values == null) {
            return Collections.emptyEnumeration();
        }
        return Collections.enumeration(values);
    }

    @Override
    public Enumeration<String> getHeaderNames() {
        return Collections.enumeration(request.headers().names());
    }

    @Override
    public int getIntHeader(String name) {
        String value = request.headers().get(name);
        if (value == null) {
            return -1;
        }
        return Integer.parseInt(value);
    }

    @Override
    public String getMethod() {
        return request.method().name();
    }

    @Override
    public String getPathInfo() {
        return pathInfo;
    }

    @Override
    public String getPathTranslated() {
        if (pathInfo == null) {
            return null;
        }
        String realPath = servletContext.getRealPath(pathInfo);
        return realPath;
    }

    @Override
    public String getContextPath() {
        return contextPath;
    }

    @Override
    public String getQueryString() {
        if (forwardedQueryString != null) {
            return forwardedQueryString;
        }
        return request.query();
    }

    @Override
    public String getRemoteUser() {
        Principal principal = getUserPrincipal();
        return principal != null ? principal.getName() : null;
    }

    @Override
    public boolean isUserInRole(String role) {
        if (role == null) {
            return false;
        }
        // <security-role-ref> lets a servlet use its own name for a deployment role.
        String resolved = role;
        if (deployment != null && servletName != null) {
            ServletInfo info = deployment.getServlets().get(servletName);
            if (info != null) {
                resolved = info.resolveRoleRef(role);
            }
        }
        if (securityContext != null) {
            return securityContext.hasRole(resolved);
        }
        SecurityIdentity identity = getSecurityIdentity();
        if (identity != null) {
            return identity.getRoles().contains(resolved);
        }
        return false;
    }

    @Override
    public Principal getUserPrincipal() {
        if (securityContext != null) {
            return securityContext.principal();
        }
        SecurityIdentity identity = getSecurityIdentity();
        if (identity != null && !identity.isAnonymous()) {
            return identity.getPrincipal();
        }
        return null;
    }

    /** The caller the container authenticated itself, or {@code null} when there is none. */
    public ServletSecurityContext getServletSecurityContext() {
        return securityContext;
    }

    public void setServletSecurityContext(ServletSecurityContext securityContext) {
        this.securityContext = securityContext;
    }

    @Override
    public String getRequestedSessionId() {
        ensureSessionIdParsed();
        return requestedSessionId;
    }

    @Override
    public String getRequestURI() {
        if (forwardedRequestURI != null) {
            return forwardedRequestURI;
        }
        String path = request.path();
        if (path == null) {
            return contextPath + servletPath + (pathInfo != null ? pathInfo : "");
        }
        int semiIdx = path.indexOf(';');
        if (semiIdx >= 0) {
            path = path.substring(0, semiIdx);
        }
        return path;
    }

    @Override
    public StringBuffer getRequestURL() {
        StringBuffer url = new StringBuffer();
        url.append(getScheme());
        url.append("://");
        if (request.authority() != null) {
            url.append(request.authority().host());
            int port = request.authority().port();
            if (port > 0) {
                boolean defaultPort = ("http".equals(getScheme()) && port == 80)
                        || ("https".equals(getScheme()) && port == 443);
                if (!defaultPort) {
                    url.append(':').append(port);
                }
            }
        }
        url.append(getRequestURI());
        return url;
    }

    @Override
    public String getServletPath() {
        return servletPath;
    }

    public void setSessionStore(VertxSessionStore sessionStore) {
        this.sessionStore = sessionStore;
    }

    public void setVertx(Vertx vertx) {
        this.vertx = vertx;
    }

    public void setAsyncSupported(boolean asyncSupported) {
        this.asyncSupported = asyncSupported;
    }

    public void setDeployment(ServletDeployment deployment) {
        this.deployment = deployment;
    }

    /**
     * Runs {@code action} on this request's own thread with its scopes active. Used to deliver
     * WriteListener callbacks, which Vert.x raises on the event loop.
     */
    void runOnRequestThread(Runnable action) {
        VertxAsyncContext.Callbacks callbacks = asyncCallbacks;
        if (callbacks != null) {
            callbacks.execute(action);
        } else {
            action.run();
        }
    }

    /** Limits from the target servlet's {@code @MultipartConfig}, if it declared one. */
    public void setMultipartLimits(MultipartConfiguration multipartLimits) {
        this.multipartLimits = multipartLimits;
    }

    public void setMapping(jakarta.servlet.http.MappingMatch mappingMatch, String matchedPattern,
            String servletName) {
        this.mappingMatch = mappingMatch;
        this.matchedPattern = matchedPattern;
        this.servletName = servletName;
    }

    /**
     * After an async {@code dispatch(path)} the request paths and mapping reflect the dispatched
     * target servlet (like a forward), while the originals live in the {@code jakarta.servlet.async.*}
     * attributes. The request URI is left untouched so it still reports the URI the client sent.
     */
    public void setAsyncDispatchTarget(String servletPath, String pathInfo,
            jakarta.servlet.http.MappingMatch mappingMatch, String matchedPattern, String servletName) {
        this.servletPath = servletPath;
        this.pathInfo = pathInfo;
        this.mappingMatch = mappingMatch;
        this.matchedPattern = matchedPattern;
        this.servletName = servletName;
        this.parameterMap = null;
    }

    @Override
    public HttpSession getSession(boolean create) {
        if (session != null) {
            return session;
        }
        if (sessionStore != null) {
            String cookieSessionId = getSessionIdFromCookie();
            if (cookieSessionId != null) {
                session = sessionStore.getSession(cookieSessionId);
                if (session != null) {
                    if (requestedSessionId == null) {
                        requestedSessionId = cookieSessionId;
                    }
                    requestedSessionIdFromCookie = true;
                    return session;
                }
            }
            if (requestedSessionId != null) {
                session = sessionStore.getSession(requestedSessionId);
                if (session != null) {
                    return session;
                }
            }
            if (!create) {
                return null;
            }
            session = sessionStore.createSession(servletContext);
            routingContext.response().addCookie(newSessionCookie(session.getId()));
            return session;
        }
        if (!create) {
            return null;
        }
        session = new SimpleHttpSession(servletContext);
        return session;
    }

    private String getSessionIdFromCookie() {
        String cookieHeader = request.headers().get("Cookie");
        if (cookieHeader == null) {
            return null;
        }
        String prefix = sessionCookieName() + "=";
        for (String pair : cookieHeader.split(";")) {
            pair = pair.trim();
            if (pair.startsWith(prefix)) {
                return pair.substring(prefix.length()).trim();
            }
        }
        return null;
    }

    @Override
    public HttpSession getSession() {
        return getSession(true);
    }

    @Override
    public String changeSessionId() {
        HttpSession currentSession = getSession(false);
        if (currentSession == null) {
            throw new IllegalStateException("No session associated with this request");
        }
        String oldId = currentSession.getId();
        String newId = UUID.randomUUID().toString();
        if (sessionStore != null) {
            sessionStore.changeSessionId(oldId, newId);
        }
        // Notify HttpSessionIdListener
        jakarta.servlet.http.HttpSessionEvent event = new jakarta.servlet.http.HttpSessionEvent(currentSession);
        for (java.util.EventListener l : servletContext.getListeners()) {
            if (l instanceof jakarta.servlet.http.HttpSessionIdListener idl) {
                idl.sessionIdChanged(event, oldId);
            }
        }
        routingContext.response().addCookie(newSessionCookie(newId));
        return newId;
    }

    private io.vertx.core.http.Cookie newSessionCookie(String id) {
        return ServletSessions.newSessionCookie(servletContext, id, contextPath, request.isSSL());
    }

    /** The configured session cookie name, {@code JSESSIONID} unless the application changed it. */
    private String sessionCookieName() {
        return ServletSessions.cookieName(servletContext);
    }

    @Override
    public boolean isRequestedSessionIdValid() {
        ensureSessionIdParsed();
        if (requestedSessionId == null) {
            return false;
        }
        if (sessionStore != null) {
            return sessionStore.getSession(requestedSessionId) != null;
        }
        return false;
    }

    @Override
    public boolean isRequestedSessionIdFromCookie() {
        ensureSessionIdParsed();
        return requestedSessionIdFromCookie;
    }

    @Override
    public boolean isRequestedSessionIdFromURL() {
        ensureSessionIdParsed();
        return requestedSessionIdFromURL;
    }

    /**
     * {@code authenticate()} and {@code login()} are synchronous by signature, so they have to wait
     * for the authentication result. That is safe on a worker or virtual thread, but on the event
     * loop it would stall every connection that thread owns. Failing with a clear message beats
     * deadlocking the server.
     */
    private static void assertCanBlock(String operation) throws ServletException {
        if (io.vertx.core.Context.isOnEventLoopThread()) {
            throw new ServletException(operation + "() blocks and cannot be called from a servlet "
                    + "running on the event loop. Annotate the servlet with @Blocking or "
                    + "@RunOnVirtualThread, or set quarkus.servlet.execution-model.");
        }
    }

    @Override
    public boolean authenticate(HttpServletResponse response) throws IOException, ServletException {
        if (getUserPrincipal() != null) {
            return true;
        }
        assertCanBlock("authenticate");
        HttpAuthenticator authenticator = routingContext.get(HttpAuthenticator.class.getName());
        if (authenticator != null) {
            SecurityIdentity identity = authenticator.attemptAuthentication(routingContext)
                    .await().indefinitely();
            if (identity != null && !identity.isAnonymous()) {
                QuarkusHttpUser.setIdentity(identity, routingContext);
                return true;
            }
            authenticator.sendChallenge(routingContext).await().indefinitely();
        }
        return false;
    }

    @Override
    public void login(String username, String password) throws ServletException {
        if (getUserPrincipal() != null) {
            throw new ServletException("User already logged in");
        }

        // When the deployment declares its own identity store the container authenticates
        // directly, which also keeps login() usable on the event loop.
        if (deployment != null && deployment.getIdentityStore() != ServletIdentityStore.EMPTY) {
            String authType = deployment.getLoginConfig() != null
                    ? deployment.getLoginConfig().authMethod()
                    : LoginConfig.BASIC;
            ServletSecurityContext caller = deployment.getIdentityStore()
                    .authenticate(username, password, authType);
            if (caller == null) {
                throw new ServletException("Login failed for user " + username);
            }
            ServletSecurityEnforcer.rememberLogin(this, caller);
            return;
        }

        assertCanBlock("login");
        HttpAuthenticator authenticator = routingContext.get(HttpAuthenticator.class.getName());
        if (authenticator == null) {
            throw new ServletException("No authenticator available");
        }
        try {
            SecurityIdentity identity = authenticator.getIdentityProviderManager()
                    .authenticate(new UsernamePasswordAuthenticationRequest(username,
                            new io.quarkus.security.credential.PasswordCredential(
                                    password.toCharArray())))
                    .await().indefinitely();
            QuarkusHttpUser.setIdentity(identity, routingContext);
            CurrentIdentityAssociation cia = io.quarkus.arc.Arc.container()
                    .instance(CurrentIdentityAssociation.class).get();
            if (cia != null) {
                cia.setIdentity(identity);
            }
        } catch (Exception e) {
            throw new ServletException("Login failed", e);
        }
    }

    @Override
    public void logout() throws ServletException {
        if (securityContext != null) {
            ServletSecurityEnforcer.logout(this);
            return;
        }
        SecurityIdentity identity = getSecurityIdentity();
        // The caller must read as anonymous for the remainder of this request, whatever the source
        // of the identity was.
        loggedOut = true;
        if (identity == null || identity.isAnonymous()) {
            return;
        }
        HttpSession session = getSession(false);
        if (session != null) {
            session.invalidate();
        }
        // Drop the identity from the ambient CDI association, so nothing downstream re-derives the
        // logged-out caller. The request itself reads as anonymous via the loggedOut flag.
        CurrentIdentityAssociation cia = io.quarkus.arc.Arc.container()
                .instance(CurrentIdentityAssociation.class).get();
        if (cia != null) {
            cia.setIdentity((SecurityIdentity) null);
        }
        // Expire the persistent credential cookie so later requests are not silently re-authenticated
        // from it. Its path is the context root, matching how the FORM mechanism writes it.
        io.vertx.core.http.Cookie expired = io.vertx.core.http.Cookie.cookie("quarkus-credential", "");
        expired.setPath("/");
        expired.setMaxAge(0);
        routingContext.response().addCookie(expired);
    }

    @Override
    public Collection<Part> getParts() throws IOException, ServletException {
        ensureMultipartParsed();
        return multipartParts;
    }

    @Override
    public Part getPart(String name) throws IOException, ServletException {
        ensureMultipartParsed();
        for (Part part : multipartParts) {
            if (part.getName().equals(name)) {
                return part;
            }
        }
        return null;
    }

    private void ensureMultipartParsed() throws ServletException {
        if (multipartParts != null) {
            return;
        }
        String ct = getContentType();
        if (ct == null || !ct.toLowerCase(Locale.ROOT).startsWith("multipart/")) {
            throw new ServletException("Request is not multipart");
        }
        String boundary = MultipartParser.boundaryOf(ct);
        if (boundary == null) {
            throw new ServletException("Multipart request has no boundary parameter");
        }

        byte[] body = requestBody != null ? requestBody.getBytes() : new byte[0];
        Charset headerCharset;
        try {
            String encoding = getCharacterEncoding();
            headerCharset = encoding != null ? Charset.forName(encoding) : StandardCharsets.UTF_8;
        } catch (RuntimeException e) {
            headerCharset = StandardCharsets.UTF_8;
        }

        List<VertxPart> parsed = MultipartParser.parse(body, boundary, headerCharset, multipartLimits);
        Path location = multipartLimits != null && multipartLimits.location() != null
                && !multipartLimits.location().isEmpty()
                        ? Path.of(multipartLimits.location())
                        : null;
        if (location != null) {
            for (VertxPart part : parsed) {
                part.setLocation(location);
            }
        }
        multipartParts = new ArrayList<>(parsed);
    }

    /**
     * The form fields of a multipart request, which the spec folds into the request parameters
     * alongside the query string.
     */
    private void addMultipartParameters(Map<String, List<String>> target) {
        try {
            ensureMultipartParsed();
        } catch (ServletException | RuntimeException e) {
            // A malformed body must not break getParameter for the query string.
            return;
        }
        for (Part part : multipartParts) {
            if (part.getSubmittedFileName() == null && part instanceof VertxPart vp) {
                target.computeIfAbsent(part.getName(), k -> new ArrayList<>())
                        .add(vp.getValueAsString());
            }
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends HttpUpgradeHandler> T upgrade(Class<T> handlerClass) throws IOException, ServletException {
        try {
            T handler;
            Object cdiInstance = io.quarkus.arc.Arc.container().instance(handlerClass).get();
            if (cdiInstance != null) {
                handler = (T) cdiInstance;
            } else {
                handler = handlerClass.getDeclaredConstructor().newInstance();
            }
            routingContext.response().setStatusCode(101);

            // toNetSocket() must not be awaited here: on the default execution model this runs on
            // the event loop, and awaiting would deadlock the very thread that has to complete the
            // handshake. The handler is initialised from the completion callback instead, which is
            // also what the spec describes - the upgrade takes effect once service() returns.
            final T upgradeHandler = handler;
            routingContext.request().toNetSocket().onComplete(ar -> {
                if (ar.failed()) {
                    log.error("HTTP upgrade failed", ar.cause());
                    return;
                }
                try {
                    upgradeHandler.init(new VertxWebConnection(ar.result()));
                } catch (Exception e) {
                    log.error("HTTP upgrade handler failed to initialise", e);
                    ar.result().close();
                }
            });
            return handler;
        } catch (Exception e) {
            throw new ServletException("Failed to upgrade connection", e);
        }
    }

    @Override
    public HttpServletMapping getHttpServletMapping() {
        return new HttpServletMapping() {
            @Override
            public String getMatchValue() {
                if (mappingMatch == null) {
                    return "";
                }
                return switch (mappingMatch) {
                    case CONTEXT_ROOT -> "";
                    case DEFAULT -> servletPath != null ? servletPath : "";
                    case EXACT -> servletPath != null && servletPath.startsWith("/")
                            ? servletPath.substring(1)
                            : (servletPath != null ? servletPath : "");
                    case PATH -> pathInfo != null && pathInfo.startsWith("/")
                            ? pathInfo.substring(1)
                            : (pathInfo != null ? pathInfo : "");
                    case EXTENSION -> {
                        // For an extension match (*.ext) the match value is the path minus the leading
                        // slash and minus the extension: "/a.ts" against "*.ts" yields "a".
                        String sp = servletPath != null ? servletPath : "";
                        if (sp.startsWith("/")) {
                            sp = sp.substring(1);
                        }
                        int dot = sp.lastIndexOf('.');
                        yield dot >= 0 ? sp.substring(0, dot) : sp;
                    }
                };
            }

            @Override
            public String getPattern() {
                return matchedPattern != null ? matchedPattern : "";
            }

            @Override
            public String getServletName() {
                return servletName != null ? servletName : "";
            }

            @Override
            public MappingMatch getMappingMatch() {
                return mappingMatch;
            }
        };
    }

    // ---- ServletRequest methods ----

    @Override
    public Object getAttribute(String name) {
        return attributes.get(name);
    }

    @Override
    public Enumeration<String> getAttributeNames() {
        return Collections.enumeration(attributes.keySet());
    }

    @Override
    public String getCharacterEncoding() {
        if (characterEncoding != null) {
            return characterEncoding;
        }
        String contentType = getContentType();
        if (contentType != null) {
            String parsed = parseCharsetFromContentType(contentType);
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    @Override
    public void setCharacterEncoding(String env) throws UnsupportedEncodingException {
        if (readerCalled || inputStreamCalled) {
            return;
        }
        if (env == null) {
            throw new UnsupportedEncodingException("Encoding cannot be null");
        }
        try {
            Charset.forName(env);
        } catch (UnsupportedCharsetException e) {
            throw new UnsupportedEncodingException(env);
        }
        this.characterEncoding = env;
    }

    @Override
    public int getContentLength() {
        String value = request.headers().get("Content-Length");
        if (value == null) {
            return -1;
        }
        try {
            long length = Long.parseLong(value);
            if (length > Integer.MAX_VALUE) {
                return -1;
            }
            return (int) length;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    @Override
    public long getContentLengthLong() {
        String value = request.headers().get("Content-Length");
        if (value == null) {
            return -1L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    @Override
    public String getContentType() {
        return request.headers().get("Content-Type");
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
        if (readerCalled) {
            throw new IllegalStateException("getReader() has already been called on this request");
        }
        inputStreamCalled = true;
        if (servletInputStream == null) {
            servletInputStream = newInputStream();
        }
        return servletInputStream;
    }

    @Override
    public String getParameter(String name) {
        ensureParametersParsed();
        String[] values = parameterMap.get(name);
        return values != null && values.length > 0 ? values[0] : null;
    }

    @Override
    public Enumeration<String> getParameterNames() {
        ensureParametersParsed();
        return Collections.enumeration(parameterMap.keySet());
    }

    @Override
    public String[] getParameterValues(String name) {
        ensureParametersParsed();
        return parameterMap.get(name);
    }

    @Override
    public Map<String, String[]> getParameterMap() {
        ensureParametersParsed();
        return Collections.unmodifiableMap(parameterMap);
    }

    @Override
    public String getProtocol() {
        HttpVersion version = request.version();
        if (version == null) {
            return "HTTP/1.1";
        }
        return switch (version) {
            case HTTP_1_0 -> "HTTP/1.0";
            case HTTP_1_1 -> "HTTP/1.1";
            case HTTP_2 -> "HTTP/2.0";
            default -> "HTTP/1.1";
        };
    }

    @Override
    public String getScheme() {
        return request.scheme();
    }

    @Override
    public String getServerName() {
        if (request.authority() != null) {
            return request.authority().host();
        }
        return "localhost";
    }

    @Override
    public int getServerPort() {
        if (request.authority() != null) {
            int port = request.authority().port();
            if (port > 0) {
                return port;
            }
        }
        return "https".equals(getScheme()) ? 443 : 80;
    }

    @Override
    public BufferedReader getReader() throws IOException {
        if (inputStreamCalled) {
            throw new IllegalStateException("getInputStream() has already been called on this request");
        }
        readerCalled = true;
        if (reader == null) {
            String encoding = getCharacterEncoding();
            if (encoding != null) {
                reader = new BufferedReader(new InputStreamReader(getInternalInputStream(), encoding));
            } else {
                reader = new BufferedReader(new InputStreamReader(getInternalInputStream(), StandardCharsets.ISO_8859_1));
            }
        }
        return reader;
    }

    @Override
    public String getRemoteAddr() {
        if (request.remoteAddress() != null) {
            return request.remoteAddress().host();
        }
        return null;
    }

    @Override
    public String getRemoteHost() {
        if (request.remoteAddress() != null) {
            return request.remoteAddress().host();
        }
        return null;
    }

    @Override
    public void setAttribute(String name, Object o) {
        if (o == null) {
            removeAttribute(name);
            return;
        }
        Object old = attributes.put(name, o);
        for (EventListener l : servletContext.getListeners()) {
            if (l instanceof ServletRequestAttributeListener sral) {
                if (old == null) {
                    sral.attributeAdded(new ServletRequestAttributeEvent(servletContext, this, name, o));
                } else {
                    sral.attributeReplaced(new ServletRequestAttributeEvent(servletContext, this, name, old));
                }
            }
        }
    }

    @Override
    public void removeAttribute(String name) {
        Object old = attributes.remove(name);
        if (old != null) {
            for (EventListener l : servletContext.getListeners()) {
                if (l instanceof ServletRequestAttributeListener sral) {
                    sral.attributeRemoved(new ServletRequestAttributeEvent(servletContext, this, name, old));
                }
            }
        }
    }

    @Override
    public Locale getLocale() {
        List<Locale> locales = parseAcceptLanguage();
        return locales.isEmpty() ? Locale.getDefault() : locales.get(0);
    }

    @Override
    public Enumeration<Locale> getLocales() {
        List<Locale> locales = parseAcceptLanguage();
        if (locales.isEmpty()) {
            locales = List.of(Locale.getDefault());
        }
        return Collections.enumeration(locales);
    }

    @Override
    public boolean isSecure() {
        return request.isSSL();
    }

    @Override
    public RequestDispatcher getRequestDispatcher(String path) {
        if (path == null) {
            return null;
        }
        if (!path.startsWith("/")) {
            String base = getServletPath() + (getPathInfo() != null ? getPathInfo() : "");
            int lastSlash = base.lastIndexOf('/');
            if (lastSlash >= 0) {
                path = base.substring(0, lastSlash + 1) + path;
            }
        }
        return new VertxRequestDispatcher(path, deployment);
    }

    @Override
    public int getRemotePort() {
        if (request.remoteAddress() != null) {
            return request.remoteAddress().port();
        }
        return 0;
    }

    @Override
    public String getLocalName() {
        if (request.localAddress() != null) {
            return request.localAddress().host();
        }
        return null;
    }

    @Override
    public String getLocalAddr() {
        if (request.localAddress() != null) {
            return request.localAddress().host();
        }
        return null;
    }

    @Override
    public int getLocalPort() {
        if (request.localAddress() != null) {
            return request.localAddress().port();
        }
        return 0;
    }

    @Override
    public VertxServletContext getServletContext() {
        return servletContext;
    }

    @Override
    public AsyncContext startAsync() throws IllegalStateException {
        return startAsync(this, ServletRequestContext.get().getResponse());
    }

    @Override
    public AsyncContext startAsync(ServletRequest servletRequest, ServletResponse servletResponse)
            throws IllegalStateException {
        if (!asyncSupported) {
            throw new IllegalStateException("Async not supported for this request");
        }
        if (asyncStarted) {
            throw new IllegalStateException("Async already started");
        }
        VertxAsyncContext previous = asyncContext;
        asyncStarted = true;
        // True only when *both* the request and the response are the container's originals; a
        // wrapper on either side means the application no longer has the original pair.
        ServletResponse originalResponse = ServletRequestContext.get().getResponse();
        boolean original = (servletRequest == this) && (servletResponse == originalResponse);
        asyncContext = new VertxAsyncContext(servletRequest, servletResponse, this, original, vertx, deployment);
        asyncContext.setCallbacks(asyncCallbacks);
        if (previous != null) {
            previous.notifyStartAsync(asyncContext);
        }
        asyncContext.startTimeout();
        return asyncContext;
    }

    /**
     * Attached by the container before the servlet runs, so that an {@link AsyncContext} created
     * during {@code service()} can finish the exchange without racing the container.
     */
    public void setAsyncCallbacks(VertxAsyncContext.Callbacks asyncCallbacks) {
        this.asyncCallbacks = asyncCallbacks;
    }

    @Override
    public boolean isAsyncStarted() {
        return asyncStarted;
    }

    @Override
    public boolean isAsyncSupported() {
        return asyncSupported;
    }

    @Override
    public AsyncContext getAsyncContext() {
        if (asyncContext == null) {
            throw new IllegalStateException("Async not started");
        }
        return asyncContext;
    }

    @Override
    public DispatcherType getDispatcherType() {
        return dispatcherType;
    }

    public void setDispatcherType(DispatcherType dispatcherType) {
        this.dispatcherType = dispatcherType;
    }

    public void setForwardedPath(String requestURI, String servletPath, String pathInfo,
            String queryString) {
        this.forwardedRequestURI = requestURI;
        this.servletPath = servletPath;
        this.pathInfo = pathInfo;
        this.forwardedQueryString = queryString;
        this.dispatchQueryString = queryString;
        this.parameterMap = null;
    }

    public void setDispatchQueryString(String queryString) {
        this.dispatchQueryString = queryString;
        this.parameterMap = null;
    }

    public void clearDispatchQueryString() {
        this.dispatchQueryString = null;
        this.parameterMap = null;
    }

    public void resetAsyncState() {
        this.asyncStarted = false;
        this.asyncContext = null;
    }

    @Override
    public String getRequestId() {
        return requestId;
    }

    @Override
    public String getProtocolRequestId() {
        HttpVersion version = request.version();
        if (version == HttpVersion.HTTP_2) {
            // For HTTP/2, return the stream ID if available
            return String.valueOf(request.streamId());
        }
        return "";
    }

    @Override
    public ServletConnection getServletConnection() {
        return new VertxServletConnection(request);
    }

    // ---- Internal helper methods ----

    private SecurityIdentity getSecurityIdentity() {
        if (loggedOut) {
            return null;
        }
        try {
            return QuarkusHttpUser.getSecurityIdentityBlocking(routingContext, null);
        } catch (Exception e) {
            return null;
        }
    }

    RoutingContext getRoutingContext() {
        return routingContext;
    }

    /**
     * Creates the VertxServletInputStream without setting the inputStreamCalled flag.
     * Used internally by getReader() to avoid the mutual exclusion check.
     */
    private ServletInputStream getInternalInputStream() throws IOException {
        if (servletInputStream == null) {
            servletInputStream = newInputStream();
        }
        return servletInputStream;
    }

    /**
     * Creates the input stream, handing it the hook it needs to run ReadListener callbacks on this
     * request's own thread with the request scope active.
     */
    private VertxServletInputStream newInputStream() {
        VertxServletInputStream stream = new VertxServletInputStream(requestBody);
        stream.setCallbackExecutor(action -> {
            // The spec only allows a ReadListener in async or upgraded mode.
            if (!asyncStarted) {
                throw new IllegalStateException(
                        "setReadListener() requires async processing to have been started");
            }
            VertxAsyncContext.Callbacks callbacks = asyncCallbacks;
            if (callbacks != null) {
                callbacks.execute(action);
            } else {
                action.run();
            }
        });
        return stream;
    }

    private void ensureParametersParsed() {
        if (parameterMap != null) {
            return;
        }
        Map<String, List<String>> merged = new LinkedHashMap<>();

        // Dispatch query string parameters take precedence (appear first)
        if (dispatchQueryString != null && !dispatchQueryString.isEmpty()) {
            parseQueryString(dispatchQueryString, merged);
        }

        // Original query string parameters
        String originalQuery = request.query();
        if (originalQuery != null && !originalQuery.isEmpty()) {
            parseQueryString(originalQuery, merged);
        }

        // Form parameters from the request body, decoded with the request's character encoding
        // (setCharacterEncoding or the Content-Type charset), not an assumed UTF-8.
        String contentType = getContentType();
        if (contentType != null
                && contentType.toLowerCase(Locale.ROOT).startsWith("application/x-www-form-urlencoded")
                && requestBody != null && requestBody.length() > 0) {
            Charset formCharset;
            try {
                String encoding = getCharacterEncoding();
                formCharset = encoding != null ? Charset.forName(encoding) : StandardCharsets.UTF_8;
            } catch (RuntimeException e) {
                formCharset = StandardCharsets.UTF_8;
            }
            parseFormEncoded(requestBody.toString(formCharset), formCharset, merged);
        } else if (contentType != null
                && contentType.toLowerCase(Locale.ROOT).startsWith("multipart/form-data")
                && requestBody != null && requestBody.length() > 0) {
            addMultipartParameters(merged);
        }

        int maxParams = deployment != null ? deployment.getMaxParameters() : 1000;
        int totalParams = merged.values().stream().mapToInt(List::size).sum();
        if (totalParams > maxParams) {
            throw new IllegalStateException(
                    "Request exceeded maximum number of parameters (" + maxParams + ")");
        }

        Map<String, String[]> result = new LinkedHashMap<>();
        for (var entry : merged.entrySet()) {
            result.put(entry.getKey(), entry.getValue().toArray(new String[0]));
        }
        this.parameterMap = result;
    }

    private static void parseQueryString(String queryString, Map<String, List<String>> target) {
        parseFormEncoded(queryString, StandardCharsets.UTF_8, target);
    }

    /**
     * Parses {@code application/x-www-form-urlencoded} data. Parameters whose percent-encoding is
     * malformed are skipped rather than failing the whole request: a single bad escape anywhere in
     * the query string must not turn every parameter read into a 500.
     */
    private static void parseFormEncoded(String encoded, Charset charset,
            Map<String, List<String>> target) {
        for (String param : encoded.split("&")) {
            if (param.isEmpty()) {
                continue;
            }
            int eq = param.indexOf('=');
            String name;
            String value;
            try {
                if (eq >= 0) {
                    name = java.net.URLDecoder.decode(param.substring(0, eq), charset);
                    value = java.net.URLDecoder.decode(param.substring(eq + 1), charset);
                } else {
                    name = java.net.URLDecoder.decode(param, charset);
                    value = "";
                }
            } catch (IllegalArgumentException e) {
                continue;
            }
            target.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
        }
    }

    private List<Locale> parseAcceptLanguage() {
        String header = request.headers().get("Accept-Language");
        if (header == null || header.isEmpty()) {
            return Collections.emptyList();
        }

        record LangQuality(Locale locale, double quality) implements Comparable<LangQuality> {
            @Override
            public int compareTo(LangQuality other) {
                return Double.compare(other.quality, this.quality);
            }
        }

        List<LangQuality> entries = new ArrayList<>();
        String[] parts = header.split(",");
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            double quality = 1.0;
            String lang = trimmed;
            int qIndex = trimmed.indexOf(";q=");
            if (qIndex < 0) {
                qIndex = trimmed.indexOf(";Q=");
            }
            if (qIndex >= 0) {
                lang = trimmed.substring(0, qIndex).trim();
                try {
                    quality = Double.parseDouble(trimmed.substring(qIndex + 3).trim());
                } catch (NumberFormatException e) {
                    quality = 0.0;
                }
            }
            Locale locale = Locale.forLanguageTag(lang.replace('_', '-'));
            entries.add(new LangQuality(locale, quality));
        }

        Collections.sort(entries);
        List<Locale> result = new ArrayList<>(entries.size());
        for (LangQuality entry : entries) {
            result.add(entry.locale);
        }
        return result;
    }

    private static String parseCharsetFromContentType(String contentType) {
        int charsetIndex = contentType.toLowerCase(Locale.ROOT).indexOf("charset=");
        if (charsetIndex < 0) {
            return null;
        }
        String charset = contentType.substring(charsetIndex + 8).trim();
        // Remove quotes if present
        if (charset.startsWith("\"") && charset.endsWith("\"")) {
            charset = charset.substring(1, charset.length() - 1);
        }
        // Remove any trailing parameters
        int semicolon = charset.indexOf(';');
        if (semicolon >= 0) {
            charset = charset.substring(0, semicolon).trim();
        }
        return charset;
    }

    // ---- Inner class: minimal in-memory HttpSession ----

    private static class SimpleHttpSession implements HttpSession {

        private final String id;
        private final long creationTime;
        private final ServletContext servletContext;
        private final Map<String, Object> attributes = new ConcurrentHashMap<>();
        private int maxInactiveInterval = 1800; // 30 minutes default
        private volatile long lastAccessedTime;
        private volatile boolean invalid;
        private volatile boolean isNew = true;

        SimpleHttpSession(ServletContext servletContext) {
            this.id = UUID.randomUUID().toString();
            this.creationTime = System.currentTimeMillis();
            this.lastAccessedTime = this.creationTime;
            this.servletContext = servletContext;
        }

        private void checkValid() {
            if (invalid) {
                throw new IllegalStateException("Session has been invalidated");
            }
        }

        @Override
        public long getCreationTime() {
            checkValid();
            return creationTime;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public long getLastAccessedTime() {
            checkValid();
            return lastAccessedTime;
        }

        @Override
        public ServletContext getServletContext() {
            return servletContext;
        }

        @Override
        public void setMaxInactiveInterval(int interval) {
            this.maxInactiveInterval = interval;
        }

        @Override
        public int getMaxInactiveInterval() {
            return maxInactiveInterval;
        }

        @Override
        public Object getAttribute(String name) {
            checkValid();
            return attributes.get(name);
        }

        @Override
        public Enumeration<String> getAttributeNames() {
            checkValid();
            return Collections.enumeration(attributes.keySet());
        }

        @Override
        public void setAttribute(String name, Object value) {
            checkValid();
            if (value == null) {
                removeAttribute(name);
                return;
            }
            attributes.put(name, value);
        }

        @Override
        public void removeAttribute(String name) {
            checkValid();
            attributes.remove(name);
        }

        @Override
        public void invalidate() {
            checkValid();
            attributes.clear();
            invalid = true;
        }

        @Override
        public boolean isNew() {
            checkValid();
            return isNew;
        }
    }
}
