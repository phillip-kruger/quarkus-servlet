package io.quarkiverse.servlet.spi;

import io.quarkus.builder.item.MultiBuildItem;

public final class ServletInitParamBuildItem extends MultiBuildItem {

    private final String key;
    private final String value;

    public ServletInitParamBuildItem(String key, String value) {
        this.key = key;
        this.value = value;
    }

    public String getKey() {
        return key;
    }

    public String getValue() {
        return value;
    }
}
