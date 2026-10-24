package com.github.auties00.smalt.metadata;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.xml.XmlReader;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Holds an XMP packet: the RDF/XML metadata Adobe's Extensible Metadata Platform (ISO 16684-1)
 * embeds in JPEG, PNG, WebP, HEIF, TIFF, GIF, PDF and MP4 files.
 *
 * <p>The packet is an ordered list of top-level {@link XmpProperty properties}, each named by a
 * namespace URI and a local name and holding a {@link XmpValue}: text, an array or a structure.
 * {@link #parse(byte[])} accepts every RDF form XMP writers use (properties as attributes or
 * elements, {@code rdf:parseType="Resource"}, nested {@code rdf:Description}, {@code rdf:resource}
 * URIs, several {@code rdf:Description} elements) and keeps the prefixes the packet declared.
 * {@link #toXml()} writes the canonical form: one {@code rdf:Description} inside
 * {@code x:xmpmeta}, wrapped in an {@code xpacket}.
 *
 * <p>Qualifiers other than {@code xml:lang} are not represented and are dropped when a packet is
 * rewritten. Instances are immutable and thread-safe.
 *
 * @apiNote Images carry their packet through
 * {@code com.github.auties00.smalt.image.metadata.ImageMetadata} in the {@code smalt-image}
 * artifact.
 */
public final class XmpPacket {
    /**
     * The Dublin Core namespace: {@code dc:title}, {@code dc:creator}, {@code dc:description},
     * {@code dc:rights}, {@code dc:subject}.
     */
    public static final String DC = "http://purl.org/dc/elements/1.1/";

    /**
     * The XMP basic namespace: {@code xmp:CreateDate}, {@code xmp:ModifyDate},
     * {@code xmp:CreatorTool}, {@code xmp:Rating}.
     */
    public static final String XMP = "http://ns.adobe.com/xap/1.0/";

    /**
     * The XMP rights management namespace.
     */
    public static final String XMP_RIGHTS = "http://ns.adobe.com/xap/1.0/rights/";

    /**
     * The XMP media management namespace: document and instance identifiers, history.
     */
    public static final String XMP_MM = "http://ns.adobe.com/xap/1.0/mm/";

    /**
     * The XMP note namespace, which holds {@code xmpNote:HasExtendedXMP}.
     */
    public static final String XMP_NOTE = "http://ns.adobe.com/xmp/note/";

    /**
     * The EXIF namespace: {@code exif:GPSLatitude}, {@code exif:DateTimeOriginal}, ...
     */
    public static final String EXIF = "http://ns.adobe.com/exif/1.0/";

    /**
     * The EXIF 2.3 namespace of the CIPA DC-010 mapping.
     */
    public static final String EXIF_EX = "http://cipa.jp/exif/1.0/";

    /**
     * The TIFF namespace: {@code tiff:Orientation}, {@code tiff:Make}, {@code tiff:Model}.
     */
    public static final String TIFF = "http://ns.adobe.com/tiff/1.0/";

    /**
     * The Photoshop namespace: {@code photoshop:City}, {@code photoshop:Country}, ...
     */
    public static final String PHOTOSHOP = "http://ns.adobe.com/photoshop/1.0/";

    /**
     * The IPTC Core namespace.
     */
    public static final String IPTC_CORE = "http://iptc.org/std/Iptc4xmpCore/1.0/xmlns/";

    /**
     * The IPTC Extension namespace: {@code Iptc4xmpExt:LocationCreated}, ...
     */
    public static final String IPTC_EXTENSION = "http://iptc.org/std/Iptc4xmpExt/2008-02-29/";

    /**
     * The language of the default item of a language alternative.
     */
    public static final String DEFAULT_LANGUAGE = "x-default";

    /**
     * The RDF namespace.
     */
    static final String RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";

    /**
     * The namespace of the {@code x:xmpmeta} wrapper.
     */
    static final String X = "adobe:ns:meta/";

    /**
     * The prefixes of the well-known namespaces.
     */
    private static final Map<String, String> KNOWN_PREFIXES = Map.ofEntries(
            Map.entry(DC, "dc"), Map.entry(XMP, "xmp"), Map.entry(XMP_RIGHTS, "xmpRights"),
            Map.entry(XMP_MM, "xmpMM"), Map.entry(XMP_NOTE, "xmpNote"), Map.entry(EXIF, "exif"),
            Map.entry(EXIF_EX, "exifEX"), Map.entry(TIFF, "tiff"), Map.entry(PHOTOSHOP, "photoshop"),
            Map.entry(IPTC_CORE, "Iptc4xmpCore"), Map.entry(IPTC_EXTENSION, "Iptc4xmpExt"),
            Map.entry("http://ns.adobe.com/camera-raw-settings/1.0/", "crs"),
            Map.entry("http://ns.adobe.com/xap/1.0/sType/ResourceRef#", "stRef"),
            Map.entry("http://ns.adobe.com/xap/1.0/sType/ResourceEvent#", "stEvt"),
            Map.entry("http://ns.adobe.com/xmp/1.0/DynamicMedia/", "xmpDM"),
            Map.entry("http://ns.google.com/photos/1.0/camera/", "GCamera"),
            Map.entry("http://ns.adobe.com/hdr-gain-map/1.0/", "hdrgm"));

    /**
     * The Dublin Core properties that are language alternatives.
     */
    private static final List<String> DC_ALTERNATIVES = List.of("title", "description", "rights");

    /**
     * The Dublin Core properties that are ordered arrays.
     */
    private static final List<String> DC_ORDERED = List.of("creator", "date");

    /**
     * The Dublin Core properties that are unordered arrays.
     */
    private static final List<String> DC_UNORDERED = List.of("subject", "contributor", "publisher", "language", "type",
            "relation");

    /**
     * The deepest structure nesting decoded.
     */
    private static final int MAX_DEPTH = 32;

    /**
     * A packet without properties.
     */
    private static final XmpPacket EMPTY = new XmpPacket(List.of(), Map.of());

    /**
     * The top-level properties, in order.
     */
    private final List<XmpProperty> properties;

    /**
     * The prefixes the packet declared, keyed by namespace.
     */
    private final Map<String, String> prefixes;

    /**
     * Creates a packet.
     *
     * @param properties the properties in document order
     * @param prefixes the declared prefixes keyed by namespace
     */
    private XmpPacket(List<XmpProperty> properties, Map<String, String> prefixes) {
        this.properties = List.copyOf(properties);
        this.prefixes = Collections.unmodifiableMap(new LinkedHashMap<>(prefixes));
    }

    /**
     * Returns a packet without properties.
     *
     * @return the empty packet
     */
    public static XmpPacket empty() {
        return EMPTY;
    }

    /**
     * Parses a serialized packet.
     *
     * <p>The bytes are UTF-8, or UTF-16 or UTF-32 when they start with the matching byte order
     * mark. The {@code xpacket} processing instructions and the {@code x:xmpmeta} wrapper are
     * optional; trailing padding is ignored. Control characters XML 1.0 cannot hold (C0 controls
     * other than tab, line feed and carriage return) are dropped from text and attribute values,
     * as {@link #toXml()} drops them, so a parsed packet is the one its serialization parses to.
     *
     * @param xml the serialized packet
     * @return the packet
     * @throws NullPointerException     if {@code xml} is {@code null}
     * @throws MediaException.Malformed if the bytes are not well-formed XML, hold a character
     *                                  reference to a character XML cannot hold, or hold no
     *                                  {@code rdf:RDF} element
     */
    public static XmpPacket parse(byte[] xml) {
        Objects.requireNonNull(xml, "xml");
        return parse(decode(xml));
    }

    /**
     * Parses a serialized packet.
     *
     * <p>The text is read as {@link #parse(byte[])} reads decoded bytes.
     *
     * @param xml the serialized packet
     * @return the packet
     * @throws NullPointerException     if {@code xml} is {@code null}
     * @throws MediaException.Malformed if the text is not well-formed XML, holds a character
     *                                  reference to a character XML cannot hold, or holds no
     *                                  {@code rdf:RDF} element
     */
    public static XmpPacket parse(String xml) {
        Objects.requireNonNull(xml, "xml");
        var trimmed = trimPadding(xml);
        XmlReader.Element root;
        try {
            root = XmlReader.parse(trimmed);
        } catch (StackOverflowError error) {
            throw new MediaException.Malformed("XMP packet nests too deeply");
        }
        var rdf = findRdf(root, 0);
        if (rdf == null) {
            throw new MediaException.Malformed("XMP packet without an rdf:RDF element");
        }
        var properties = new ArrayList<XmpProperty>();
        var prefixes = new LinkedHashMap<String, String>();
        collectPrefixes(root, prefixes, 0);
        for (var description : rdf.elements()) {
            if (!description.is(RDF, "Description")) {
                continue;
            }
            for (var property : fields(description, 0)) {
                if (indexOf(properties, property.namespace(), property.name()) < 0) {
                    properties.add(property);
                }
            }
        }
        return new XmpPacket(properties, prefixes);
    }

    /**
     * Decodes the bytes of a packet.
     *
     * @param xml the bytes
     * @return the text
     */
    private static String decode(byte[] xml) {
        if (xml.length >= 4 && xml[0] == 0 && xml[1] == 0 && (xml[2] & 0xFF) == 0xFE && (xml[3] & 0xFF) == 0xFF) {
            return new String(xml, 4, xml.length - 4, java.nio.charset.Charset.forName("UTF-32BE"));
        }
        if (xml.length >= 4 && (xml[0] & 0xFF) == 0xFF && (xml[1] & 0xFF) == 0xFE && xml[2] == 0 && xml[3] == 0) {
            return new String(xml, 4, xml.length - 4, java.nio.charset.Charset.forName("UTF-32LE"));
        }
        if (xml.length >= 2 && (xml[0] & 0xFF) == 0xFE && (xml[1] & 0xFF) == 0xFF) {
            return new String(xml, 2, xml.length - 2, StandardCharsets.UTF_16BE);
        }
        if (xml.length >= 2 && (xml[0] & 0xFF) == 0xFF && (xml[1] & 0xFF) == 0xFE) {
            return new String(xml, 2, xml.length - 2, StandardCharsets.UTF_16LE);
        }
        return new String(xml, StandardCharsets.UTF_8);
    }

    /**
     * Removes the NUL and whitespace padding that follows a packet.
     *
     * @param xml the text
     * @return the text up to the last {@code >}
     */
    private static String trimPadding(String xml) {
        var end = xml.lastIndexOf('>');
        return end < 0 ? xml : xml.substring(0, end + 1);
    }

    /**
     * Finds the {@code rdf:RDF} element.
     *
     * @param element the element to search
     * @param depth   the search depth
     * @return the element, or {@code null}
     */
    private static XmlReader.Element findRdf(XmlReader.Element element, int depth) {
        if (element.is(RDF, "RDF")) {
            return element;
        }
        if (depth > 4) {
            return null;
        }
        for (var child : element.elements()) {
            var found = findRdf(child, depth + 1);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * Collects the prefixes declared anywhere in the tree.
     *
     * @param element the element whose subtree is scanned
     * @param prefixes receives the prefixes keyed by namespace, first declaration winning
     * @param depth the nesting depth
     */
    private static void collectPrefixes(XmlReader.Element element, Map<String, String> prefixes, int depth) {
        for (var entry : element.declared().entrySet()) {
            if (!entry.getKey().isEmpty() && XmpProperty.isName(entry.getKey())) {
                prefixes.putIfAbsent(entry.getValue(), entry.getKey());
            }
        }
        if (depth < MAX_DEPTH) {
            for (var child : element.elements()) {
                collectPrefixes(child, prefixes, depth + 1);
            }
        }
    }

    /**
     * Reads the properties of a node element ({@code rdf:Description} or a structure).
     *
     * @param node  the node element
     * @param depth the structure depth
     * @return the properties
     */
    private static List<XmpProperty> fields(XmlReader.Element node, int depth) {
        var result = new ArrayList<XmpProperty>();
        for (var attribute : node.attributes()) {
            if (isSyntax(attribute.namespace()) || attribute.namespace().isEmpty() || !XmpProperty.isName(attribute.name())) {
                continue;
            }
            result.add(new XmpProperty(attribute.namespace(), attribute.name(), XmpValue.text(attribute.value())));
        }
        for (var child : node.elements()) {
            if (isSyntax(child.namespace()) || child.namespace().isEmpty() || !XmpProperty.isName(child.name())) {
                continue;
            }
            var value = value(child, depth);
            if (value != null) {
                result.add(new XmpProperty(child.namespace(), child.name(), value));
            }
        }
        return result;
    }

    /**
     * Returns whether a namespace belongs to the RDF syntax rather than to a property.
     *
     * @param namespace a namespace URI
     * @return {@code true} for RDF, XML and the {@code x:} wrapper
     */
    private static boolean isSyntax(String namespace) {
        return namespace.equals(RDF) || namespace.equals(XmlReader.XML_NAMESPACE) || namespace.equals(X);
    }

    /**
     * Reads the value of a property element or array item.
     *
     * @param element the property element or array item
     * @param depth the structure depth
     * @return the value, or {@code null} when nesting is too deep
     */
    private static XmpValue value(XmlReader.Element element, int depth) {
        if (depth > MAX_DEPTH) {
            return null;
        }
        var resource = element.attribute(RDF, "resource");
        if (resource != null) {
            return XmpValue.text(resource);
        }
        if ("Resource".equals(element.attribute(RDF, "parseType"))) {
            var value = element.elements().stream().filter(child -> child.is(RDF, "value")).findFirst();
            if (value.isPresent()) {
                return value(value.get(), depth + 1);
            }
            return new XmpValue.Struct(fields(element, depth + 1));
        }
        var children = element.elements();
        if (children.size() == 1) {
            var child = children.getFirst();
            var kind = child.namespace().equals(RDF) ? switch (child.name()) {
                case "Seq" -> XmpValue.Array.Kind.ORDERED;
                case "Bag" -> XmpValue.Array.Kind.UNORDERED;
                case "Alt" -> XmpValue.Array.Kind.ALTERNATIVE;
                default -> null;
            } : null;
            if (kind != null) {
                var items = new ArrayList<XmpValue>();
                for (var item : child.elements()) {
                    if (item.is(RDF, "li")) {
                        var value = value(item, depth + 1);
                        if (value != null) {
                            items.add(value);
                        }
                    }
                }
                return new XmpValue.Array(kind, items);
            }
            if (child.is(RDF, "Description")) {
                var value = child.elements().stream().filter(field -> field.is(RDF, "value")).findFirst();
                if (value.isPresent()) {
                    return value(value.get(), depth + 1);
                }
                return new XmpValue.Struct(fields(child, depth + 1));
            }
        }
        if (!children.isEmpty()) {
            return new XmpValue.Struct(fields(element, depth + 1));
        }
        var structAttributes = false;
        for (var attribute : element.attributes()) {
            if (!isSyntax(attribute.namespace()) && !attribute.namespace().isEmpty()) {
                structAttributes = true;
                break;
            }
        }
        if (structAttributes) {
            return new XmpValue.Struct(fields(element, depth + 1));
        }
        var language = element.attribute(XmlReader.XML_NAMESPACE, "lang");
        return new XmpValue.Text(element.text(), language);
    }

    /**
     * Returns the index of a property in a list.
     *
     * @param list the properties to search
     * @param namespace the namespace URI of the property
     * @param name the local name
     * @return the index, or {@code -1}
     */
    private static int indexOf(List<XmpProperty> list, String namespace, String name) {
        for (var i = 0; i < list.size(); i++) {
            if (list.get(i).namespace().equals(namespace) && list.get(i).name().equals(name)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Returns the top-level properties in order.
     *
     * @return the properties; unmodifiable
     */
    public List<XmpProperty> properties() {
        return properties;
    }

    /**
     * Returns whether the packet has no property.
     *
     * @return {@code true} when empty
     */
    public boolean isEmpty() {
        return properties.isEmpty();
    }

    /**
     * Returns the prefixes the parsed packet declared.
     *
     * @return the prefixes keyed by namespace; unmodifiable
     */
    public Map<String, String> prefixes() {
        return prefixes;
    }

    /**
     * Returns the value of a property.
     *
     * @param namespace the namespace URI of the property
     * @param name the local name
     * @return the value, or empty when absent
     * @throws NullPointerException if an argument is {@code null}
     */
    public Optional<XmpValue> get(String namespace, String name) {
        var index = indexOf(properties, Objects.requireNonNull(namespace, "namespace"), Objects.requireNonNull(name, "name"));
        return index < 0 ? Optional.empty() : Optional.of(properties.get(index).value());
    }

    /**
     * Returns the text of a property, as {@link XmpValue#asText()} defines it.
     *
     * @param namespace the namespace URI of the property
     * @param name the local name
     * @return the text, or empty when absent or a structure
     * @throws NullPointerException if an argument is {@code null}
     */
    public Optional<String> text(String namespace, String name) {
        return get(namespace, name).flatMap(XmpValue::asText);
    }

    /**
     * Returns the text items of a property: the items of an array, or the single text value.
     *
     * @param namespace the namespace URI of the property
     * @param name the local name
     * @return the texts, empty when absent; unmodifiable
     * @throws NullPointerException if an argument is {@code null}
     */
    public List<String> texts(String namespace, String name) {
        var value = get(namespace, name);
        if (value.isEmpty()) {
            return List.of();
        }
        if (value.get() instanceof XmpValue.Array array) {
            var result = new ArrayList<String>();
            for (var item : array.items()) {
                item.asText().ifPresent(result::add);
            }
            return List.copyOf(result);
        }
        return value.get().asText().map(List::of).orElse(List.of());
    }

    /**
     * Returns a copy of this packet with a property set, replacing its old value in place or
     * adding it last.
     *
     * @param namespace the namespace URI of the property
     * @param name the local name
     * @param value the new value
     * @return the new packet
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if the namespace is empty or the name is not an XML name
     */
    public XmpPacket with(String namespace, String name, XmpValue value) {
        var property = new XmpProperty(namespace, name, value);
        var list = new ArrayList<>(properties);
        var index = indexOf(list, namespace, name);
        if (index >= 0) {
            list.set(index, property);
        } else {
            list.add(property);
        }
        return new XmpPacket(list, prefixes);
    }

    /**
     * Returns a copy of this packet with a property set to a text.
     *
     * <p>The text keeps the form the property already has or must have: it becomes the single
     * item of an existing array (the {@code x-default} item of a language alternative), and of
     * the arrays the Dublin Core schema defines ({@code dc:title}, {@code dc:creator},
     * {@code dc:subject}, ...); otherwise it is a simple value.
     *
     * @param namespace the namespace URI of the property
     * @param name the local name
     * @param text the new text
     * @return the new packet
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if the namespace is empty or the name is not an XML name
     */
    public XmpPacket with(String namespace, String name, String text) {
        Objects.requireNonNull(text, "text");
        var existing = get(namespace, name);
        XmpValue.Array.Kind kind = null;
        if (existing.isPresent() && existing.get() instanceof XmpValue.Array array) {
            kind = array.kind();
        } else if (existing.isEmpty() && DC.equals(namespace)) {
            kind = DC_ALTERNATIVES.contains(name) ? XmpValue.Array.Kind.ALTERNATIVE
                    : DC_ORDERED.contains(name) ? XmpValue.Array.Kind.ORDERED
                    : DC_UNORDERED.contains(name) ? XmpValue.Array.Kind.UNORDERED : null;
        }
        if (kind == null) {
            return with(namespace, name, XmpValue.text(text));
        }
        if (kind == XmpValue.Array.Kind.ALTERNATIVE) {
            return with(namespace, name, XmpValue.alternative(text));
        }
        return with(namespace, name, new XmpValue.Array(kind, List.of(XmpValue.text(text))));
    }

    /**
     * Returns a copy of this packet without a property.
     *
     * @param namespace the namespace URI of the property
     * @param name the local name
     * @return the new packet, or this packet when the property is absent
     * @throws NullPointerException if an argument is {@code null}
     */
    public XmpPacket without(String namespace, String name) {
        return withoutIf(property -> property.namespace().equals(namespace) && property.name().equals(name));
    }

    /**
     * Returns a copy of this packet without the properties of a namespace.
     *
     * @param namespace the namespace URI whose properties are removed
     * @return the new packet
     * @throws NullPointerException if {@code namespace} is {@code null}
     */
    public XmpPacket withoutNamespace(String namespace) {
        Objects.requireNonNull(namespace, "namespace");
        return withoutIf(property -> property.namespace().equals(namespace));
    }

    /**
     * Returns a copy of this packet without the top-level properties a predicate selects.
     *
     * @param filter selects the properties to remove
     * @return the new packet, or this packet when nothing is removed
     * @throws NullPointerException if {@code filter} is {@code null}
     */
    public XmpPacket withoutIf(Predicate<XmpProperty> filter) {
        Objects.requireNonNull(filter, "filter");
        var list = new ArrayList<XmpProperty>();
        for (var property : properties) {
            if (!filter.test(property)) {
                list.add(property);
            }
        }
        return list.size() == properties.size() ? this : new XmpPacket(list, prefixes);
    }

    /**
     * Serializes the packet in canonical form, wrapped in {@code xpacket} processing
     * instructions marked writable.
     *
     * @return the XML text
     */
    public String toXml() {
        return toXml(0);
    }

    /**
     * Serializes the packet with whitespace padding before the closing processing instruction,
     * which in-place editors use to grow the packet without moving the file.
     *
     * @param padding the number of padding characters, recommended 2048
     * @return the XML text
     * @throws IllegalArgumentException if {@code padding} is negative
     */
    public String toXml(int padding) {
        if (padding < 0) {
            throw new IllegalArgumentException("negative padding " + padding);
        }
        var names = assignPrefixes();
        var out = new StringBuilder(256 + properties.size() * 64 + padding);
        out.append("<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n");
        out.append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">\n");
        out.append(" <rdf:RDF xmlns:rdf=\"").append(RDF).append("\">\n");
        out.append("  <rdf:Description rdf:about=\"\"");
        for (var entry : names.entrySet()) {
            out.append("\n    xmlns:").append(entry.getValue()).append("=\"").append(XmlReader.escape(entry.getKey()))
                    .append('"');
        }
        out.append(">\n");
        for (var property : properties) {
            writeProperty(out, names, property, 3);
        }
        out.append("  </rdf:Description>\n");
        out.append(" </rdf:RDF>\n");
        out.append("</x:xmpmeta>\n");
        for (var i = 0; i < padding; i++) {
            out.append(i % 100 == 99 ? '\n' : ' ');
        }
        out.append("<?xpacket end=\"w\"?>");
        return out.toString();
    }

    /**
     * Serializes the packet in UTF-8.
     *
     * @return the bytes
     */
    public byte[] toBytes() {
        return toXml().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Chooses a prefix for every namespace the properties use, keeping the parsed prefixes and
     * the well-known ones.
     *
     * @return the prefixes keyed by namespace, in first-use order
     */
    private Map<String, String> assignPrefixes() {
        var result = new LinkedHashMap<String, String>();
        var used = new java.util.HashSet<String>(List.of("x", "rdf", "xml"));
        for (var property : properties) {
            assign(property, result, used, 0);
        }
        return result;
    }

    /**
     * Assigns prefixes to the namespaces of a property and its fields.
     *
     * @param property the property to assign prefixes to
     * @param result the prefixes keyed by namespace
     * @param used the prefixes taken
     * @param depth the structure depth
     */
    private void assign(XmpProperty property, Map<String, String> result, java.util.Set<String> used, int depth) {
        if (!result.containsKey(property.namespace())) {
            var prefix = prefixes.getOrDefault(property.namespace(), KNOWN_PREFIXES.get(property.namespace()));
            if (prefix == null || used.contains(prefix)) {
                var counter = 1;
                do {
                    prefix = "ns" + counter++;
                } while (used.contains(prefix));
            }
            used.add(prefix);
            result.put(property.namespace(), prefix);
        }
        if (depth < MAX_DEPTH) {
            visit(property.value(), result, used, depth + 1);
        }
    }

    /**
     * Assigns prefixes to the fields nested in a value.
     *
     * @param value the value whose fields are visited
     * @param result the prefixes keyed by namespace
     * @param used the prefixes taken
     * @param depth the structure depth
     */
    private void visit(XmpValue value, Map<String, String> result, java.util.Set<String> used, int depth) {
        switch (value) {
            case XmpValue.Struct struct -> {
                for (var field : struct.fields()) {
                    assign(field, result, used, depth);
                }
            }
            case XmpValue.Array array -> {
                for (var item : array.items()) {
                    visit(item, result, used, depth);
                }
            }
            case XmpValue.Text _ -> {
            }
        }
    }

    /**
     * Writes one property element.
     *
     * @param out the destination
     * @param names the prefixes keyed by namespace
     * @param property the property to write
     * @param indent the indentation depth
     */
    private static void writeProperty(StringBuilder out, Map<String, String> names, XmpProperty property, int indent) {
        var tag = names.get(property.namespace()) + ":" + property.name();
        out.append(" ".repeat(indent)).append('<').append(tag);
        writeValueBody(out, names, property.value(), tag, indent);
    }

    /**
     * Writes the attributes, content and end tag of an element holding a value.
     *
     * @param out the destination
     * @param names the prefixes keyed by namespace
     * @param value the value to write
     * @param tag the qualified name of the element
     * @param indent the indentation depth
     */
    private static void writeValueBody(StringBuilder out, Map<String, String> names, XmpValue value, String tag, int indent) {
        switch (value) {
            case XmpValue.Text text -> {
                text.language().ifPresent(language -> out.append(" xml:lang=\"").append(XmlReader.escape(language)).append('"'));
                out.append('>').append(XmlReader.escape(text.value())).append("</").append(tag).append(">\n");
            }
            case XmpValue.Array array -> {
                var container = switch (array.kind()) {
                    case ORDERED -> "rdf:Seq";
                    case UNORDERED -> "rdf:Bag";
                    case ALTERNATIVE -> "rdf:Alt";
                };
                out.append(">\n").append(" ".repeat(indent + 1)).append('<').append(container).append(">\n");
                for (var item : array.items()) {
                    out.append(" ".repeat(indent + 2)).append("<rdf:li");
                    writeValueBody(out, names, item, "rdf:li", indent + 2);
                }
                out.append(" ".repeat(indent + 1)).append("</").append(container).append(">\n");
                out.append(" ".repeat(indent)).append("</").append(tag).append(">\n");
            }
            case XmpValue.Struct struct -> {
                out.append(" rdf:parseType=\"Resource\">\n");
                for (var field : struct.fields()) {
                    writeProperty(out, names, field, indent + 1);
                }
                out.append(" ".repeat(indent)).append("</").append(tag).append(">\n");
            }
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof XmpPacket that && properties.equals(that.properties);
    }

    @Override
    public int hashCode() {
        return properties.hashCode();
    }

    @Override
    public String toString() {
        return "XmpPacket" + properties;
    }
}
