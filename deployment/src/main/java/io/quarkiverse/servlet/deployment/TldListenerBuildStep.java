package io.quarkiverse.servlet.deployment;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Stream;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.jboss.logging.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import io.quarkiverse.servlet.spi.ListenerBuildItem;
import io.quarkus.deployment.ApplicationArchive;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.ApplicationArchivesBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;

/**
 * Registers the listeners a tag library declares.
 * <p>
 * A TLD may carry {@code <listener>} entries (Jakarta Pages 3.1 section 7.3.1), and a container has
 * to honour them whether or not it implements Pages itself - a library ships one so that dropping
 * its jar on the classpath is enough to install its listeners. TLDs are searched for under
 * {@code META-INF} in every application archive and under {@code WEB-INF} in the application.
 * <p>
 * They are registered as restricted. Servlet 6.1 section 4.4 confines a listener the deployment
 * descriptor never declared: it may not add servlets, filters or listeners, and may not reconfigure
 * the context. A TLD is not a deployment descriptor, so its listeners fall in that category even
 * though they are, like declared ones, registered before the application starts.
 */
public class TldListenerBuildStep {

    private static final Logger log = Logger.getLogger(TldListenerBuildStep.class);

    @BuildStep
    void scanTldListeners(ApplicationArchivesBuildItem archives,
            BuildProducer<ListenerBuildItem> listenerProducer,
            BuildProducer<ReflectiveClassBuildItem> reflective) {

        Set<String> classNames = new LinkedHashSet<>();
        for (ApplicationArchive archive : archives.getAllApplicationArchives()) {
            for (Path root : archive.getRootDirectories()) {
                collectFrom(root.resolve("META-INF"), classNames);
                collectFrom(root.resolve("WEB-INF"), classNames);
            }
        }

        for (String className : classNames) {
            listenerProducer.produce(new ListenerBuildItem(className, true));
            // Instantiated reflectively when it is not a CDI bean, exactly as a declared listener is.
            reflective.produce(ReflectiveClassBuildItem.builder(className)
                    .constructors().build());
            log.debugf("Registered listener from TLD: %s", className);
        }
    }

    private static void collectFrom(Path dir, Set<String> into) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.filter(p -> p.getFileName() != null
                    && p.getFileName().toString().endsWith(".tld"))
                    .forEach(tld -> parse(tld, into));
        } catch (Exception e) {
            log.debugf(e, "Could not search for TLDs under %s", dir);
        }
    }

    private static void parse(Path tld, Set<String> into) {
        try (InputStream is = Files.newInputStream(tld)) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // A TLD is application-supplied, so it is parsed with entity resolution disabled and
            // without reaching the network for the schema it names.
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            // Not disallow-doctype-decl: TLDs written against JSP 1.2 carry a DOCTYPE, and
            // rejecting those outright would drop their listeners. Blocking the entities is what
            // actually matters, and with external access denied above nothing is fetched.
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            // Namespace-unaware, so the element names match whether or not the TLD declares the
            // Jakarta namespace - both spellings are in the wild.
            factory.setNamespaceAware(false);
            Document doc = factory.newDocumentBuilder().parse(is);
            NodeList nodes = doc.getElementsByTagName("listener-class");
            for (int i = 0; i < nodes.getLength(); i++) {
                String className = nodes.item(i).getTextContent();
                if (className != null && !className.isBlank()) {
                    into.add(className.trim());
                }
            }
        } catch (Exception e) {
            log.warnf(e, "Failed to parse TLD %s", tld);
        }
    }
}
