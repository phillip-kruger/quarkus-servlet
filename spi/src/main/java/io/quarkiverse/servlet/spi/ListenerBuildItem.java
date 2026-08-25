package io.quarkiverse.servlet.spi;

import io.quarkus.builder.item.MultiBuildItem;

public final class ListenerBuildItem extends MultiBuildItem {

    private final String listenerClass;
    private final boolean restricted;

    public ListenerBuildItem(String listenerClass) {
        this(listenerClass, false);
    }

    /**
     * @param restricted whether the listener is subject to the restrictions Servlet 6.1 section 4.4
     *        places on a listener the deployment descriptor never declared - it may not add
     *        servlets, filters or listeners, and may not change the context's configuration.
     *        Listeners declared in a TLD are in this category; those from {@code web.xml},
     *        {@code web-fragment.xml} or {@code @WebListener} are not.
     */
    public ListenerBuildItem(String listenerClass, boolean restricted) {
        this.listenerClass = listenerClass;
        this.restricted = restricted;
    }

    public String getListenerClass() {
        return listenerClass;
    }

    public boolean isRestricted() {
        return restricted;
    }
}
