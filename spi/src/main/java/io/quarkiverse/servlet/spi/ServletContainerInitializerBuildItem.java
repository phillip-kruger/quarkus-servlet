package io.quarkiverse.servlet.spi;

import java.util.Set;

import io.quarkus.builder.item.MultiBuildItem;

/**
 * A {@code jakarta.servlet.ServletContainerInitializer} to run when the servlet context starts.
 * <p>
 * {@code handledTypes} holds the classes that matched the initializer's {@code @HandlesTypes}
 * declaration, resolved from the Jandex index at build time. Frameworks that ship an initializer
 * but drive their own bootstrap under Quarkus can suppress it by not producing this item.
 */
public final class ServletContainerInitializerBuildItem extends MultiBuildItem {

    private final String initializerClass;
    private final Set<String> handledTypes;

    public ServletContainerInitializerBuildItem(String initializerClass, Set<String> handledTypes) {
        this.initializerClass = initializerClass;
        this.handledTypes = handledTypes;
    }

    public String getInitializerClass() {
        return initializerClass;
    }

    public Set<String> getHandledTypes() {
        return handledTypes;
    }
}
