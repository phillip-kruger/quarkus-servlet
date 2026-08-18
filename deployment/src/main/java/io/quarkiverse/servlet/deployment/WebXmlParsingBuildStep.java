package io.quarkiverse.servlet.deployment;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Supplier;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamReader;

import org.jboss.logging.Logger;
import org.jboss.metadata.javaee.spec.ParamValueMetaData;
import org.jboss.metadata.javaee.support.MappableMetaData;
import org.jboss.metadata.javaee.support.MappedMetaData;
import org.jboss.metadata.parser.servlet.WebFragmentMetaDataParser;
import org.jboss.metadata.parser.servlet.WebMetaDataParser;
import org.jboss.metadata.parser.util.MetaDataElementParser;
import org.jboss.metadata.property.PropertyReplacers;
import org.jboss.metadata.web.spec.AbsoluteOrderingMetaData;
import org.jboss.metadata.web.spec.FilterMappingMetaData;
import org.jboss.metadata.web.spec.FiltersMetaData;
import org.jboss.metadata.web.spec.OrderingElementMetaData;
import org.jboss.metadata.web.spec.OrderingMetaData;
import org.jboss.metadata.web.spec.RelativeOrderingMetaData;
import org.jboss.metadata.web.spec.ServletMappingMetaData;
import org.jboss.metadata.web.spec.ServletMetaData;
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

    /**
     * A web-fragment together with the archive it came from, so a fragment excluded by
     * {@code <absolute-ordering>} can be traced back to its jar and kept out of annotation scanning.
     */
    private record FragmentArchive(WebFragmentMetaData fragment, ApplicationArchive archive) {
    }

    @BuildStep
    void parseWebXml(ApplicationArchivesBuildItem archives,
            BuildProducer<WebMetadataBuildItem> webMetadataProducer,
            BuildProducer<ExcludedFragmentClassesBuildItem> excludedProducer) {

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

        Set<String> excludedClasses = new HashSet<>();

        // Servlet 6.1 chapter 8: a library on the classpath may declare its own servlets, filters
        // and listeners in META-INF/web-fragment.xml, and the container has to treat them as though
        // they had been written in web.xml. An application can consist of nothing but fragments,
        // so this runs whether or not a web.xml was found.
        List<FragmentArchive> fragmentArchives = findWebFragments(archives);
        if (!fragmentArchives.isEmpty()) {
            if (webMetaData == null) {
                webMetaData = new WebMetaData();
            }
            List<WebFragmentMetaData> fragments = new ArrayList<>();
            for (FragmentArchive fa : fragmentArchives) {
                fragments.add(fa.fragment());
            }
            // web.xml wins over fragments for a given name: once web.xml maps a servlet or filter,
            // any mapping a fragment declares for that same name is discarded (Servlet 6.1 chapter 8),
            // so this is snapshotted before merging - not read back from the growing merged model.
            Set<String> webXmlMappedServlets = mappedNames(webMetaData.getServletMappings(),
                    ServletMappingMetaData::getServletName);
            Set<String> webXmlMappedFilters = mappedNames(webMetaData.getFilterMappings(),
                    FilterMappingMetaData::getFilterName);
            // <absolute-ordering> in web.xml (Servlet 6.1 section 8.2.2) overrides any relative
            // <ordering> the fragments declare, and may exclude some of them outright.
            AbsoluteOrderingMetaData absoluteOrdering = webMetaData.getAbsoluteOrdering();
            List<WebFragmentMetaData> ordered = absoluteOrdering != null
                    ? absoluteOrder(fragments, absoluteOrdering)
                    : orderFragments(fragments);
            for (WebFragmentMetaData fragment : ordered) {
                merge(webMetaData, fragment, webXmlMappedServlets, webXmlMappedFilters);
            }

            // A fragment left out of the ordering contributes nothing - not through its descriptor
            // (it was never merged) and not through annotations either, so collect its jar's classes
            // for the scanner to skip.
            Set<WebFragmentMetaData> included = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            included.addAll(ordered);
            for (FragmentArchive fa : fragmentArchives) {
                if (!included.contains(fa.fragment())) {
                    collectClassNames(fa.archive(), excludedClasses);
                }
            }
        }

        if (webMetaData != null) {
            webMetadataProducer.produce(new WebMetadataBuildItem(webMetaData));
        }
        excludedProducer.produce(new ExcludedFragmentClassesBuildItem(excludedClasses));
    }

    private static void collectClassNames(ApplicationArchive archive, Set<String> into) {
        for (var classInfo : archive.getIndex().getKnownClasses()) {
            into.add(classInfo.name().toString());
        }
    }

    private List<FragmentArchive> findWebFragments(ApplicationArchivesBuildItem archives) {
        List<FragmentArchive> fragments = new ArrayList<>();
        for (ApplicationArchive archive : archives.getAllApplicationArchives()) {
            for (Path rootDir : archive.getRootDirectories()) {
                Path fragmentXml = rootDir.resolve("META-INF/web-fragment.xml");
                if (!Files.exists(fragmentXml)) {
                    continue;
                }
                try (InputStream is = Files.newInputStream(fragmentXml)) {
                    fragments.add(new FragmentArchive(WebFragmentMetaDataParser.parse(
                            newReader(is), PropertyReplacers.noop()), archive));
                    log.debugf("Parsed web-fragment.xml from %s", fragmentXml);
                } catch (Exception e) {
                    log.warnf(e, "Failed to parse web-fragment.xml from %s", fragmentXml);
                }
            }
        }
        return fragments;
    }

    /**
     * Sorts fragments by their relative {@code <ordering>} (Servlet 6.1 section 8.2.2). Fragments
     * asking to come {@code <before><others/>} lead, those asking for {@code <after><others/>} trail,
     * and the rest keep their discovery order in the middle. Named {@code <before>}/{@code <after>}
     * constraints are then honoured on top with a stable topological sort. Absolute ordering
     * ({@code <absolute-ordering>} in web.xml) is handled separately by {@link #absoluteOrder}.
     */
    private static List<WebFragmentMetaData> orderFragments(List<WebFragmentMetaData> fragments) {
        if (fragments.size() < 2 || fragments.stream().allMatch(f -> f.getOrdering() == null)) {
            return fragments;
        }
        List<WebFragmentMetaData> beforeOthers = new ArrayList<>();
        List<WebFragmentMetaData> middle = new ArrayList<>();
        List<WebFragmentMetaData> afterOthers = new ArrayList<>();
        for (WebFragmentMetaData fragment : fragments) {
            OrderingMetaData ordering = fragment.getOrdering();
            if (ordering != null && refersToOthers(ordering.getBefore())) {
                beforeOthers.add(fragment);
            } else if (ordering != null && refersToOthers(ordering.getAfter())) {
                afterOthers.add(fragment);
            } else {
                middle.add(fragment);
            }
        }
        List<WebFragmentMetaData> combined = new ArrayList<>(fragments.size());
        combined.addAll(beforeOthers);
        combined.addAll(middle);
        combined.addAll(afterOthers);
        return applyNamedOrdering(combined);
    }

    /**
     * Reorders fragments so that every named {@code <before>}/{@code <after>} constraint is satisfied,
     * breaking ties by the incoming position so the {@code <others/>} grouping is preserved. Falls
     * back to the incoming order if the constraints form a cycle.
     */
    private static List<WebFragmentMetaData> applyNamedOrdering(List<WebFragmentMetaData> fragments) {
        int n = fragments.size();
        Map<String, Integer> nameToIndex = new HashMap<>();
        for (int i = 0; i < n; i++) {
            String name = fragments.get(i).getName();
            if (name != null) {
                nameToIndex.putIfAbsent(name, i);
            }
        }
        List<Set<Integer>> successors = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            successors.add(new HashSet<>());
        }
        int[] inDegree = new int[n];
        for (int i = 0; i < n; i++) {
            OrderingMetaData ordering = fragments.get(i).getOrdering();
            if (ordering == null) {
                continue;
            }
            for (String name : namedReferences(ordering.getBefore())) {
                Integer j = nameToIndex.get(name);
                if (j != null && successors.get(i).add(j)) {
                    inDegree[j]++;
                }
            }
            for (String name : namedReferences(ordering.getAfter())) {
                Integer j = nameToIndex.get(name);
                if (j != null && successors.get(j).add(i)) {
                    inDegree[i]++;
                }
            }
        }
        PriorityQueue<Integer> ready = new PriorityQueue<>();
        for (int i = 0; i < n; i++) {
            if (inDegree[i] == 0) {
                ready.add(i);
            }
        }
        List<WebFragmentMetaData> result = new ArrayList<>(n);
        while (!ready.isEmpty()) {
            int u = ready.poll();
            result.add(fragments.get(u));
            for (int v : successors.get(u)) {
                if (--inDegree[v] == 0) {
                    ready.add(v);
                }
            }
        }
        return result.size() == n ? result : fragments;
    }

    /**
     * Applies {@code <absolute-ordering>} from web.xml (Servlet 6.1 section 8.2.2). Only fragments
     * named in the element are kept, in the order named; a name matching no fragment is ignored. A
     * {@code <others/>} entry stands for every fragment not named explicitly (including nameless
     * ones), in discovery order, at that position; without {@code <others/>} those fragments are
     * excluded entirely.
     */
    private static List<WebFragmentMetaData> absoluteOrder(List<WebFragmentMetaData> fragments,
            AbsoluteOrderingMetaData absoluteOrdering) {
        List<OrderingElementMetaData> elements = absoluteOrdering.getOrdering();
        if (elements == null || elements.isEmpty()) {
            // An empty <absolute-ordering/> excludes every fragment.
            return List.of();
        }

        Map<String, WebFragmentMetaData> byName = new HashMap<>();
        Set<String> explicitlyNamed = new HashSet<>();
        for (OrderingElementMetaData element : elements) {
            if (element != null && !element.isOthers() && element.getName() != null) {
                explicitlyNamed.add(element.getName());
            }
        }
        for (WebFragmentMetaData fragment : fragments) {
            String name = fragment.getName();
            if (name != null && !name.isEmpty()) {
                byName.putIfAbsent(name, fragment);
            }
        }
        // The <others/> group: every fragment whose name is not called out explicitly, kept in
        // discovery order.
        List<WebFragmentMetaData> others = new ArrayList<>();
        for (WebFragmentMetaData fragment : fragments) {
            String name = fragment.getName();
            if (name == null || name.isEmpty() || !explicitlyNamed.contains(name)) {
                others.add(fragment);
            }
        }

        List<WebFragmentMetaData> result = new ArrayList<>();
        Set<String> added = new HashSet<>();
        for (OrderingElementMetaData element : elements) {
            if (element == null) {
                continue;
            }
            if (element.isOthers()) {
                result.addAll(others);
            } else {
                WebFragmentMetaData fragment = byName.get(element.getName());
                if (fragment != null && added.add(element.getName())) {
                    result.add(fragment);
                }
            }
        }
        return result;
    }

    private static boolean refersToOthers(RelativeOrderingMetaData ordering) {
        if (ordering == null || ordering.getOrdering() == null) {
            return false;
        }
        return ordering.getOrdering().stream().anyMatch(e -> e != null && e.isOthers());
    }

    private static List<String> namedReferences(RelativeOrderingMetaData ordering) {
        if (ordering == null || ordering.getOrdering() == null) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        ordering.getOrdering().forEach(e -> {
            if (e != null && !e.isOthers() && e.getName() != null) {
                names.add(e.getName());
            }
        });
        return names;
    }

    /**
     * Folds a fragment's declarations into the main descriptor. Fragments arrive already sorted by
     * {@link #orderFragments} or {@link #absoluteOrder}, so appending here reflects their ordering.
     */
    private static void merge(WebMetaData target, WebFragmentMetaData fragment,
            Set<String> webXmlMappedServlets, Set<String> webXmlMappedFilters) {
        target.setServlets(mergeServlets(target.getServlets(), fragment.getServlets()));
        target.setFilters(addNewNames(target.getFilters(), fragment.getFilters(), FiltersMetaData::new));
        target.setServletMappings(concatMappings(target.getServletMappings(), fragment.getServletMappings(),
                m -> !webXmlMappedServlets.contains(m.getServletName())));
        target.setFilterMappings(concatMappings(target.getFilterMappings(), fragment.getFilterMappings(),
                m -> !webXmlMappedFilters.contains(m.getFilterName())));
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
     * Adds a fragment's servlets to the target. A servlet name that web.xml (or an earlier fragment)
     * already declared keeps its class and configuration, but init-params the fragment introduces are
     * folded in: the existing params are kept ahead of the fragment's, so that when
     * {@link ServletProcessor} collapses the list keeping the first value seen for each name, web.xml
     * wins every conflict and the fragment only contributes params web.xml never set (Servlet 6.1
     * section 8.2.3).
     */
    private static ServletsMetaData mergeServlets(ServletsMetaData target, ServletsMetaData addition) {
        if (addition == null || addition.isEmpty()) {
            return target;
        }
        if (target == null) {
            target = new ServletsMetaData();
        }
        for (ServletMetaData servlet : addition) {
            ServletMetaData existing = target.containsKey(servlet.getKey()) ? target.get(servlet.getKey()) : null;
            if (existing != null) {
                mergeInitParams(existing, servlet.getInitParam());
            } else {
                target.add(servlet);
            }
        }
        return target;
    }

    /**
     * Appends a fragment servlet's init-params after those already collected, leaving conflict
     * resolution (first value wins) to the point of consumption.
     */
    private static void mergeInitParams(ServletMetaData existing, List<ParamValueMetaData> extra) {
        if (extra == null || extra.isEmpty()) {
            return;
        }
        List<ParamValueMetaData> merged = existing.getInitParam();
        if (merged == null) {
            merged = new ArrayList<>();
            existing.setInitParam(merged);
        }
        merged.addAll(extra);
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

    /**
     * Collects the keying name (servlet-name or filter-name) of every mapping web.xml declared, so a
     * fragment mapping for one of those names can be recognised and dropped.
     */
    private static <E> Set<String> mappedNames(List<E> mappings, java.util.function.Function<E, String> nameOf) {
        Set<String> names = new HashSet<>();
        if (mappings != null) {
            for (E mapping : mappings) {
                names.add(nameOf.apply(mapping));
            }
        }
        return names;
    }

    /**
     * Appends a fragment's mappings, keeping only those {@code keep} accepts. Like {@link #concat} the
     * mappings are positional and accumulate, but a mapping whose servlet or filter web.xml already
     * mapped is skipped - web.xml owns the complete mapping set for the names it declares.
     */
    private static <E> List<E> concatMappings(List<E> target, List<E> addition,
            java.util.function.Predicate<E> keep) {
        if (addition == null || addition.isEmpty()) {
            return target;
        }
        List<E> result = target != null ? target : new ArrayList<>();
        for (E entry : addition) {
            if (keep.test(entry)) {
                result.add(entry);
            }
        }
        return result;
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
