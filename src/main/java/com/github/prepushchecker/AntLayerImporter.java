package com.github.prepushchecker;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates {@code .prepush-layers} text from an Ant {@code build.xml}: one layer per target
 * containing {@code <javac>}, in {@code depends} order. Best effort: anything it cannot resolve
 * ({@code <import>}, unknown {@code ${props}}) becomes a {@code # TODO} line for the user to fix.
 */
final class AntLayerImporter {
    private static final Pattern PROPERTY_REF = Pattern.compile("\\$\\{([^}]+)}");

    record JavacTarget(
        @NotNull String name,
        @NotNull List<String> srcDirs,
        @NotNull List<String> includes,
        @NotNull List<String> excludes
    ) {
    }

    record Result(@NotNull List<JavacTarget> targets, @NotNull List<String> todos) {
    }

    private AntLayerImporter() {
    }

    /**
     * @param buildFile     the build.xml
     * @param repositoryRoot paths in the output are made relative to this
     */
    static @NotNull Result importFile(@NotNull Path buildFile, @NotNull Path repositoryRoot) throws IOException {
        return importXml(Files.readString(buildFile, StandardCharsets.UTF_8),
            buildFile.toAbsolutePath().getParent(), repositoryRoot.toAbsolutePath());
    }

    static @NotNull Result importXml(@NotNull String xml, @NotNull Path baseDir, @NotNull Path repositoryRoot)
            throws IOException {
        Document document = parse(xml);
        Element project = document.getDocumentElement();
        List<String> todos = new ArrayList<>();

        String basedirAttr = project.getAttribute("basedir");
        Path antBase = basedirAttr.isBlank() ? baseDir : baseDir.resolve(basedirAttr).normalize();

        Map<String, String> properties = new HashMap<>();
        properties.put("basedir", antBase.toString());
        Map<String, Element> patternSets = new HashMap<>();
        for (Element child : children(project)) {
            switch (child.getTagName()) {
                case "property" -> readProperty(child, antBase, properties, todos);
                case "patternset" -> {
                    String id = child.getAttribute("id");
                    if (!id.isBlank()) patternSets.put(id, child);
                }
                case "import", "include" -> todos.add("<" + child.getTagName() + " file=\""
                    + child.getAttribute("file") + "\"> not followed; merge its javac targets manually");
                default -> { }
            }
        }

        Map<String, Element> targets = new LinkedHashMap<>();
        for (Element child : children(project)) {
            if (child.getTagName().equals("target")) targets.put(child.getAttribute("name"), child);
        }

        List<JavacTarget> result = new ArrayList<>();
        for (String name : dependencyOrder(targets, todos)) {
            Element target = targets.get(name);
            // Properties declared inside targets are common in Ant scripts; collect them first.
            for (Element property : descendants(target, "property")) {
                readProperty(property, antBase, properties, todos);
            }
            List<Element> javacs = descendants(target, "javac");
            for (int i = 0; i < javacs.size(); i++) {
                String layerName = javacs.size() == 1 ? name : name + "-" + (i + 1);
                result.add(toJavacTarget(layerName, javacs.get(i), antBase, repositoryRoot,
                    properties, patternSets, todos));
            }
        }
        if (result.isEmpty()) todos.add("no <javac> found in any target");
        return new Result(result, todos);
    }

    /** Renders the result as rules-file text. */
    static @NotNull String render(@NotNull Result result) {
        StringBuilder out = new StringBuilder();
        out.append("# Generated from Ant build.xml by Pre-Push Compilation Checker.\n");
        out.append("# Layers are in compile order: a file may import only from its own or earlier layers.\n");
        out.append("# Review before committing; first matching layer wins.\n");
        for (String todo : result.todos()) out.append("# TODO ").append(todo).append('\n');
        out.append('\n');

        Set<String> roots = new java.util.LinkedHashSet<>();
        for (JavacTarget target : result.targets()) roots.addAll(target.srcDirs());
        for (String root : roots) out.append("root ").append(root.isEmpty() ? "." : root).append('\n');

        for (JavacTarget target : result.targets()) {
            out.append('\n').append("layer ").append(target.name()).append('\n');
            for (String srcDir : target.srcDirs()) {
                String prefix = srcDir.isEmpty() ? "" : srcDir + "/";
                if (target.includes().isEmpty()) {
                    out.append("  include ").append(prefix).append("**\n");
                }
                for (String include : target.includes()) {
                    out.append("  include ").append(prefix).append(include).append('\n');
                }
                for (String exclude : target.excludes()) {
                    out.append("  exclude ").append(prefix).append(exclude).append('\n');
                }
            }
        }
        return out.toString();
    }

    private static JavacTarget toJavacTarget(
        String name,
        Element javac,
        Path antBase,
        Path repositoryRoot,
        Map<String, String> properties,
        Map<String, Element> patternSets,
        List<String> todos
    ) {
        List<String> srcDirs = new ArrayList<>();
        List<String> includes = new ArrayList<>();
        List<String> excludes = new ArrayList<>();

        for (String dir : splitPathList(javac.getAttribute("srcdir"))) {
            addSrcDir(dir, name, antBase, repositoryRoot, properties, srcDirs, todos);
        }
        for (Element src : descendants(javac, "src")) {
            for (String dir : splitPathList(src.getAttribute("path"))) {
                addSrcDir(dir, name, antBase, repositoryRoot, properties, srcDirs, todos);
            }
            for (Element pathElement : descendants(src, "pathelement")) {
                String dir = pathElement.getAttribute("location");
                if (dir.isBlank()) dir = pathElement.getAttribute("path");
                addSrcDir(dir, name, antBase, repositoryRoot, properties, srcDirs, todos);
            }
        }
        if (srcDirs.isEmpty()) todos.add("target '" + name + "': javac has no resolvable srcdir");

        collectPatterns(javac, name, properties, patternSets, includes, excludes, todos);
        return new JavacTarget(name, srcDirs, includes, excludes);
    }

    private static void collectPatterns(
        Element element,
        String targetName,
        Map<String, String> properties,
        Map<String, Element> patternSets,
        List<String> includes,
        List<String> excludes,
        List<String> todos
    ) {
        for (String pattern : splitPatterns(element.getAttribute("includes"))) {
            includes.add(expand(pattern, properties, targetName, todos));
        }
        for (String pattern : splitPatterns(element.getAttribute("excludes"))) {
            excludes.add(expand(pattern, properties, targetName, todos));
        }
        for (Element child : children(element)) {
            switch (child.getTagName()) {
                case "include" -> includes.add(expand(child.getAttribute("name"), properties, targetName, todos));
                case "exclude" -> excludes.add(expand(child.getAttribute("name"), properties, targetName, todos));
                case "patternset" -> {
                    String ref = child.getAttribute("refid");
                    Element referenced = ref.isBlank() ? child : patternSets.get(ref);
                    if (referenced == null) {
                        todos.add("target '" + targetName + "': patternset refid '" + ref + "' not found");
                    } else {
                        collectPatterns(referenced, targetName, properties, patternSets, includes, excludes, todos);
                    }
                }
                case "includesfile", "excludesfile" -> todos.add("target '" + targetName + "': <"
                    + child.getTagName() + " name=\"" + child.getAttribute("name") + "\"> not read");
                default -> { }
            }
        }
    }

    private static void addSrcDir(
        String raw,
        String targetName,
        Path antBase,
        Path repositoryRoot,
        Map<String, String> properties,
        List<String> srcDirs,
        List<String> todos
    ) {
        if (raw == null || raw.isBlank()) return;
        String expanded = expand(raw, properties, targetName, todos);
        if (PROPERTY_REF.matcher(expanded).find()) return;
        Path dir = antBase.resolve(expanded).normalize();
        String relative;
        try {
            relative = repositoryRoot.normalize().relativize(dir).toString().replace('\\', '/');
        } catch (IllegalArgumentException e) {
            relative = null;
        }
        if (relative == null || relative.startsWith("..")) {
            todos.add("target '" + targetName + "': srcdir " + dir + " is outside the repository");
            return;
        }
        if (!srcDirs.contains(relative)) srcDirs.add(relative);
    }

    private static void readProperty(Element property, Path antBase, Map<String, String> properties, List<String> todos) {
        String name = property.getAttribute("name");
        if (!name.isBlank()) {
            // Ant properties are immutable: the first definition wins.
            if (properties.containsKey(name)) return;
            if (property.hasAttribute("location")) {
                String value = expand(property.getAttribute("location"), properties, null, todos);
                properties.put(name, antBase.resolve(value).normalize().toString());
            } else if (property.hasAttribute("value")) {
                properties.put(name, expand(property.getAttribute("value"), properties, null, todos));
            }
            return;
        }
        String file = property.getAttribute("file");
        if (file.isBlank()) return;
        Path path = antBase.resolve(expand(file, properties, null, todos)).normalize();
        Properties loaded = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            loaded.load(reader);
        } catch (IOException e) {
            todos.add("property file " + path + " not readable");
            return;
        }
        for (String key : loaded.stringPropertyNames()) {
            properties.putIfAbsent(key, loaded.getProperty(key));
        }
    }

    private static String expand(String value, Map<String, String> properties, @Nullable String target, List<String> todos) {
        String current = value;
        for (int depth = 0; depth < 10; depth++) {
            Matcher m = PROPERTY_REF.matcher(current);
            StringBuilder out = new StringBuilder();
            boolean replaced = false;
            while (m.find()) {
                String resolved = properties.get(m.group(1));
                if (resolved != null) replaced = true;
                m.appendReplacement(out, Matcher.quoteReplacement(resolved != null ? resolved : m.group()));
            }
            m.appendTail(out);
            current = out.toString();
            if (!replaced) break;
        }
        Matcher unresolved = PROPERTY_REF.matcher(current);
        while (unresolved.find() && target != null) {
            String todo = "target '" + target + "': property ${" + unresolved.group(1) + "} unresolved";
            if (!todos.contains(todo)) todos.add(todo);
        }
        return current;
    }

    /** Topological order over {@code depends}; ties keep declaration order. */
    private static List<String> dependencyOrder(Map<String, Element> targets, List<String> todos) {
        List<String> order = new ArrayList<>();
        Set<String> done = new HashSet<>();
        Set<String> visiting = new HashSet<>();
        for (String name : targets.keySet()) visit(name, targets, done, visiting, order, todos);
        return order;
    }

    private static void visit(
        String name, Map<String, Element> targets, Set<String> done, Set<String> visiting,
        List<String> order, List<String> todos
    ) {
        if (done.contains(name)) return;
        Element target = targets.get(name);
        if (target == null) {
            todos.add("depends on unknown target '" + name + "'");
            done.add(name);
            return;
        }
        if (!visiting.add(name)) {
            todos.add("dependency cycle through target '" + name + "'");
            return;
        }
        for (String dependency : target.getAttribute("depends").split(",")) {
            String dep = dependency.trim();
            if (!dep.isEmpty()) visit(dep, targets, done, visiting, order, todos);
        }
        visiting.remove(name);
        done.add(name);
        order.add(name);
    }

    private static List<String> splitPathList(String value) {
        List<String> parts = new ArrayList<>();
        if (value == null || value.isBlank()) return parts;
        for (String part : value.split("[;:](?![\\\\/])")) {
            if (!part.isBlank()) parts.add(part.trim());
        }
        return parts;
    }

    private static List<String> splitPatterns(String value) {
        List<String> parts = new ArrayList<>();
        if (value == null || value.isBlank()) return parts;
        for (String part : value.split("[,\\s]+")) {
            if (!part.isBlank()) parts.add(part.trim());
        }
        return parts;
    }

    private static List<Element> children(Element parent) {
        List<Element> result = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element element) result.add(element);
        }
        return result;
    }

    private static List<Element> descendants(Element parent, String tag) {
        List<Element> result = new ArrayList<>();
        NodeList nodes = parent.getElementsByTagName(tag);
        for (int i = 0; i < nodes.getLength(); i++) result.add((Element) nodes.item(i));
        return result;
    }

    private static Document parse(String xml) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            // Old Ant scripts use DOCTYPE entities, so allow DOCTYPE but never load external ones.
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IOException("build.xml could not be parsed: " + e.getMessage(), e);
        }
    }
}
