package io.quarkiverse.servlet.runtime;

import java.util.function.Consumer;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;

import io.vertx.core.buffer.Buffer;

/**
 * Servlet input stream over the request body, which the container has already buffered in full.
 * <p>
 * Because every byte is present before the servlet runs, {@link #isReady()} is true until the
 * stream is exhausted and reads never block. The {@link ReadListener} contract is still honoured
 * exactly: {@code onDataAvailable} is delivered while the listener keeps consuming, and
 * {@code onAllDataRead} fires only once the application has actually read everything.
 */
public class VertxServletInputStream extends ServletInputStream {

    private final byte[] bodyBuffer;
    private int position;
    private boolean finished;

    private ReadListener readListener;
    private boolean allDataReadFired;

    /**
     * Runs listener callbacks on the request's own thread. Supplied by the container; when absent
     * (a plain blocking read) the listener path is not used.
     */
    private Consumer<Runnable> callbackExecutor;

    public VertxServletInputStream(Buffer body) {
        if (body != null && body.length() > 0) {
            this.bodyBuffer = body.getBytes();
        } else {
            this.bodyBuffer = new byte[0];
            // An empty body is complete from the outset.
            this.finished = true;
        }
    }

    void setCallbackExecutor(Consumer<Runnable> callbackExecutor) {
        this.callbackExecutor = callbackExecutor;
    }

    @Override
    public int read() {
        if (position >= bodyBuffer.length) {
            finished = true;
            return -1;
        }
        int value = bodyBuffer[position++] & 0xFF;
        if (position >= bodyBuffer.length) {
            finished = true;
        }
        return value;
    }

    @Override
    public int read(byte[] b, int off, int len) {
        if (position >= bodyBuffer.length) {
            finished = true;
            return -1;
        }
        int available = bodyBuffer.length - position;
        int toRead = Math.min(len, available);
        System.arraycopy(bodyBuffer, position, b, off, toRead);
        position += toRead;
        if (position >= bodyBuffer.length) {
            finished = true;
        }
        return toRead;
    }

    @Override
    public int available() {
        return bodyBuffer.length - position;
    }

    @Override
    public boolean isFinished() {
        return finished;
    }

    @Override
    public boolean isReady() {
        // Data is already in memory, so reading can always proceed until the stream is exhausted.
        return !finished;
    }

    @Override
    public void setReadListener(ReadListener readListener) {
        if (readListener == null) {
            throw new NullPointerException("ReadListener must not be null");
        }
        if (this.readListener != null) {
            throw new IllegalStateException("A ReadListener has already been set on this stream");
        }
        this.readListener = readListener;

        Runnable pump = this::pump;
        if (callbackExecutor != null) {
            callbackExecutor.accept(pump);
        } else {
            pump.run();
        }
    }

    /**
     * Drives the listener. Keeps delivering {@code onDataAvailable} for as long as the listener
     * makes progress, then reports completion. If a listener returns without consuming anything the
     * loop stops rather than spinning - no more data can arrive to unblock it.
     */
    private void pump() {
        try {
            while (!finished) {
                int before = position;
                readListener.onDataAvailable();
                if (position == before) {
                    // The listener declined to consume; nothing further will change that.
                    return;
                }
            }
            fireAllDataRead();
        } catch (Throwable t) {
            readListener.onError(t);
        }
    }

    private void fireAllDataRead() throws Exception {
        if (allDataReadFired) {
            return;
        }
        allDataReadFired = true;
        readListener.onAllDataRead();
    }
}
