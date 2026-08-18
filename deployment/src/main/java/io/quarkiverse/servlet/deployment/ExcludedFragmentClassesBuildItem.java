package io.quarkiverse.servlet.deployment;

import java.util.Set;

import io.quarkus.builder.item.SimpleBuildItem;

/**
 * The fully qualified names of the classes that live in web-fragment jars excluded by an
 * {@code <absolute-ordering>} in web.xml (Servlet 6.1 section 8.2.2). An excluded fragment
 * contributes nothing, so its classes must not be picked up by annotation scanning either -
 * a {@code @WebServlet} or {@code @WebListener} in such a jar would otherwise sneak back in.
 */
public final class ExcludedFragmentClassesBuildItem extends SimpleBuildItem {

    private final Set<String> classNames;

    public ExcludedFragmentClassesBuildItem(Set<String> classNames) {
        this.classNames = classNames;
    }

    public Set<String> getClassNames() {
        return classNames;
    }
}
