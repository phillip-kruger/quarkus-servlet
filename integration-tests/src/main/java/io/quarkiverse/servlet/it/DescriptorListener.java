package io.quarkiverse.servlet.it;

import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;

/**
 * Declared in {@code web.xml}, and the reason this test exists: descriptor-declared listeners were
 * once never registered at all, so nothing they set up - including the servlets they add
 * programmatically - ever reached the deployment.
 */
public class DescriptorListener implements ServletContextListener {

    static final String RAN = "io.quarkiverse.servlet.it.listenerRan";

    @Override
    public void contextInitialized(ServletContextEvent event) {
        ServletContext context = event.getServletContext();
        context.setAttribute(RAN, Boolean.TRUE);

        // Programmatic registration is only legal during initialization; if the container never
        // opens that window this throws IllegalStateException and the deployment fails.
        context.addServlet("programmatic", ProgrammaticServlet.class)
                .addMapping("/programmatic");
    }
}
