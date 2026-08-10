package io.quarkiverse.servlet.tck;

import org.jboss.arquillian.container.spi.client.container.DeployableContainer;
import org.jboss.arquillian.core.spi.LoadableExtension;

public class ServletTckExtension implements LoadableExtension {

    /**
     * Selects the container the TCK runs against.
     * <p>
     * The default boots a real Quarkus application per deployment, so the TCK measures the
     * extension as shipped - build steps, CDI, the Vert.x route and all.
     * <p>
     * {@code -Dservlet.tck.container=direct} instead drives the servlet runtime directly from a
     * harness that re-implements annotation scanning, {@code web.xml} parsing and the context
     * bootstrap in test code. That was the default until it was found to be flattering the
     * implementation: anything the harness does for itself is something the extension is never
     * asked to do, which is how a deployment that could not even start scored 97%. It is kept for
     * isolating runtime-library regressions, and is not the number to quote.
     */
    private static final String CONTAINER = System.getProperty("servlet.tck.container", "quarkus");

    @Override
    public void register(ExtensionBuilder builder) {
        if ("direct".equalsIgnoreCase(CONTAINER)) {
            builder.service(DeployableContainer.class, ServletTckDeployableContainer.class);
        } else {
            builder.service(DeployableContainer.class, QuarkusServletTckContainer.class);
        }
    }
}
