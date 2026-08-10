package io.quarkiverse.servlet.deployment;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamReader;

import org.jboss.logging.Logger;
import org.jboss.metadata.javaee.support.MappableMetaData;
import org.jboss.metadata.javaee.support.MappedMetaData;
import org.jboss.metadata.parser.servlet.WebFragmentMetaDataParser;
import org.jboss.metadata.parser.servlet.WebMetaDataParser;
import org.jboss.metadata.parser.util.MetaDataElementParser;
import org.jboss.metadata.property.PropertyReplacers;
import org.jboss.metadata.web.spec.FiltersMetaData;
import org.jboss.metadata.web.spec.ServletsMetaData;
import org.jboss.metadata.web.spec.WebFragmentMetaData;
import org.jboss.metadata.web.spec.WebMetaData;

import io.quarkiverse.servlet.spi.WebMetadataBuildItem;
import io.quarkus.deployment.ApplicationArchive;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.BuildSteps;
import io.quarkus.deployment.builditem.AdditionalApplicationArchiveMarkerBuildItem;
import io.quarkus.deployment.builditem.ApplicationArchivesBuildItem;

@BuildSteps
public class WebXmlParsingBuildStep {

    private static final Logger log = Logger.getLogger(WebXmlParsingBuildStep.class);

    /**
     * Makes a library that ships a {@code web-fragment.xml} an indexed application archive.
     * <p>
     * Without this, an ordinary jar is not one, and its fragment is never seen - the descriptor is
     * only found in archives the application already indexes for other reasons, such as those with
     * a {@code beans.xml}. A library whose whole purpose is to contribute servlets would deploy
     * nothing at all.
     */
    @BuildStep
    AdditionalApplicationArchiveMarkerBuildItem fragmentsMakeApplicationArchives() {
        return new AdditionalApplicationArchiveMarkerBuildItem("META-INF/web-fragment.xml");
    }

    @BuildStep
    void parseWebXml(ApplicationArchivesBuildItem archives,
            BuildProducer<WebMetadataBuildItem> webMetadataProducer) {

        WebMetaData webMetaData = null;

        for (ApplicationArchive archive : archives.getAllApplicationArchives()) {
            Path webXml = findWebXml(archive);
            if (webXml != null && Files.exists(webXml)) {
                try {
                    webMetaData = parse(webXml);
                    log.debugf("Parsed web.xml from %s", webXml);
                    break;
                } catch (Exception e) {
                    log.warnf(e, "Failed to parse web.xml from %s", webXml);
                }
            }
        }

        // Servlet 6.1 chapter 8: a library on the classpath may declare its own servlets, filters
        // and listeners in META-INF/web-fragment.xml, and the container has to treat them as though
        // they had been written in web.xml. An application can consist of nothing but fragments,
        // so this runs whether or not a web.xml was found.
        List<WebFragmentMetaData> fragments = findWebFragments(archives);
        if (!fragments.isEmpty()) {
            if (webMetaData == null) {
                webMetaData = new WebMetaData();
            }
            for (WebFragmentMetaData fragment : fragments) {
                merge(webMetaData, fragment);
            }
        }

        if (webMetaData != null) {
            webMetadataProducer.produce(new WebMetadataBuildItem(webMetaData));
        }
    }

    private List<WebFragmentMetaData> findWebFragments(ApplicationArchivesBuildItem archives) {
        List<WebFragmentMetaData> fragments = new ArrayList<>();
        for (ApplicationArchive archive : archives.getAllApplicationArchives()) {
            for (Path rootDir : archive.getRootDirectories()) {
                Path fragmentXml = rootDir.resolve("META-INF/web-fragment.xml");
                if (!Files.exists(fragmentXml)) {
                    continue;
                }
                try (InputStream is = Files.newInputStream(fragmentXml)) {
                    fragments.add(WebFragmentMetaDataParser.parse(
                            newReader(is), PropertyReplacers.noop()));
                    log.debugf("Parsed web-fragment.xml from %s", fragmentXml);
                } catch (Exception e) {
                    log.warnf(e, "Failed to parse web-fragment.xml from %s", fragmentXml);
                }
            }
        }
        return fragments;
    }

    /**
     * Folds a fragment's declarations into the main descriptor.
     * <p>
     * Absolute and relative fragment ordering is not implemented: fragments are merged in the order
     * the archives are visited. That affects which declaration wins when two fragments configure
     * the same name, but not whether a fragment's servlets are deployed at all.
     */
    private static void merge(WebMetaData target, WebFragmentMetaData fragment) {
        target.setServlets(addNewNames(target.getServlets(), fragment.getServlets(), ServletsMetaData::new));
        target.setFilters(addNewNames(target.getFilters(), fragment.getFilters(), FiltersMetaData::new));
        target.setServletMappings(concat(target.getServletMappings(), fragment.getServletMappings()));
        target.setFilterMappings(concat(target.getFilterMappings(), fragment.getFilterMappings()));
        target.setListeners(concat(target.getListeners(), fragment.getListeners()));
        target.setContextParams(concat(target.getContextParams(), fragment.getContextParams()));
        target.setSecurityConstraints(
                concat(target.getSecurityConstraints(), fragment.getSecurityConstraints()));
        target.setSecurityRoles(concat(target.getSecurityRoles(), fragment.getSecurityRoles()));
        target.setErrorPages(concat(target.getErrorPages(), fragment.getErrorPages()));
        if (target.getLoginConfig() == null) {
            target.setLoginConfig(fragment.getLoginConfig());
        }
        if (target.getSessionConfig() == null) {
            target.setSessionConfig(fragment.getSessionConfig());
        }
        if (target.getWelcomeFileList() == null) {
            target.setWelcomeFileList(fragment.getWelcomeFileList());
        }
    }

    /**
     * Adds only the entries whose name is not already declared. These collections are keyed by
     * name, so a plain {@code addAll} would let a fragment silently replace a servlet or filter
     * that web.xml declared - the opposite of the precedence the spec gives web.xml.
     */
    private static <E extends MappableMetaData, C extends MappedMetaData<E>> C addNewNames(
            C target, C addition, Supplier<C> factory) {
        if (addition == null || addition.isEmpty()) {
            return target;
        }
        if (target == null) {
            target = factory.get();
        }
        for (E entry : addition) {
            if (!target.containsKey(entry.getKey())) {
                target.add(entry);
            }
        }
        return target;
    }

    /**
     * Appends one fragment collection to another. Unlike servlets and filters these are positional
     * rather than keyed - mappings, listeners and constraints all accumulate - so order of arrival
     * is the only precedence there is. The fragment's own collection doubles as the container when
     * the descriptor declared none, since its concrete type is the only one available here.
     */
    private static <E, C extends Collection<E>> C concat(C target, C addition) {
        if (addition == null || addition.isEmpty()) {
            return target;
        }
        if (target == null) {
            return addition;
        }
        target.addAll(addition);
        return target;
    }

    private Path findWebXml(ApplicationArchive archive) {
        for (Path rootDir : archive.getRootDirectories()) {
            Path metaInf = rootDir.resolve("META-INF/web.xml");
            if (Files.exists(metaInf)) {
                return metaInf;
            }
            Path webInf = rootDir.resolve("WEB-INF/web.xml");
            if (Files.exists(webInf)) {
                return webInf;
            }
        }
        return null;
    }

    private WebMetaData parse(Path webXml) throws Exception {
        try (InputStream is = Files.newInputStream(webXml)) {
            return WebMetaDataParser.parse(newReader(is),
                    new MetaDataElementParser.DTDInfo(), PropertyReplacers.noop());
        }
    }

    private static XMLStreamReader newReader(InputStream is) throws Exception {
        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        return factory.createXMLStreamReader(is);
    }
}
