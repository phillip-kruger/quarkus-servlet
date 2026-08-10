package io.quarkiverse.servlet.runtime;

import java.io.IOException;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerResponse;

public class VertxServletOutputStream extends ServletOutputStream {

    private static final int DEFAULT_BUFFER_SIZE = 8192;

    private final HttpServerResponse response;
    private byte[] buffer;
    private int count;
    private boolean closed;
    private boolean committed;
    private boolean suspended;

    public VertxServletOutputStream(HttpServerResponse response) {
        this.response = response;
        this.buffer = new byte[DEFAULT_BUFFER_SIZE];
    }

    public void suspend() {
        this.suspended = true;
    }

    @Override
    public void write(int b) throws IOException {
        if (suspended) {
            return;
        }
        checkClosed();
        if (count >= buffer.length) {
            flushInternal();
        }
        buffer[count++] = (byte) b;
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        if (suspended) {
            return;
        }
        checkClosed();
        if (len == 0) {
            return;
        }
        if (len > buffer.length - count) {
            flushInternal();
            if (len > buffer.length) {
                ensureHeadersWritten();
                writeToVertx(b, off, len);
                return;
            }
        }
        System.arraycopy(b, off, buffer, count, len);
        count += len;
    }

    @Override
    public void flush() throws IOException {
        if (suspended) {
            return;
        }
        checkClosed();
        flushInternal();
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        if (count > 0) {
            if (!committed && !response.headers().contains("Content-Length")) {
                response.headers().set("Content-Length", Integer.toString(count));
            }
            ensureHeadersWritten();
            Buffer vertxBuffer = Buffer.buffer(count);
            vertxBuffer.appendBytes(buffer, 0, count);
            count = 0;
            response.end(vertxBuffer);
        } else if (!response.ended()) {
            if (!committed && !response.headers().contains("Content-Length")) {
                response.headers().set("Content-Length", "0");
            }
            ensureHeadersWritten();
            response.end();
        }
    }

    @Override
    public boolean isReady() {
        return !response.writeQueueFull();
    }

    @Override
    public void setWriteListener(WriteListener writeListener) {
        if (writeListener == null) {
            throw new NullPointerException("WriteListener must not be null");
        }
        response.drainHandler(v -> {
            try {
                writeListener.onWritePossible();
            } catch (Throwable t) {
                writeListener.onError(t);
            }
        });
        Thread.startVirtualThread(() -> {
            try {
                writeListener.onWritePossible();
            } catch (Throwable t) {
                writeListener.onError(t);
            }
        });
    }

    int getBufferSize() {
        return buffer.length;
    }

    void setBufferSize(int size) {
        if (committed) {
            throw new IllegalStateException("Cannot set buffer size after content has been written");
        }
        buffer = new byte[size];
        count = 0;
    }

    void resetBuffer() {
        if (committed) {
            throw new IllegalStateException("Cannot reset buffer after response has been committed");
        }
        count = 0;
    }

    boolean hasWritten() {
        return committed;
    }

    private void flushInternal() throws IOException {
        ensureHeadersWritten();
        if (count > 0) {
            writeToVertx(buffer, 0, count);
            count = 0;
        } else if (!response.headWritten()) {
            writeToVertx(new byte[0], 0, 0);
        }
    }

    private void writeToVertx(byte[] data, int off, int len) {
        Buffer vertxBuffer = Buffer.buffer(len);
        vertxBuffer.appendBytes(data, off, len);
        response.write(vertxBuffer);
    }

    private void ensureHeadersWritten() {
        if (!committed) {
            committed = true;
            if (!response.headers().contains("Content-Length")) {
                response.setChunked(true);
            }
        }
    }

    private void checkClosed() throws IOException {
        if (closed) {
            throw new IOException("Stream is closed");
        }
    }
}
