package io.quarkiverse.servlet.runtime;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;

import io.vertx.core.http.HttpServerResponse;

public class VertxServletResponse implements HttpServletResponse {

    private final HttpServerResponse response;
    private final VertxServletRequest request;

    private VertxServletOutputStream outputStream;
    private PrintWriter writer;
    private boolean outputStreamUsed;
    private boolean writerUsed;
    private boolean errorSent;
    private Supplier<Map<String, String>> trailerFieldsSupplier;
    private boolean redirectSent;
    private String characterEncoding;
    private boolean characterEncodingExplicitlySet;
    private boolean charsetLocked;
    private Locale locale = Locale.getDefault();
    private String statusMessage;
    private int errorCode;
    private String errorMessage;

    public VertxServletResponse(HttpServerResponse response, VertxServletRequest request) {
        this.response = response;
        this.request = request;
    }

    // ---- Status ----

    @Override
    public void setStatus(int sc) {
        response.setStatusCode(sc);
    }

    @Override
    public int getStatus() {
        return response.getStatusCode();
    }

    // ---- Headers ----

    @Override
    public void setHeader(String name, String value) {
        if (isCommitted()) {
            return;
        }
        response.headers().set(name, value);
    }

    @Override
    public void addHeader(String name, String value) {
        if (isCommitted()) {
            return;
        }
        response.headers().add(name, value);
    }

    @Override
    public String getHeader(String name) {
        return response.headers().get(name);
    }

    @Override
    public Collection<String> getHeaders(String name) {
        return new java.util.ArrayList<>(response.headers().getAll(name));
    }

    @Override
    public Collection<String> getHeaderNames() {
        return response.headers().names();
    }

    @Override
    public boolean containsHeader(String name) {
        return response.headers().contains(name);
    }

    @Override
    public void setDateHeader(String name, long date) {
        if (isCommitted()) {
            return;
        }
        response.headers().set(name, formatHttpDate(date));
    }

    @Override
    public void addDateHeader(String name, long date) {
        if (isCommitted()) {
            return;
        }
        response.headers().add(name, formatHttpDate(date));
    }

    @Override
    public void setIntHeader(String name, int value) {
        if (isCommitted()) {
            return;
        }
        response.headers().set(name, String.valueOf(value));
    }

    @Override
    public void addIntHeader(String name, int value) {
        if (isCommitted()) {
            return;
        }
        response.headers().add(name, String.valueOf(value));
    }

    // ---- Cookies ----

    @Override
    public void addCookie(Cookie cookie) {
        StringBuilder sb = new StringBuilder();
        sb.append(cookie.getName()).append('=').append(cookie.getValue() != null ? cookie.getValue() : "");

        if (cookie.getDomain() != null) {
            sb.append("; Domain=").append(cookie.getDomain());
        }
        if (cookie.getPath() != null) {
            sb.append("; Path=").append(cookie.getPath());
        }
        if (cookie.getMaxAge() > 0) {
            sb.append("; Max-Age=").append(cookie.getMaxAge());
        } else if (cookie.getMaxAge() == 0) {
            // RFC 6265: zero max-age means delete - send Expires in the past, no Max-Age
            sb.append("; Expires=Thu, 01 Jan 1970 00:00:00 GMT");
        }
        if (cookie.getSecure()) {
            sb.append("; Secure");
        }
        if (cookie.isHttpOnly()) {
            sb.append("; HttpOnly");
        }
        // Handle custom attributes (Servlet 6.0+)
        Map<String, String> attributes = cookie.getAttributes();
        if (attributes != null) {
            for (Map.Entry<String, String> attr : attributes.entrySet()) {
                String key = attr.getKey();
                String val = attr.getValue();
                // Skip attributes already handled above
                if (key.equalsIgnoreCase("Domain") || key.equalsIgnoreCase("Path")
                        || key.equalsIgnoreCase("Max-Age") || key.equalsIgnoreCase("Expires")
                        || key.equalsIgnoreCase("Secure") || key.equalsIgnoreCase("HttpOnly")) {
                    continue;
                }
                if (val == null || val.isEmpty()) {
                    sb.append("; ").append(key);
                } else {
                    sb.append("; ").append(key).append('=').append(val);
                }
            }
        }

        response.headers().add("Set-Cookie", sb.toString());
    }

    // ---- Redirect and Error ----

    @Override
    public void sendRedirect(String location) throws IOException {
        checkNotCommitted();
        response.setStatusCode(SC_FOUND);
        response.headers().set("Location", toAbsoluteUrl(location));
        redirectSent = true;
        response.end();
    }

    @Override
    public void sendRedirect(String location, int sc, boolean clearBuffer) throws IOException {
        checkNotCommitted();
        if (clearBuffer && outputStream != null) {
            outputStream.resetBuffer();
        }
        response.setStatusCode(sc);
        response.headers().set("Location", toAbsoluteUrl(location));
        redirectSent = true;
        response.end();
    }

    @Override
    public void sendError(int sc) throws IOException {
        sendError(sc, null);
    }

    @Override
    public void sendError(int sc, String msg) throws IOException {
        checkNotCommitted();
        response.setStatusCode(sc);
        if (outputStream != null) {
            outputStream.resetBuffer();
            outputStream.suspend();
        }
        errorSent = true;
        errorCode = sc;
        errorMessage = msg;
    }

    public boolean isErrorSent() {
        return errorSent;
    }

    public int getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void writeDefaultErrorPage() throws IOException {
        String body = buildErrorBody(errorCode, errorMessage);
        response.headers().set("Content-Type", "text/html; charset=UTF-8");
        response.end(body);
    }

    // ---- Output Stream and Writer ----

    @Override
    public ServletOutputStream getOutputStream() throws IOException {
        checkResponseActive();
        if (writerUsed) {
            throw new IllegalStateException("getWriter() has already been called on this response");
        }
        if (outputStream == null) {
            outputStream = new VertxServletOutputStream(response);
            if (request != null) {
                outputStream.setCallbackExecutor(request::runOnRequestThread);
            }
        }
        outputStreamUsed = true;
        return outputStream;
    }

    @Override
    public PrintWriter getWriter() throws IOException {
        checkResponseActive();
        if (outputStreamUsed) {
            throw new IllegalStateException("getOutputStream() has already been called on this response");
        }
        if (writer == null) {
            if (outputStream == null) {
                outputStream = new VertxServletOutputStream(response);
            }
            writer = new PrintWriter(new OutputStreamWriter(outputStream, getCharacterEncoding()));
        }
        writerUsed = true;
        charsetLocked = true;
        return writer;
    }

    // ---- Buffer management ----

    @Override
    public void reset() {
        if (isCommitted()) {
            throw new IllegalStateException("Response is already committed");
        }
        response.headers().clear();
        response.setStatusCode(SC_OK);
        if (outputStream != null) {
            outputStream.resetBuffer();
        }
        writer = null;
        writerUsed = false;
        outputStreamUsed = false;
        charsetLocked = false;
        characterEncoding = null;
        characterEncodingExplicitlySet = false;
    }

    @Override
    public void resetBuffer() {
        if (isCommitted()) {
            throw new IllegalStateException("Response is already committed");
        }
        if (outputStream != null) {
            outputStream.resetBuffer();
        }
    }

    @Override
    public void flushBuffer() throws IOException {
        if (writer != null) {
            writer.flush();
        }
        if (outputStream == null) {
            outputStream = new VertxServletOutputStream(response);
        }
        outputStream.flush();
    }

    @Override
    public int getBufferSize() {
        if (outputStream != null) {
            return outputStream.getBufferSize();
        }
        return 0;
    }

    @Override
    public void setBufferSize(int size) {
        if (outputStream != null && outputStream.hasWritten()) {
            throw new IllegalStateException("Content has already been written");
        }
        if (outputStream == null) {
            outputStream = new VertxServletOutputStream(response);
        }
        outputStream.setBufferSize(size);
    }

    // ---- Committed state ----

    @Override
    public boolean isCommitted() {
        return response.headWritten() || errorSent || redirectSent;
    }

    // ---- Content-Type and Encoding ----

    @Override
    public void setContentType(String type) {
        if (isCommitted()) {
            return;
        }
        if (type == null) {
            response.headers().remove("Content-Type");
            return;
        }

        // Parse the incoming type to separate MIME type from charset
        String mimeType = type;
        String incomingCharset = null;
        int semiIndex = type.indexOf(';');
        if (semiIndex >= 0) {
            mimeType = type.substring(0, semiIndex).trim();
            String params = type.substring(semiIndex + 1).trim();
            int charsetIndex = params.toLowerCase(Locale.ROOT).indexOf("charset=");
            if (charsetIndex >= 0) {
                incomingCharset = params.substring(charsetIndex + 8).trim();
                int trailingSemicolon = incomingCharset.indexOf(';');
                if (trailingSemicolon >= 0) {
                    incomingCharset = incomingCharset.substring(0, trailingSemicolon).trim();
                }
            }
        }

        // Update charset only if not locked by getWriter()
        if (incomingCharset != null && !charsetLocked) {
            characterEncoding = incomingCharset;
            characterEncodingExplicitlySet = true;
        }

        // Build the Content-Type header value
        // If charset is locked (writer obtained), use the locked charset
        // If charset was explicitly set, include it
        if (charsetLocked) {
            response.headers().set("Content-Type", mimeType + ";charset=" + getCharacterEncoding());
        } else if (characterEncodingExplicitlySet || characterEncoding != null) {
            response.headers().set("Content-Type",
                    mimeType + ";charset=" + (characterEncoding != null ? characterEncoding : "ISO-8859-1"));
        } else {
            response.headers().set("Content-Type", mimeType);
        }
    }

    @Override
    public String getContentType() {
        String ct = response.headers().get("Content-Type");
        if (ct == null) {
            return null;
        }
        return ct;
    }

    @Override
    public void setCharacterEncoding(String charset) {
        if (charsetLocked || isCommitted()) {
            return;
        }
        if (charset == null) {
            characterEncoding = null;
            characterEncodingExplicitlySet = false;
        } else {
            characterEncoding = charset;
            characterEncodingExplicitlySet = true;
        }
        // Update Content-Type header if already set
        String contentType = getContentType();
        if (contentType != null) {
            // Strip existing charset parameter
            int charsetIdx = contentType.indexOf(';');
            String baseType = contentType;
            String remainingParams = "";
            if (charsetIdx >= 0) {
                baseType = contentType.substring(0, charsetIdx).trim();
                // Preserve non-charset parameters
                String params = contentType.substring(charsetIdx);
                StringBuilder sb = new StringBuilder();
                for (String part : params.split(";")) {
                    String trimmed = part.trim();
                    if (!trimmed.isEmpty() && !trimmed.toLowerCase(Locale.ROOT).startsWith("charset=")) {
                        sb.append("; ").append(trimmed);
                    }
                }
                remainingParams = sb.toString();
            }
            if (characterEncodingExplicitlySet) {
                response.headers().set("Content-Type", baseType + ";charset=" + characterEncoding + remainingParams);
            } else {
                response.headers().set("Content-Type", baseType + remainingParams);
            }
        }
    }

    @Override
    public String getCharacterEncoding() {
        if (characterEncoding == null) {
            return "ISO-8859-1";
        }
        return characterEncoding;
    }

    // ---- Content-Length ----

    @Override
    public void setContentLength(int len) {
        response.headers().set("Content-Length", String.valueOf(len));
    }

    @Override
    public void setContentLengthLong(long len) {
        response.headers().set("Content-Length", String.valueOf(len));
    }

    // ---- Locale ----

    @Override
    public void setLocale(Locale loc) {
        if (loc == null || isCommitted()) {
            return;
        }
        this.locale = loc;

        // Set Content-Language header
        String language = loc.getLanguage();
        if (language != null && !language.isEmpty()) {
            String country = loc.getCountry();
            String value = (country != null && !country.isEmpty()) ? language + "-" + country : language;
            response.headers().set("Content-Language", value);
        }

        // Derive charset from locale if not explicitly set and not locked
        if (!characterEncodingExplicitlySet && !charsetLocked) {
            // Common locale-to-charset mappings
            String derivedCharset = deriveCharsetFromLocale(loc);
            if (derivedCharset != null) {
                characterEncoding = derivedCharset;
                // Update Content-Type if present
                String contentType = getContentType();
                if (contentType != null && !contentType.toLowerCase(Locale.ROOT).contains("charset=")) {
                    response.headers().set("Content-Type", contentType + "; charset=" + characterEncoding);
                }
            }
        }
    }

    @Override
    public Locale getLocale() {
        return locale;
    }

    // ---- URL encoding (no session tracking in Phase 1) ----

    @Override
    public String encodeURL(String url) {
        return url;
    }

    @Override
    public String encodeRedirectURL(String url) {
        return url;
    }

    // ---- Close / lifecycle ----

    /**
     * Flushes any remaining buffered content and ends the Vert.x response.
     */
    public void close() throws IOException {
        if (writer != null) {
            writer.flush();
        }
        if (outputStream != null) {
            outputStream.flush();
        }
        if (!response.ended()) {
            applyTrailerFields();
            response.end();
        }
    }

    /**
     * Copies the application's trailer fields onto the Vert.x response. Must run after the body has
     * been flushed and before {@code end()}, which is when trailers are actually written.
     */
    private void applyTrailerFields() {
        Supplier<Map<String, String>> supplier = trailerFieldsSupplier;
        if (supplier == null) {
            return;
        }
        Map<String, String> trailers;
        try {
            trailers = supplier.get();
        } catch (RuntimeException e) {
            // A broken supplier must not prevent the response from completing.
            return;
        }
        if (trailers == null || trailers.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> entry : trailers.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                response.putTrailer(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue());
            }
        }
    }

    @Override
    public void setTrailerFields(Supplier<Map<String, String>> supplier) {
        if (isCommitted()) {
            throw new IllegalStateException("Cannot set trailer fields after the response is committed");
        }
        if (request != null && request.getProtocol() != null
                && request.getProtocol().equals("HTTP/1.0")) {
            throw new IllegalStateException("Trailer fields are not supported on HTTP/1.0");
        }
        this.trailerFieldsSupplier = supplier;
    }

    @Override
    public Supplier<Map<String, String>> getTrailerFields() {
        return trailerFieldsSupplier;
    }

    // ---- Internal helpers ----

    private void checkNotCommitted() {
        if (isCommitted()) {
            throw new IllegalStateException("Response is already committed");
        }
    }

    private void checkResponseActive() throws IllegalStateException {
        if (errorSent) {
            throw new IllegalStateException("Response has already been committed by sendError()");
        }
        if (redirectSent) {
            throw new IllegalStateException("Response has already been committed by sendRedirect()");
        }
    }

    private String formatHttpDate(long dateMillis) {
        ZonedDateTime dateTime = ZonedDateTime.ofInstant(Instant.ofEpochMilli(dateMillis), ZoneOffset.UTC);
        return DateTimeFormatter.RFC_1123_DATE_TIME.format(dateTime);
    }

    private String buildErrorBody(int statusCode, String message) {
        // The message routinely carries request-derived text (the classic
        // sendError(404, "Not found: " + getRequestURI()) idiom), so it must be escaped.
        String safeMessage = (message != null) ? escapeHtml(message) : null;
        String title = (safeMessage != null) ? safeMessage : "Error " + statusCode;
        return "<html><head><title>" + title + "</title></head>"
                + "<body><h1>HTTP Error " + statusCode + "</h1>"
                + (safeMessage != null ? "<p>" + safeMessage + "</p>" : "")
                + "</body></html>";
    }

    private static String escapeHtml(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private String toAbsoluteUrl(String location) {
        // Already absolute
        if (location.contains("://")) {
            return location;
        }

        String scheme = request.getScheme();
        String host = request.getServerName();
        int port = request.getServerPort();

        StringBuilder url = new StringBuilder();
        url.append(scheme).append("://").append(host);
        boolean defaultPort = ("http".equals(scheme) && port == 80)
                || ("https".equals(scheme) && port == 443);
        if (!defaultPort) {
            url.append(':').append(port);
        }

        if (location.startsWith("/")) {
            // Server-relative: just append
            url.append(location);
        } else {
            // Relative: resolve against the request URI directory
            String requestURI = request.getRequestURI();
            // Get the directory portion of the request URI
            int lastSlash = requestURI.lastIndexOf('/');
            if (lastSlash >= 0) {
                url.append(requestURI, 0, lastSlash + 1);
            } else {
                url.append('/');
            }
            url.append(location);
        }

        return url.toString();
    }

    private String deriveCharsetFromLocale(Locale locale) {
        // Check configured locale-encoding mappings from servlet context
        if (request != null && request.getServletContext() != null) {
            VertxServletContext ctx = request.getServletContext();
            String mapped = ctx.getLocaleEncodingMapping(locale);
            if (mapped != null) {
                return mapped;
            }
        }
        String language = locale.getLanguage();
        if (language == null) {
            return null;
        }
        return switch (language) {
            case "ja" -> "Shift_JIS";
            case "ko" -> "EUC-KR";
            case "zh" -> {
                String country = locale.getCountry();
                yield "TW".equals(country) ? "Big5" : "GB2312";
            }
            default -> null;
        };
    }
}
