package io.quarkiverse.servlet.runtime;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;

import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.WebConnection;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetSocket;

public class VertxWebConnection implements WebConnection {

    private final NetSocket socket;
    private final UpgradeServletInputStream inputStream;
    private final UpgradeServletOutputStream outputStream;

    public VertxWebConnection(NetSocket socket) throws IOException {
        this.socket = socket;

        PipedOutputStream pipeOut = new PipedOutputStream();
        PipedInputStream pipeIn = new PipedInputStream(pipeOut, 8192);

        socket.handler(buf -> {
            try {
                pipeOut.write(buf.getBytes());
                pipeOut.flush();
            } catch (IOException e) {
                socket.close();
            }
        });
        socket.closeHandler(v -> {
            try {
                pipeOut.close();
            } catch (IOException ignored) {
            }
        });

        this.inputStream = new UpgradeServletInputStream(pipeIn);
        this.outputStream = new UpgradeServletOutputStream(socket);
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

    private static class UpgradeServletInputStream extends ServletInputStream {
        private final PipedInputStream delegate;

        UpgradeServletInputStream(PipedInputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public int read() throws IOException {
            return delegate.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return delegate.read(b, off, len);
        }

        @Override
        public int available() throws IOException {
            return delegate.available();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }

        @Override
        public boolean isFinished() {
            try {
                return delegate.available() == 0;
            } catch (IOException e) {
                return true;
            }
        }

        @Override
        public boolean isReady() {
            try {
                return delegate.available() > 0;
            } catch (IOException e) {
                return false;
            }
        }

        @Override
        public void setReadListener(jakarta.servlet.ReadListener readListener) {
            throw new UnsupportedOperationException("Non-blocking I/O not supported on upgrade connections");
        }
    }

    private static class UpgradeServletOutputStream extends ServletOutputStream {
        private final NetSocket socket;

        UpgradeServletOutputStream(NetSocket socket) {
            this.socket = socket;
        }

        @Override
        public void write(int b) throws IOException {
            socket.write(Buffer.buffer(new byte[] { (byte) b }));
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            byte[] data = new byte[len];
            System.arraycopy(b, off, data, 0, len);
            socket.write(Buffer.buffer(data));
        }

        @Override
        public void flush() {
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
            throw new UnsupportedOperationException("Non-blocking I/O not supported on upgrade connections");
        }
    }
}
