package io.quarkiverse.servlet.runtime;

import jakarta.servlet.ServletConnection;

import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpVersion;

public class VertxServletConnection implements ServletConnection {

    private final HttpServerRequest request;
    private final String connectionId;

    public VertxServletConnection(HttpServerRequest request) {
        this.request = request;
        this.connectionId = Integer.toHexString(System.identityHashCode(request.connection()));
    }

    @Override
    public String getConnectionId() {
        return connectionId;
    }

    @Override
    public String getProtocol() {
        HttpVersion version = request.version();
        return switch (version) {
            case HTTP_1_0 -> "HTTP/1.0";
            case HTTP_1_1 -> "HTTP/1.1";
            case HTTP_2 -> "HTTP/2";
            default -> version.name();
        };
    }

    @Override
    public String getProtocolConnectionId() {
        long streamId = request.streamId();
        return streamId >= 0 ? String.valueOf(streamId) : "";
    }

    @Override
    public boolean isSecure() {
        return request.isSSL();
    }
}
