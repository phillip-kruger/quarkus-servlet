package io.quarkiverse.servlet.runtime;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;

import io.vertx.core.buffer.Buffer;

public class VertxServletInputStream extends ServletInputStream {

    private final byte[] bodyBuffer;
    private int position;
    private boolean finished;

    public VertxServletInputStream(Buffer body) {
        if (body != null && body.length() > 0) {
            this.bodyBuffer = body.getBytes();
        } else {
            this.bodyBuffer = new byte[0];
        }
    }

    @Override
    public int read() {
        if (position >= bodyBuffer.length) {
            finished = true;
            return -1;
        }
        return bodyBuffer[position++] & 0xFF;
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
        return !finished;
    }

    @Override
    public void setReadListener(ReadListener readListener) {
        if (readListener == null) {
            throw new NullPointerException("ReadListener must not be null");
        }
        Thread.startVirtualThread(() -> {
            try {
                if (bodyBuffer.length > 0 && !finished) {
                    readListener.onDataAvailable();
                }
                readListener.onAllDataRead();
            } catch (Throwable t) {
                readListener.onError(t);
            }
        });
    }
}
