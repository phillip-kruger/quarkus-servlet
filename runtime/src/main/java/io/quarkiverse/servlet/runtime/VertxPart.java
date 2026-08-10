package io.quarkiverse.servlet.runtime;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import jakarta.servlet.http.Part;

import io.vertx.ext.web.FileUpload;

public class VertxPart implements Part {

    private final String name;
    private final String submittedFileName;
    private final String contentType;
    private final long size;
    private final Path filePath;
    private final byte[] content;

    VertxPart(FileUpload upload) {
        this.name = upload.name();
        this.submittedFileName = upload.fileName();
        this.contentType = upload.contentType();
        this.size = upload.size();
        this.filePath = Path.of(upload.uploadedFileName());
        this.content = null;
    }

    VertxPart(String name, String value) {
        this.name = name;
        this.submittedFileName = null;
        this.contentType = "text/plain";
        this.content = value.getBytes(StandardCharsets.UTF_8);
        this.size = this.content.length;
        this.filePath = null;
    }

    @Override
    public InputStream getInputStream() throws IOException {
        if (filePath != null) {
            return Files.newInputStream(filePath);
        }
        return new ByteArrayInputStream(content != null ? content : new byte[0]);
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
        return size;
    }

    @Override
    public void write(String fileName) throws IOException {
        if (filePath != null) {
            Files.copy(filePath, Path.of(fileName));
        } else if (content != null) {
            Files.write(Path.of(fileName), content);
        }
    }

    @Override
    public void delete() throws IOException {
        if (filePath != null) {
            Files.deleteIfExists(filePath);
        }
    }

    @Override
    public String getHeader(String name) {
        if ("content-type".equalsIgnoreCase(name)) {
            return contentType;
        }
        if ("content-disposition".equalsIgnoreCase(name)) {
            StringBuilder sb = new StringBuilder("form-data; name=\"").append(this.name).append("\"");
            if (submittedFileName != null) {
                sb.append("; filename=\"").append(submittedFileName).append("\"");
            }
            return sb.toString();
        }
        return null;
    }

    @Override
    public Collection<String> getHeaders(String name) {
        String value = getHeader(name);
        return value != null ? List.of(value) : Collections.emptyList();
    }

    @Override
    public Collection<String> getHeaderNames() {
        if (submittedFileName != null) {
            return List.of("content-type", "content-disposition");
        }
        return List.of("content-disposition");
    }
}
