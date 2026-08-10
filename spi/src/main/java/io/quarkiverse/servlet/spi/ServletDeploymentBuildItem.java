package io.quarkiverse.servlet.spi;

import io.quarkus.builder.item.SimpleBuildItem;
import io.quarkus.runtime.RuntimeValue;

public final class ServletDeploymentBuildItem extends SimpleBuildItem {

    private final RuntimeValue<?> deployment;

    public ServletDeploymentBuildItem(RuntimeValue<?> deployment) {
        this.deployment = deployment;
    }

    @SuppressWarnings("unchecked")
    public <T> RuntimeValue<T> getDeployment() {
        return (RuntimeValue<T>) deployment;
    }
}
