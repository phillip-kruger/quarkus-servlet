# Quarkus Servlet - User Guide

This guide covers the Quarkus Servlet extension, a Jakarta Servlet 6.1 implementation
built on Vert.x 5 for Quarkus.

## Table of Contents

- [Getting Started](#getting-started)
- [Servlets](#servlets)
- [Filters](#filters)
- [Listeners](#listeners)
- [Threading Model](#threading-model)
- [Request Dispatching](#request-dispatching)
- [Async Support](#async-support)
- [Sessions](#sessions)
- [Static Resources](#static-resources)
- [Error Pages](#error-pages)
- [Multipart File Upload](#multipart-file-upload)
- [Non-blocking I/O](#non-blocking-io)
- [HTTP Upgrade](#http-upgrade)
- [Security](#security)
- [CDI Integration](#cdi-integration)
- [Configuration Reference](#configuration-reference)
- [Dev UI and Dev MCP](#dev-ui-and-dev-mcp)
- [Migration from quarkus-undertow](#migration-from-quarkus-undertow)
- [Known Limitations](#known-limitations)

---

## Getting Started

Add the extension to your project:

```xml
<dependency>
    <groupId>io.quarkiverse.servlet</groupId>
    <artifactId>quarkus-servlet</artifactId>
    <version>${quarkus-servlet.version}</version>
</dependency>
```

Create a servlet:

```java
@WebServlet(urlPatterns = "/hello")
public class HelloServlet extends HttpServlet {
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("text/plain");
        resp.getWriter().write("Hello from Quarkus Servlet!");
    }
}
```

Run in dev mode:

```bash
mvn quarkus:dev
```

Visit `http://localhost:8080/hello`.

---

## Servlets

Servlets can be registered using annotations or `web.xml`.

### Annotation-based

```java
@WebServlet(
    name = "myServlet",
    urlPatterns = {"/api/*", "/service"},
    loadOnStartup = 1,
    asyncSupported = true,
    initParams = @WebInitParam(name = "configKey", value = "configValue")
)
public class MyServlet extends HttpServlet {
    // ...
}
```

### web.xml-based

Place `web.xml` in `src/main/resources/META-INF/web.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<web-app xmlns="https://jakarta.ee/xml/ns/jakartaee"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="https://jakarta.ee/xml/ns/jakartaee
         https://jakarta.ee/xml/ns/jakartaee/web-app_6_1.xsd"
         version="6.1">

    <servlet>
        <servlet-name>myServlet</servlet-name>
        <servlet-class>com.example.MyServlet</servlet-class>
        <load-on-startup>1</load-on-startup>
    </servlet>
    <servlet-mapping>
        <servlet-name>myServlet</servlet-name>
        <url-pattern>/api/*</url-pattern>
    </servlet-mapping>
</web-app>
```

### Programmatic registration via SCI

Use `ServletContainerInitializer` to register servlets programmatically:

```java
public class MyInitializer implements ServletContainerInitializer {
    @Override
    public void onStartup(Set<Class<?>> classes, ServletContext ctx) {
        var reg = ctx.addServlet("dynamic", DynamicServlet.class);
        reg.addMapping("/dynamic");
        reg.setLoadOnStartup(1);
    }
}
```

Register in `META-INF/services/jakarta.servlet.ServletContainerInitializer`.

### SPI - Programmatic registration from other extensions

Other Quarkus extensions can register servlets at build time:

```java
@BuildStep
void addServlet(BuildProducer<ServletBuildItem> servlets) {
    servlets.produce(new ServletBuildItem(
        "myServlet",              // name
        MyServlet.class.getName(), // class
        List.of("/api/*"),         // URL patterns
        Map.of(),                  // init params
        1,                         // loadOnStartup
        false                      // asyncSupported
    ));
}
```

---

## Filters

### Annotation-based

```java
@WebFilter(urlPatterns = "/*", filterName = "loggingFilter")
public class LoggingFilter implements Filter {
    @Override
    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain)
            throws IOException, ServletException {
        // pre-processing
        chain.doFilter(req, resp);
        // post-processing
    }
}
```

### Dispatch types

Filters can be scoped to specific dispatch types:

```java
@WebFilter(
    urlPatterns = "/api/*",
    dispatcherTypes = {DispatcherType.REQUEST, DispatcherType.FORWARD}
)
```

### Servlet-name matching

Filters can also match by servlet name rather than URL pattern, both in `web.xml`:

```xml
<filter-mapping>
    <filter-name>myFilter</filter-name>
    <servlet-name>myServlet</servlet-name>
</filter-mapping>
```

### SPI - from other extensions

```java
@BuildStep
void addFilter(BuildProducer<FilterBuildItem> filters) {
    filters.produce(new FilterBuildItem(
        "metricsFilter", MetricsFilter.class.getName(),
        List.of("/*"), null, false, Map.of(), 0
    ));
}
```

---

## Listeners

All standard listener types are supported:

| Listener | Purpose |
|---|---|
| `ServletContextListener` | Application lifecycle |
| `ServletContextAttributeListener` | Context attribute changes |
| `ServletRequestListener` | Request lifecycle |
| `ServletRequestAttributeListener` | Request attribute changes |
| `HttpSessionListener` | Session creation/destruction |
| `HttpSessionAttributeListener` | Session attribute changes |
| `HttpSessionIdListener` | Session ID changes |
| `HttpSessionBindingListener` | Object binding to sessions |

Register with `@WebListener`:

```java
@WebListener
public class AppLifecycle implements ServletContextListener {
    @Override
    public void contextInitialized(ServletContextEvent sce) {
        // app starting
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        // app stopping
    }
}
```

---

## Threading Model

By default, servlets execute on the Vert.x event loop for maximum performance. This
means servlet code must be non-blocking - it should not perform I/O operations that
block the thread (database calls, file reads, HTTP client calls, `Thread.sleep()`).

For simple servlets that compute a response and write it, the event loop is ideal and
delivers the highest throughput.

### Virtual threads (opt-in)

For servlets that need blocking I/O, annotate with `@RunOnVirtualThread`:

```java
import io.smallrye.common.annotation.RunOnVirtualThread;

@WebServlet(urlPatterns = "/users")
@RunOnVirtualThread
public class UserServlet extends HttpServlet {
    @Inject
    UserRepository userRepo;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        // This blocks - safe on virtual thread
        List<User> users = userRepo.listAll();
        resp.setContentType("application/json");
        resp.getWriter().write(toJson(users));
    }
}
```

### Global virtual threads

To run all servlets on virtual threads without annotating each one:

```properties
quarkus.servlet.virtual-threads=true
```

This is useful when migrating from `quarkus-undertow` where all servlets ran on worker
threads by default.

### When to use which

| Scenario | Threading |
|---|---|
| Simple response (no I/O) | Event loop (default) |
| Database queries | `@RunOnVirtualThread` |
| HTTP client calls | `@RunOnVirtualThread` |
| File I/O | `@RunOnVirtualThread` |
| `Thread.sleep()` | `@RunOnVirtualThread` |
| High-throughput REST APIs | Event loop (default) |
| Migrating from Undertow | `quarkus.servlet.virtual-threads=true` |

---

## Request Dispatching

### Forward

```java
req.getRequestDispatcher("/other").forward(req, resp);
```

Forward transfers processing to another servlet. The response buffer is reset before
the target servlet runs. Forward attributes (`jakarta.servlet.forward.*`) are set on
the first forward in the chain.

### Include

```java
req.getRequestDispatcher("/fragment").include(req, resp);
```

Include inserts another servlet's output into the current response. Include attributes
(`jakarta.servlet.include.*`) are set for the included servlet.

### Named dispatchers

```java
ctx.getNamedDispatcher("myServlet").forward(req, resp);
```

Named dispatchers dispatch by servlet name rather than URL pattern. Forward/include
attributes are not set for named dispatches.

---

## Async Support

Servlets can start asynchronous processing using `AsyncContext`:

```java
@WebServlet(urlPatterns = "/async", asyncSupported = true)
public class AsyncServlet extends HttpServlet {
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
        AsyncContext ac = req.startAsync();
        ac.setTimeout(30000);

        // Process on a different thread
        Thread.startVirtualThread(() -> {
            try {
                String result = longRunningOperation();
                ac.getResponse().getWriter().write(result);
                ac.complete();
            } catch (Exception e) {
                ac.complete();
            }
        });
    }
}
```

### Async dispatch

```java
AsyncContext ac = req.startAsync();
ac.dispatch("/result");  // dispatch to another servlet
```

### Async listeners

```java
ac.addListener(new AsyncListener() {
    public void onComplete(AsyncEvent event) { }
    public void onTimeout(AsyncEvent event) { }
    public void onError(AsyncEvent event) { }
    public void onStartAsync(AsyncEvent event) { }
});
```

---

## Sessions

HTTP sessions are stored in-memory and tracked via cookies by default.

### Using sessions

```java
HttpSession session = req.getSession(true);  // create if needed
session.setAttribute("user", currentUser);

// Later...
User user = (User) session.getAttribute("user");
session.invalidate();  // destroy session
```

### Session configuration

Via `web.xml`:

```xml
<session-config>
    <session-timeout>30</session-timeout>  <!-- minutes -->
    <cookie-config>
        <name>JSESSIONID</name>
        <path>/</path>
        <http-only>true</http-only>
        <secure>true</secure>
    </cookie-config>
    <tracking-mode>COOKIE</tracking-mode>
</session-config>
```

### CDI @SessionScoped

Beans annotated with `@SessionScoped` have their lifecycle tied to the HTTP session:

```java
@SessionScoped
public class ShoppingCart implements Serializable {
    private List<Item> items = new ArrayList<>();

    public void addItem(Item item) { items.add(item); }
    public List<Item> getItems() { return items; }
}
```

CDI lifecycle events are fired:
- `@Initialized(SessionScoped.class)` - when a session is created
- `@BeforeDestroyed(SessionScoped.class)` - before session is destroyed
- `@Destroyed(SessionScoped.class)` - after session is destroyed

---

## Static Resources

Static files in `META-INF/resources/` on the classpath are served automatically by the
built-in `DefaultServlet` (mapped to `/`).

```
src/main/resources/META-INF/resources/
  index.html
  css/style.css
  js/app.js
```

These are accessible at `/index.html`, `/css/style.css`, `/js/app.js`.

### Caching

In production mode, static resources are cached in memory (up to 1000 entries). In dev
mode, resources are read fresh from disk to support hot reload.

### Welcome files

Configure via `web.xml`:

```xml
<welcome-file-list>
    <welcome-file>index.html</welcome-file>
    <welcome-file>index.htm</welcome-file>
</welcome-file-list>
```

---

## Error Pages

### By status code

```xml
<error-page>
    <error-code>404</error-code>
    <location>/error/404</location>
</error-page>
```

### By exception type

```xml
<error-page>
    <exception-type>java.lang.RuntimeException</exception-type>
    <location>/error/exception</location>
</error-page>
```

### Error attributes

Error page servlets receive these request attributes:

| Attribute | Type | Description |
|---|---|---|
| `jakarta.servlet.error.status_code` | `Integer` | HTTP status code |
| `jakarta.servlet.error.exception` | `Throwable` | The exception |
| `jakarta.servlet.error.exception_type` | `Class` | Exception class |
| `jakarta.servlet.error.message` | `String` | Error message |
| `jakarta.servlet.error.request_uri` | `String` | Original request URI |
| `jakarta.servlet.error.servlet_name` | `String` | Servlet that failed |

### Dev mode

In dev mode, unhandled exceptions show a rich error page with stack traces and source
code decoration (via Quarkus's built-in error handler). Unmatched URLs show a route
listing page with all registered servlet endpoints.

---

## Multipart File Upload

Servlets can receive multipart form data (file uploads) using `getParts()`:

```java
@WebServlet(urlPatterns = "/upload")
@MultipartConfig(maxFileSize = 10_000_000) // 10MB
public class UploadServlet extends HttpServlet {
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws IOException, ServletException {
        for (Part part : req.getParts()) {
            if (part.getSubmittedFileName() != null) {
                // File upload
                part.write("/tmp/" + part.getSubmittedFileName());
            } else {
                // Form field
                String value = new String(part.getInputStream().readAllBytes());
            }
        }
        resp.getWriter().write("Upload complete");
    }
}
```

Individual parts can be retrieved by name:

```java
Part photo = req.getPart("photo");
```

File uploads are handled by Vert.x's multipart parser. The `Part` implementation wraps
Vert.x `FileUpload` objects for file parts and form attribute values for non-file parts.

---

## Non-blocking I/O

For async servlets, `ReadListener` and `WriteListener` provide callback-based I/O:

```java
@WebServlet(urlPatterns = "/async-io", asyncSupported = true)
public class AsyncIOServlet extends HttpServlet {
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        AsyncContext ac = req.startAsync();
        ServletOutputStream out = resp.getOutputStream();
        out.setWriteListener(new WriteListener() {
            @Override
            public void onWritePossible() throws IOException {
                while (out.isReady()) {
                    out.write("data chunk\n".getBytes());
                }
            }
            @Override
            public void onError(Throwable t) {
                ac.complete();
            }
        });
    }
}
```

The `WriteListener` integrates with Vert.x's backpressure mechanism - `isReady()` checks
`response.writeQueueFull()` and `onWritePossible()` is called via `drainHandler()`.

---

## HTTP Upgrade

Servlets can upgrade HTTP connections to raw TCP for custom protocols:

```java
@WebServlet(urlPatterns = "/upgrade")
public class UpgradeServlet extends HttpServlet {
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws IOException, ServletException {
        if ("upgrade-protocol".equals(req.getHeader("Upgrade"))) {
            req.upgrade(MyUpgradeHandler.class);
        }
    }
}

public class MyUpgradeHandler implements HttpUpgradeHandler {
    @Override
    public void init(WebConnection wc) {
        // Use wc.getInputStream() and wc.getOutputStream()
        // for raw TCP communication
    }

    @Override
    public void destroy() { }
}
```

The upgrade is implemented via Vert.x's `request.toNetSocket()`, which downgrades the
HTTP connection to a raw `NetSocket`. The `WebConnection` wraps this socket with
`ServletInputStream`/`ServletOutputStream` adapters.

---

## Security

### Quarkus Security bridge

The extension bridges Quarkus Security (`SecurityIdentity`) into the servlet security API:

```java
// These work when Quarkus Security is configured
Principal user = req.getUserPrincipal();
boolean isAdmin = req.isUserInRole("admin");
String username = req.getRemoteUser();
```

### Programmatic login/logout

```java
req.login("username", "password");  // authenticates via IdentityProviderManager
req.logout();                        // invalidates session and clears identity
req.authenticate(resp);              // triggers authentication challenge
```

### CDI security annotations

`@RolesAllowed`, `@Inject SecurityIdentity`, and other Quarkus Security features work
in CDI beans called from servlets:

```java
@ApplicationScoped
public class SecureService {
    @RolesAllowed("admin")
    public void adminOnly() { }
}
```

### Auth type

`req.getAuthType()` returns the authentication type string from the `SecurityIdentity`
attribute `quarkus.http.auth.type` when the user is authenticated.

### Declarative security constraints

Security constraints from `web.xml` are enforced automatically:

```xml
<security-constraint>
    <web-resource-collection>
        <web-resource-name>Admin area</web-resource-name>
        <url-pattern>/admin/*</url-pattern>
    </web-resource-collection>
    <auth-constraint>
        <role-name>admin</role-name>
    </auth-constraint>
</security-constraint>

<security-constraint>
    <web-resource-collection>
        <web-resource-name>Deny all</web-resource-name>
        <url-pattern>/internal/*</url-pattern>
    </web-resource-collection>
    <auth-constraint/>  <!-- empty = deny all -->
</security-constraint>
```

Constraints are evaluated at the Vert.x HTTP routing layer before the servlet handler
runs. The `SecurityIdentity` roles (from Quarkus Security) are checked against the
declared role names. Transport guarantees (`CONFIDENTIAL`) require HTTPS.

---

## CDI Integration

### Injectable types

The following types can be injected with `@Inject` in any CDI bean during a servlet
request:

```java
@Inject HttpServletRequest request;
@Inject HttpServletResponse response;
@Inject HttpSession session;
@Inject SecurityIdentity identity;
```

### CDI in servlets

Servlets, filters, and listeners annotated with `@WebServlet`, `@WebFilter`, or
`@WebListener` are automatically CDI beans. You can inject dependencies:

```java
@WebServlet(urlPatterns = "/users")
public class UserServlet extends HttpServlet {
    @Inject
    UserService userService;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        resp.getWriter().write(userService.list().toString());
    }
}
```

### @Typed annotation

Servlet, filter, and listener classes automatically get `@Typed({ClassName.class})`
added at build time. This prevents them from being injectable by their supertypes
(`Servlet`, `Filter`, `EventListener`), avoiding ambiguous dependency errors when
multiple servlets exist.

---

## Configuration Reference

### Runtime properties

| Property | Default | Description |
|---|---|---|
| `quarkus.servlet.context-path` | `/` | Servlet context path, relative to `quarkus.http.root-path` |
| `quarkus.servlet.buffer-size` | `8192` | Output stream buffer size in bytes |
| `quarkus.servlet.max-parameters` | `1000` | Maximum number of request parameters (query + form body). Returns 400 if exceeded. |
| `quarkus.servlet.virtual-threads` | `false` | When true, all servlets run on virtual threads by default |
| `quarkus.servlet.disallowed-methods` | (none) | Comma-separated HTTP methods to reject with 405. Example: `TRACE,OPTIONS` |
| `quarkus.servlet.default-charset` | (none) | Default character encoding for requests and responses |

### Inherited from Quarkus HTTP

These properties are handled by the Vert.x HTTP layer and apply to servlet requests:

| Property | Default | Description |
|---|---|---|
| `quarkus.http.port` | `8080` | HTTP listen port |
| `quarkus.http.host` | `0.0.0.0` | HTTP listen address |
| `quarkus.http.root-path` | `/` | Root path for all HTTP endpoints |
| `quarkus.http.limits.max-body-size` | `10240K` | Maximum request body size |
| `quarkus.http.enable-compression` | `false` | Enable gzip/deflate response compression |
| `quarkus.http.ssl.*` | | SSL/TLS configuration |
| `quarkus.http.proxy.*` | | Proxy header handling (X-Forwarded-For, etc.) |
| `quarkus.http.access-log.enabled` | `false` | Enable access logging |
| `quarkus.http.auth.proactive` | `true` | Proactive vs lazy authentication |
| `quarkus.shutdown.timeout` | `10s` | Graceful shutdown timeout |

---

## Dev UI and Dev MCP

In dev mode, the extension provides pages in the Quarkus Dev UI at `/q/dev-ui`:

- **Servlets** - table of registered servlets with URL patterns, dispatch mode, and status
- **Filters** - table of registered filters with priority
- **Sessions** - active session count and list with an Invalidate button

### Dev MCP tools

The same data is exposed as MCP tools for AI agents:

| Tool | Auto-enabled | Description |
|---|---|---|
| `getServlets()` | Yes | List all registered servlets |
| `getFilters()` | Yes | List all registered filters |
| `getServletContext()` | Yes | Context path, init params, welcome files, error pages |
| `getActiveSessions()` | No | List active sessions (sensitive) |
| `invalidateSession(id)` | No | Force-invalidate a session (mutating) |

---

## Migration from quarkus-undertow

### Dependency change

Replace:

```xml
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-undertow</artifactId>
</dependency>
```

With:

```xml
<dependency>
    <groupId>io.quarkiverse.servlet</groupId>
    <artifactId>quarkus-servlet</artifactId>
</dependency>
```

### Threading model change

`quarkus-undertow` runs all servlets on worker threads. `quarkus-servlet` runs them
on the event loop by default. For a safe migration, enable virtual threads globally:

```properties
quarkus.servlet.virtual-threads=true
```

This gives you the same blocking-safe behavior as Undertow. Once migrated, you can
optionally remove this and annotate only the servlets that truly need blocking I/O
with `@RunOnVirtualThread` for better performance.

### SPI compatibility

The build item API is compatible. `ServletBuildItem`, `FilterBuildItem`,
`ListenerBuildItem`, `ServletContextPathBuildItem`, `WebMetadataBuildItem`, and other
SPI classes have the same method signatures. Extensions that produce these build items
(like `resteasy-classic`) will work without changes.

### undertow-handlers.conf

The Undertow-specific `META-INF/undertow-handlers.conf` predicated handler language is
not supported. URL rewrites and access control rules should be migrated to Quarkus
Vert.x HTTP routing or handled by a reverse proxy.

### Configuration mapping

| quarkus-undertow | quarkus-servlet | Notes |
|---|---|---|
| `quarkus.servlet.context-path` | `quarkus.servlet.context-path` | Same |
| `quarkus.servlet.buffer-size` | `quarkus.servlet.buffer-size` | Same concept, different default |
| `quarkus.servlet.max-parameters` | `quarkus.servlet.max-parameters` | Same |
| `quarkus.servlet.disallowed-methods` | `quarkus.servlet.disallowed-methods` | Same |
| `quarkus.servlet.direct-buffers` | N/A | Not applicable (byte array based) |
| `quarkus.servlet.record-request-start-time` | N/A | Use `quarkus.http.record-request-start-time` |

---

## Known Limitations

| Feature | Status | Notes |
|---|---|---|
| Session URL rewriting (`encodeURL()`) | Not planned | Cookie-based tracking is the modern approach |
| `undertow-handlers.conf` | Not supported | Undertow-specific; use Vert.x routing |
| JSP | Minimal stub only | Use a template engine (Qute, Thymeleaf) instead |
| Clustered sessions | Not implemented | In-memory sessions only |
