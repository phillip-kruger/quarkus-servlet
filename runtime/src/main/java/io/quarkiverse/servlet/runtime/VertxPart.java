package io.quarkiverse.servlet.runtime;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import jakarta.servlet.http.Part;

/**
 * A single part of a {@code multipart/form-data} request, backed by the bytes the container already
 * buffered. Headers are the ones that actually appeared in the part, not a reconstruction.
 */
public class VertxPart implements Part {

    private final String name;
    private final String submittedFileName;
    private final String contentType;
    private final byte[] content;
    private final Map<String, List<String>> headers;
    private final Charset charset;

    /** Directory that relative {@link #write(String)} names resolve against. */
    private volatile Path location;

    VertxPart(String name, String submittedFileName, String contentType, byte[] content,
            Map<String, List<String>> headers, Charset charset) {
        this.name = name;
        this.submittedFileName = submittedFileName;
        this.contentType = contentType;
        this.content = content;
        this.headers = headers;
        this.charset = charset;
    }

    void setLocation(Path location) {
        this.location = location;
    }

    /** The raw bytes, used when a form field's value feeds {@code getParameter}. */
    byte[] getContent() {
        return content;
    }

    String getValueAsString() {
        return new String(content, charset != null ? charset : StandardCharsets.UTF_8);
    }

    @Override
    public InputStream getInputStream() throws IOException {
        return new ByteArrayInputStream(content);
    }

    @Override
    public String getContentType() {
        return contentType;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getSubmittedFileName() {
        return submittedFileName;
    }

    @Override
    public long getSize() {
        return content.length;
    }

    @Override
    public void write(String fileName) throws IOException {
        Path target = Path.of(fileName);
        if (!target.isAbsolute()) {
            // Per the spec, relative names resolve against the @MultipartConfig location rather
            // than the process working directory.
            Path base = location;
            target = (base != null) ? base.resolve(target) : target;
        }
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(target, content);
    }

    @Override
    public void delete() throws IOException {
        // Parts live in memory; there is no temporary file to remove.
    }

    @Override
    public String getHeader(String name) {
        List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
        return (values == null || values.isEmpty()) ? null : values.get(0);
    }

    @Override
    public Collection<String> getHeaders(String name) {
        List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
        return values != null ? Collections.unmodifiableList(values) : Collections.emptyList();
    }

    @Override
    public Collection<String> getHeaderNames() {
        return Collections.unmodifiableCollection(new ArrayList<>(headers.keySet()));
    }

    /** Copies to {@code target}, replacing anything already there. */
    void copyTo(Path target) throws IOException {
        try (InputStream in = getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
