package io.quarkiverse.servlet.it;

import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;

/**
 * A listener declared in {@code META-INF/test-listener.tld} rather than in a deployment descriptor.
 * <p>
 * It records both that it ran and how the container treated it. Servlet 6.1 section 4.4 restricts a
 * listener the descriptor never declared, so reconfiguring the context from here has to fail with
 * {@link UnsupportedOperationException} - running the listener but leaving it unrestricted would be
 * just as wrong as never running it, and only checking the two together tells them apart.
 */
public class TldDeclaredListener implements ServletContextListener {

    static final String RAN = "tld.listener.ran";
    static final String RESTRICTED = "tld.listener.restricted";

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        ServletContext context = sce.getServletContext();
        context.setAttribute(RAN, Boolean.TRUE);
        try {
            context.setSessionTimeout(7);
            context.setAttribute(RESTRICTED, Boolean.FALSE);
        } catch (UnsupportedOperationException expected) {
            context.setAttribute(RESTRICTED, Boolean.TRUE);
        }
    }
}
