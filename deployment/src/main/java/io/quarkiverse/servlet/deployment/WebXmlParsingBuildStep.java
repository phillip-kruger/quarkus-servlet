package io.quarkiverse.servlet.deployment;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamReader;

import org.jboss.logging.Logger;
import org.jboss.metadata.parser.servlet.WebMetaDataParser;
import org.jboss.metadata.parser.util.MetaDataElementParser;
import org.jboss.metadata.property.PropertyReplacers;
import org.jboss.metadata.web.spec.WebMetaData;

import io.quarkiverse.servlet.spi.WebMetadataBuildItem;
import io.quarkus.deployment.ApplicationArchive;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.BuildSteps;
import io.quarkus.deployment.builditem.ApplicationArchivesBuildItem;

@BuildSteps
public class WebXmlParsingBuildStep {

    private static final Logger log = Logger.getLogger(WebXmlParsingBuildStep.class);

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

        if (webMetaData != null) {
            webMetadataProducer.produce(new WebMetadataBuildItem(webMetaData));
        }
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
            XMLInputFactory factory = XMLInputFactory.newInstance();
            factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
            XMLStreamReader reader = factory.createXMLStreamReader(is);

            return WebMetaDataParser.parse(reader,
                    new MetaDataElementParser.DTDInfo(), PropertyReplacers.noop());
        }
    }
}
