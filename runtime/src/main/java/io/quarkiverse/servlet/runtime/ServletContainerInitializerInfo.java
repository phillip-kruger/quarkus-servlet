package io.quarkiverse.servlet.runtime;

import java.util.Set;

/**
 * A {@link jakarta.servlet.ServletContainerInitializer} to run when the context starts, together
 * with the classes its {@link jakarta.servlet.annotation.HandlesTypes} declaration matched.
 * <p>
 * Both are held as names rather than {@link Class} objects: this is built during augmentation, when
 * the deployment classloader is in play, but the initializer has to run against the runtime
 * classloader. Resolving the names at boot keeps the two apart.
 */
public class ServletContainerInitializerInfo {

    private final String className;
    private final Set<String> handledTypeNames;

    public ServletContainerInitializerInfo(String className, Set<String> handledTypeNames) {
        this.className = className;
        this.handledTypeNames = handledTypeNames;
    }

    public String getClassName() {
        return className;
    }

    public Set<String> getHandledTypeNames() {
        return handledTypeNames;
    }
}
