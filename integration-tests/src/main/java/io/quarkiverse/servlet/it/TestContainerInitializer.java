package io.quarkiverse.servlet.it;

import java.util.Set;

import jakarta.servlet.ServletContainerInitializer;
import jakarta.servlet.ServletContext;
import jakarta.servlet.annotation.HandlesTypes;

/**
 * Discovered through {@code META-INF/services}. {@code @HandlesTypes} is resolved from the Jandex
 * index at build time, so the set handed over here proves the build step matched implementors
 * rather than handing over nothing.
 */
@HandlesTypes(HandledMarker.class)
public class TestContainerInitializer implements ServletContainerInitializer {

    static final String RAN = "io.quarkiverse.servlet.it.sciRan";
    static final String HANDLED = "io.quarkiverse.servlet.it.sciHandled";

    @Override
    public void onStartup(Set<Class<?>> handled, ServletContext context) {
        context.setAttribute(RAN, Boolean.TRUE);
        context.setAttribute(HANDLED, handled == null ? 0 : handled.size());
    }
}
