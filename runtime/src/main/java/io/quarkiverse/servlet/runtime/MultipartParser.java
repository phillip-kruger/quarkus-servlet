package io.quarkiverse.servlet.runtime;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import jakarta.servlet.ServletException;

/**
 * Parser for {@code multipart/form-data} bodies (RFC 7578).
 * <p>
 * The container buffers request bodies itself, so parts are parsed straight out of those bytes
 * rather than relying on Vert.x's body handling being configured. That keeps multipart working
 * identically whether the request arrived through the Quarkus route or the TCK harness.
 */
final class MultipartParser {

    private static final byte CR = '\r';
    private static final byte LF = '\n';

    private MultipartParser() {
    }

    /**
     * Extracts the boundary from a {@code multipart/*} Content-Type header.
     *
     * @return the boundary, or {@code null} if the header carries none
     */
    static String boundaryOf(String contentType) {
        if (contentType == null) {
            return null;
        }
        for (String param : contentType.split(";")) {
            String trimmed = param.trim();
            if (trimmed.regionMatches(true, 0, "boundary=", 0, "boundary=".length())) {
                String value = trimmed.substring("boundary=".length()).trim();
                if (value.length() >= 2 && value.charAt(0) == '"' && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                return value.isEmpty() ? null : value;
            }
        }
        return null;
    }

    /**
     * Parses {@code body} into parts.
     *
     * @param limits size limits to enforce; may be {@code null} for no limits
     * @throws ServletException if the body is malformed or a limit is exceeded
     */
    static List<VertxPart> parse(byte[] body, String boundary, Charset headerCharset,
            MultipartConfiguration limits) throws ServletException {

        if (limits != null && limits.maxRequestSize() > 0 && body.length > limits.maxRequestSize()) {
            throw new IllegalStateException(
                    "Request exceeds maxRequestSize of " + limits.maxRequestSize());
        }

        byte[] delimiter = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        List<VertxPart> parts = new ArrayList<>();

        int position = indexOf(body, delimiter, 0);
        if (position < 0) {
            // No boundary at all: nothing to hand back, which is what a body-less multipart
            // request looks like.
            return parts;
        }

        while (position >= 0) {
            int afterDelimiter = position + delimiter.length;

            // "--" right after the delimiter marks the final boundary.
            if (afterDelimiter + 1 < body.length
                    && body[afterDelimiter] == '-' && body[afterDelimiter + 1] == '-') {
                break;
            }

            int headerStart = skipLineBreak(body, afterDelimiter);
            if (headerStart < 0) {
                break;
            }

            int headerEnd = indexOfHeaderEnd(body, headerStart);
            if (headerEnd < 0) {
                throw new ServletException("Malformed multipart body: unterminated part headers");
            }

            Map<String, List<String>> headers = parseHeaders(
                    new String(body, headerStart, headerEnd - headerStart, headerCharset));

            int contentStart = skipLineBreak(body, skipLineBreak(body, headerEnd));
            if (contentStart < 0) {
                throw new ServletException("Malformed multipart body: missing part content");
            }

            int nextDelimiter = indexOf(body, delimiter, contentStart);
            if (nextDelimiter < 0) {
                throw new ServletException("Malformed multipart body: missing closing boundary");
            }

            // The CRLF immediately before the next delimiter belongs to the delimiter, not content.
            int contentEnd = nextDelimiter;
            if (contentEnd - 1 >= contentStart && body[contentEnd - 1] == LF) {
                contentEnd--;
            }
            if (contentEnd - 1 >= contentStart && body[contentEnd - 1] == CR) {
                contentEnd--;
            }

            byte[] content = new byte[Math.max(0, contentEnd - contentStart)];
            System.arraycopy(body, contentStart, content, 0, content.length);

            VertxPart part = newPart(headers, content, headerCharset);
            if (limits != null && limits.maxFileSize() > 0
                    && part.getSubmittedFileName() != null
                    && content.length > limits.maxFileSize()) {
                throw new IllegalStateException(
                        "Part " + part.getName() + " exceeds maxFileSize of " + limits.maxFileSize());
            }
            parts.add(part);

            position = nextDelimiter;
        }

        return parts;
    }

    private static VertxPart newPart(Map<String, List<String>> headers, byte[] content,
            Charset headerCharset) throws ServletException {

        String disposition = firstHeader(headers, "content-disposition");
        if (disposition == null) {
            throw new ServletException("Malformed multipart body: part without Content-Disposition");
        }
        String name = dispositionParameter(disposition, "name");
        if (name == null) {
            throw new ServletException("Malformed multipart body: part without a name");
        }
        String fileName = dispositionParameter(disposition, "filename");
        String contentType = firstHeader(headers, "content-type");

        return new VertxPart(name, fileName, contentType, content, headers, headerCharset);
    }

    private static String firstHeader(Map<String, List<String>> headers, String name) {
        List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
        return (values == null || values.isEmpty()) ? null : values.get(0);
    }

    /**
     * Reads a parameter out of a Content-Disposition value, handling both quoted and bare forms.
     */
    static String dispositionParameter(String disposition, String parameter) {
        for (String segment : splitRespectingQuotes(disposition)) {
            String trimmed = segment.trim();
            int eq = trimmed.indexOf('=');
            if (eq < 0) {
                continue;
            }
            if (!trimmed.substring(0, eq).trim().equalsIgnoreCase(parameter)) {
                continue;
            }
            String value = trimmed.substring(eq + 1).trim();
            if (value.length() >= 2 && value.charAt(0) == '"' && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            return value;
        }
        return null;
    }

    private static List<String> splitRespectingQuotes(String value) {
        List<String> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
                current.append(c);
            } else if (c == ';' && !inQuotes) {
                segments.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        segments.add(current.toString());
        return segments;
    }

    private static Map<String, List<String>> parseHeaders(String block) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        String pendingName = null;
        StringBuilder pendingValue = new StringBuilder();

        for (String line : block.split("\r\n|\n")) {
            if (line.isEmpty()) {
                continue;
            }
            if ((line.charAt(0) == ' ' || line.charAt(0) == '\t') && pendingName != null) {
                // obs-fold continuation line
                pendingValue.append(' ').append(line.trim());
                continue;
            }
            if (pendingName != null) {
                headers.computeIfAbsent(pendingName, k -> new ArrayList<>()).add(pendingValue.toString());
                pendingValue.setLength(0);
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                pendingName = null;
                continue;
            }
            pendingName = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            pendingValue.append(line.substring(colon + 1).trim());
        }
        if (pendingName != null) {
            headers.computeIfAbsent(pendingName, k -> new ArrayList<>()).add(pendingValue.toString());
        }
        return headers;
    }

    /** Index of the blank line terminating a header block, i.e. the start of that line break. */
    private static int indexOfHeaderEnd(byte[] body, int from) {
        for (int i = from; i < body.length - 1; i++) {
            if (body[i] == CR && body[i + 1] == LF) {
                if (i + 3 < body.length && body[i + 2] == CR && body[i + 3] == LF) {
                    return i;
                }
                if (i + 2 < body.length && body[i + 2] == LF) {
                    return i;
                }
            } else if (body[i] == LF && body[i + 1] == LF) {
                return i;
            }
        }
        return -1;
    }

    private static int skipLineBreak(byte[] body, int position) {
        if (position < 0 || position >= body.length) {
            return -1;
        }
        if (body[position] == CR && position + 1 < body.length && body[position + 1] == LF) {
            return position + 2;
        }
        if (body[position] == LF) {
            return position + 1;
        }
        return position;
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        outer: for (int i = Math.max(0, from); i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

}
