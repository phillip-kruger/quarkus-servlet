package io.quarkiverse.servlet.runtime;

/**
 * The limits a servlet declared with {@code @MultipartConfig} or {@code <multipart-config>}.
 *
 * @param maxFileSize maximum size of an individual uploaded file, or {@code -1} for no limit
 * @param maxRequestSize maximum size of the whole request, or {@code -1} for no limit
 * @param fileSizeThreshold size above which a part would be written to disk
 * @param location directory that relative {@code Part.write(String)} names resolve against
 */
public record MultipartConfiguration(long maxFileSize, long maxRequestSize, int fileSizeThreshold,
        String location) {

    public static final MultipartConfiguration UNLIMITED = new MultipartConfiguration(-1, -1, 0, null);
}
