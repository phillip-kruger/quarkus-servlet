package io.quarkiverse.undertow.tck;

import org.jboss.arquillian.container.spi.client.container.DeployableContainer;
import org.jboss.arquillian.core.spi.LoadableExtension;

/**
 * Registers the container that runs the TCK against the Undertow servlet engine.
 */
public class UndertowTckExtension implements LoadableExtension {

    @Override
    public void register(ExtensionBuilder builder) {
        builder.service(DeployableContainer.class, UndertowTckDeployableContainer.class);
    }
}
