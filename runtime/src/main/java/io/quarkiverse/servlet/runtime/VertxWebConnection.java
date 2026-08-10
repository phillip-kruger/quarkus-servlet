package io.quarkiverse.servlet.runtime;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.WebConnection;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetSocket;

/**
 * The {@link WebConnection} handed to an {@code HttpUpgradeHandler} after a protocol upgrade.
 * <p>
 * Bytes arriving from the socket are queued rather than pushed through a {@link java.io.PipedInputStream}:
 * a pipe blocks its writer once full, and that writer is the Vert.x event loop, so a slow handler
 * would stall the whole server. Reads apply backpressure by pausing the socket instead, and the
 * handler thread - never the event loop - is the only thing that ever waits.
 */
public class VertxWebConnection implements WebConnection {

    private final NetSocket socket;
    private final UpgradeInputStream inputStream;
    private final UpgradeOutputStream outputStream;

    public VertxWebConnection(NetSocket socket) throws IOException {
        this.socket = socket;
        this.inputStream = new UpgradeInputStream(socket);
        this.outputStream = new UpgradeOutputStream(socket);

        socket.handler(inputStream::enqueue);
        socket.endHandler(v -> inputStream.signalEof());
        socket.closeHandler(v -> inputStream.signalEof());
        socket.exceptionHandler(inputStream::signalError);
    }

    @Override
    public ServletInputStream getInputStream() {
        return inputStream;
    }

    @Override
    public ServletOutputStream getOutputStream() {
        return outputStream;
    }

    @Override
    public void close() throws Exception {
        socket.close();
    }

    /**
     * Queue-backed stream over the upgraded socket. Blocking reads park the calling thread on a
     * condition; the event loop only ever enqueues and signals.
     */
    private static final class UpgradeInputStream extends ServletInputStream {

        /** Pause the socket once this many unread bytes are queued. */
        private static final int HIGH_WATER_MARK = 64 * 1024;

        private final NetSocket socket;
        private final ReentrantLock lock = new ReentrantLock();
        private final Condition dataAvailable = lock.newCondition();
        private final Deque<Buffer> queue = new ArrayDeque<>();

        private int queuedBytes;
        private int offsetInHead;
        private boolean eof;
        private boolean paused;
        private volatile Throwable failure;

        private ReadListener readListener;

        UpgradeInputStream(NetSocket socket) {
            this.socket = socket;
        }

        void enqueue(Buffer buffer) {
            if (buffer == null || buffer.length() == 0) {
                return;
            }
            ReadListener listener;
            lock.lock();
            try {
                queue.addLast(buffer);
                queuedBytes += buffer.length();
                if (queuedBytes >= HIGH_WATER_MARK && !paused) {
                    paused = true;
                    socket.pause();
                }
                dataAvailable.signalAll();
                listener = readListener;
            } finally {
                lock.unlock();
            }
            if (listener != null) {
                notifyDataAvailable(listener);
            }
        }

        void signalEof() {
            ReadListener listener;
            lock.lock();
            try {
                eof = true;
                dataAvailable.signalAll();
                listener = readListener;
            } finally {
                lock.unlock();
            }
            if (listener != null) {
                try {
                    listener.onAllDataRead();
                } catch (Exception e) {
                    listener.onError(e);
                }
            }
        }

        void signalError(Throwable t) {
            ReadListener listener;
            lock.lock();
            try {
                failure = t;
                eof = true;
                dataAvailable.signalAll();
                listener = readListener;
            } finally {
                lock.unlock();
            }
            if (listener != null) {
                listener.onError(t);
            }
        }

        private void notifyDataAvailable(ReadListener listener) {
            try {
                listener.onDataAvailable();
            } catch (Exception e) {
                listener.onError(e);
            }
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int read = read(one, 0, 1);
            return read == -1 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) {
                return 0;
            }
            lock.lock();
            try {
                while (queue.isEmpty() && !eof) {
                    try {
                        dataAvailable.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted while reading upgraded connection", e);
                    }
                }
                throwIfFailed();
                if (queue.isEmpty()) {
                    return -1;
                }

                int copied = 0;
                while (copied < len && !queue.isEmpty()) {
                    Buffer head = queue.peekFirst();
                    int remaining = head.length() - offsetInHead;
                    int toCopy = Math.min(remaining, len - copied);
                    head.getBytes(offsetInHead, offsetInHead + toCopy, b, off + copied);
                    copied += toCopy;
                    offsetInHead += toCopy;
                    queuedBytes -= toCopy;
                    if (offsetInHead == head.length()) {
                        queue.removeFirst();
                        offsetInHead = 0;
                    }
                }
                if (paused && queuedBytes < HIGH_WATER_MARK) {
                    paused = false;
                    socket.resume();
                }
                return copied;
            } finally {
                lock.unlock();
            }
        }

        @Override
        public int available() {
            lock.lock();
            try {
                return queuedBytes;
            } finally {
                lock.unlock();
            }
        }

        @Override
        public void close() {
            socket.close();
        }

        @Override
        public boolean isFinished() {
            lock.lock();
            try {
                return eof && queue.isEmpty();
            } finally {
                lock.unlock();
            }
        }

        @Override
        public boolean isReady() {
            lock.lock();
            try {
                return !queue.isEmpty();
            } finally {
                lock.unlock();
            }
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            if (readListener == null) {
                throw new NullPointerException("ReadListener must not be null");
            }
            boolean hasData;
            boolean atEof;
            lock.lock();
            try {
                if (this.readListener != null) {
                    throw new IllegalStateException("A ReadListener has already been set");
                }
                this.readListener = readListener;
                hasData = !queue.isEmpty();
                atEof = eof;
            } finally {
                lock.unlock();
            }
            if (hasData) {
                notifyDataAvailable(readListener);
            }
            if (atEof) {
                try {
                    readListener.onAllDataRead();
                } catch (Exception e) {
                    readListener.onError(e);
                }
            }
        }

        private void throwIfFailed() throws IOException {
            Throwable t = failure;
            if (t != null) {
                throw new IOException("Upgraded connection failed", t);
            }
        }
    }

    /**
     * Writes to the upgraded socket, honouring Vert.x backpressure so a slow peer cannot make the
     * write queue grow without bound.
     */
    private static final class UpgradeOutputStream extends ServletOutputStream {

        private final NetSocket socket;
        private WriteListener writeListener;

        UpgradeOutputStream(NetSocket socket) {
            this.socket = socket;
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[] { (byte) b }, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (len == 0) {
                return;
            }
            byte[] data = new byte[len];
            System.arraycopy(b, off, data, 0, len);
            socket.write(Buffer.buffer(data));
        }

        @Override
        public void flush() {
            // Vert.x writes are already dispatched; there is no local buffer to push.
        }

        @Override
        public void close() {
            socket.close();
        }

        @Override
        public boolean isReady() {
            return !socket.writeQueueFull();
        }

        @Override
        public void setWriteListener(WriteListener writeListener) {
            if (writeListener == null) {
                throw new NullPointerException("WriteListener must not be null");
            }
            if (this.writeListener != null) {
                throw new IllegalStateException("A WriteListener has already been set");
            }
            this.writeListener = writeListener;
            socket.drainHandler(v -> fireWritePossible());
            fireWritePossible();
        }

        private void fireWritePossible() {
            if (writeListener == null || socket.writeQueueFull()) {
                return;
            }
            try {
                writeListener.onWritePossible();
            } catch (Throwable t) {
                try {
                    writeListener.onError(t);
                } catch (Throwable ignored) {
                    // nothing further can be done for this connection
                }
            }
        }
    }
}
