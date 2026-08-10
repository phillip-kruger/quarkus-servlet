package io.quarkiverse.servlet.spi;

import io.quarkus.builder.item.MultiBuildItem;

public final class ServletContextAttributeBuildItem extends MultiBuildItem {

    private final String key;
    private final Object value;

    public ServletContextAttributeBuildItem(String key, Object value) {
        this.key = key;
        this.value = value;
    }

    public String getKey() {
        return key;
    }

    public Object getValue() {
        return value;
    }
}
