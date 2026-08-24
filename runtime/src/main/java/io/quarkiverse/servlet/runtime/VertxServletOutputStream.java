package io.quarkiverse.servlet.runtime;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

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

    private WriteListener writeListener;
    private final AtomicBoolean listenerRunning = new AtomicBoolean();
    private Consumer<Runnable> callbackExecutor;

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
        if (this.writeListener != null) {
            throw new IllegalStateException("A WriteListener has already been set on this stream");
        }
        this.writeListener = writeListener;

        // Vert.x signals drain on the event loop. Re-entering the listener from there while it is
        // still running on the servlet thread would interleave writes, so delivery is serialised
        // through notifyWritePossible().
        response.drainHandler(v -> notifyWritePossible());

        Runnable initial = this::notifyWritePossible;
        if (callbackExecutor != null) {
            callbackExecutor.accept(initial);
        } else {
            initial.run();
        }
    }

    /**
     * Delivers {@code onWritePossible} unless the listener is already running, in which case the
     * spec's rule applies: the container calls back only after {@link #isReady()} has returned
     * false, and the in-progress call will observe the new state itself.
     */
    private void notifyWritePossible() {
        if (writeListener == null || !isReady()) {
            return;
        }
        if (!listenerRunning.compareAndSet(false, true)) {
            return;
        }
        try {
            writeListener.onWritePossible();
        } catch (Throwable t) {
            try {
                writeListener.onError(t);
            } catch (Throwable ignored) {
                // the listener is beyond help; the response will be closed by the container
            }
        } finally {
            listenerRunning.set(false);
        }
    }

    void setCallbackExecutor(Consumer<Runnable> callbackExecutor) {
        this.callbackExecutor = callbackExecutor;
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

    boolean isClosed() {
        return closed;
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
