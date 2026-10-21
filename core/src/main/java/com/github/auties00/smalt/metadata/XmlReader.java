package com.github.auties00.smalt.metadata;

import com.github.auties00.smalt.MediaException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses the XML subset XMP packets use into a namespace-resolved element tree.
 *
 * <p>Elements, attributes, character data, CDATA sections, comments and processing instructions
 * are supported, with namespace declarations, the five predefined entities and numeric character
 * references. Document type declarations are rejected, so no external entity is ever resolved
 * and no entity can expand; nesting is bounded, so hostile input raises
 * {@link MediaException.Malformed} only. Control characters XML 1.0 cannot hold (C0 controls
 * other than tab, line feed and carriage return) are dropped from character data and attribute
 * values, as {@link #escape} drops them, so that a parsed value survives being written back.
 */
final class XmlReader {
    /**
     * The namespace of the {@code xml:} prefix.
     */
    static final String XML_NAMESPACE = "http://www.w3.org/XML/1998/namespace";

    /**
     * The deepest element nesting accepted.
     */
    private static final int MAX_DEPTH = 64;

    /**
     * The largest number of elements accepted.
     */
    private static final int MAX_ELEMENTS = 1 << 20;

    /**
     * The text being parsed.
     */
    private final String text;

    /**
     * The index of the next character.
     */
    private int position;

    /**
     * The number of elements read.
     */
    private int elements;

    /**
     * Constructs a reader.
     *
     * @param text the document
     */
    private XmlReader(String text) {
        this.text = text;
    }

    /**
     * Parses a document and returns its root element.
     *
     * @param text the document
     * @return the root element
     * @throws MediaException.Malformed if the document is not well formed, declares a document type, or nests too
     * deeply
     */
    static Element parse(String text) {
        var reader = new XmlReader(text);
        reader.skipMisc();
        if (reader.position >= text.length() || text.charAt(reader.position) != '<') {
            throw new MediaException.Malformed("XML document without a root element");
        }
        var namespaces = new HashMap<String, String>();
        namespaces.put("xml", XML_NAMESPACE);
        return reader.element(namespaces, 0);
    }

    /**
     * Skips whitespace, comments and processing instructions, rejecting a document type
     * declaration.
     */
    private void skipMisc() {
        while (position < text.length()) {
            var c = text.charAt(position);
            if (Character.isWhitespace(c) || c == '\uFEFF') {
                position++;
            } else if (text.startsWith("<?", position)) {
                skipPast("?>");
            } else if (text.startsWith("<!--", position)) {
                skipPast("-->");
            } else if (text.startsWith("<!", position)) {
                throw new MediaException.Malformed("XML document type declarations are not accepted");
            } else {
                return;
            }
        }
    }

    /**
     * Moves past the next occurrence of a delimiter.
     *
     * @param delimiter the text that ends the skipped part
     * @throws MediaException.Malformed if it does not occur
     */
    private void skipPast(String delimiter) {
        var end = text.indexOf(delimiter, position);
        if (end < 0) {
            throw new MediaException.Malformed("unterminated XML construct, expected " + delimiter);
        }
        position = end + delimiter.length();
    }

    /**
     * Parses one element starting at {@code <}.
     *
     * @param inherited the namespace bindings in scope, keyed by prefix ({@code ""} for the default namespace)
     * @param depth the nesting depth
     * @return the element
     */
    private Element element(Map<String, String> inherited, int depth) {
        if (depth > MAX_DEPTH) {
            throw new MediaException.Malformed("XML nesting deeper than " + MAX_DEPTH + " elements");
        }
        if (++elements > MAX_ELEMENTS) {
            throw new MediaException.Malformed("more than " + MAX_ELEMENTS + " XML elements");
        }
        position++;
        var qualified = name();
        var rawAttributes = new LinkedHashMap<String, String>();
        while (true) {
            skipWhitespace();
            if (position >= text.length()) {
                throw new MediaException.Malformed("unterminated XML start tag <" + qualified);
            }
            var c = text.charAt(position);
            if (c == '/' || c == '>') {
                break;
            }
            var attribute = name();
            skipWhitespace();
            expect('=');
            skipWhitespace();
            if (position >= text.length()) {
                throw new MediaException.Malformed("unterminated XML attribute " + attribute);
            }
            var quote = text.charAt(position);
            if (quote != '"' && quote != '\'') {
                throw new MediaException.Malformed("unquoted XML attribute " + attribute);
            }
            position++;
            var end = text.indexOf(quote, position);
            if (end < 0) {
                throw new MediaException.Malformed("unterminated XML attribute " + attribute);
            }
            var value = unescape(text.substring(position, end));
            position = end + 1;
            if (rawAttributes.putIfAbsent(attribute, value) != null) {
                throw new MediaException.Malformed("duplicate XML attribute " + attribute);
            }
        }
        var namespaces = inherited;
        for (var entry : rawAttributes.entrySet()) {
            var key = entry.getKey();
            if (key.equals("xmlns") || key.startsWith("xmlns:")) {
                if (namespaces == inherited) {
                    namespaces = new HashMap<>(inherited);
                }
                namespaces.put(key.equals("xmlns") ? "" : key.substring(6), entry.getValue());
            }
        }
        var attributes = new ArrayList<Attribute>();
        for (var entry : rawAttributes.entrySet()) {
            var key = entry.getKey();
            if (key.equals("xmlns") || key.startsWith("xmlns:")) {
                continue;
            }
            var colon = key.indexOf(':');
            var namespace = colon < 0 ? "" : resolve(namespaces, key.substring(0, colon), key);
            attributes.add(new Attribute(namespace, colon < 0 ? key : key.substring(colon + 1),
                    colon < 0 ? "" : key.substring(0, colon), entry.getValue()));
        }
        var colon = qualified.indexOf(':');
        var prefix = colon < 0 ? "" : qualified.substring(0, colon);
        var namespace = colon < 0 ? namespaces.getOrDefault("", "") : resolve(namespaces, prefix, qualified);
        var element = new Element(namespace, colon < 0 ? qualified : qualified.substring(colon + 1), prefix, attributes,
                new ArrayList<>(), Map.copyOf(declared(rawAttributes)));
        if (text.charAt(position) == '/') {
            position++;
            expect('>');
            return element;
        }
        position++;
        var content = new StringBuilder();
        while (true) {
            if (position >= text.length()) {
                throw new MediaException.Malformed("unterminated XML element <" + qualified + ">");
            }
            if (text.startsWith("</", position)) {
                position += 2;
                var closing = name();
                if (!closing.equals(qualified)) {
                    throw new MediaException.Malformed("XML element <" + qualified + "> closed by </" + closing + ">");
                }
                skipWhitespace();
                expect('>');
                flush(element, content);
                return element;
            }
            if (text.startsWith("<!--", position)) {
                skipPast("-->");
            } else if (text.startsWith("<![CDATA[", position)) {
                var end = text.indexOf("]]>", position + 9);
                if (end < 0) {
                    throw new MediaException.Malformed("unterminated XML CDATA section");
                }
                appendChars(content, text, position + 9, end);
                position = end + 3;
            } else if (text.startsWith("<?", position)) {
                skipPast("?>");
            } else if (text.startsWith("<!", position)) {
                throw new MediaException.Malformed("unexpected XML markup declaration");
            } else if (text.charAt(position) == '<') {
                flush(element, content);
                element.children().add(element(namespaces, depth + 1));
            } else {
                var next = text.indexOf('<', position);
                if (next < 0) {
                    next = text.length();
                }
                content.append(unescape(text.substring(position, next)));
                position = next;
            }
        }
    }

    /**
     * Returns the namespace declarations of an element.
     *
     * @param attributes the raw attributes
     * @return the declared namespaces keyed by prefix
     */
    private static Map<String, String> declared(Map<String, String> attributes) {
        var result = new HashMap<String, String>();
        for (var entry : attributes.entrySet()) {
            if (entry.getKey().equals("xmlns")) {
                result.put("", entry.getValue());
            } else if (entry.getKey().startsWith("xmlns:")) {
                result.put(entry.getKey().substring(6), entry.getValue());
            }
        }
        return result;
    }

    /**
     * Moves collected character data into an element's children.
     *
     * @param element the element receiving the text
     * @param content the collected text, cleared
     */
    private static void flush(Element element, StringBuilder content) {
        if (!content.isEmpty()) {
            element.children().add(content.toString());
            content.setLength(0);
        }
    }

    /**
     * Resolves a prefix.
     *
     * @param namespaces the bindings in scope
     * @param prefix the namespace prefix, or empty for the default namespace
     * @param name the qualified name, for the error message
     * @return the namespace
     * @throws MediaException.Malformed if the prefix is unbound
     */
    private static String resolve(Map<String, String> namespaces, String prefix, String name) {
        var namespace = namespaces.get(prefix);
        if (namespace == null) {
            throw new MediaException.Malformed("unbound XML namespace prefix in " + name);
        }
        return namespace;
    }

    /**
     * Reads a name.
     *
     * @return the name
     * @throws MediaException.Malformed if no name starts here
     */
    private String name() {
        var start = position;
        while (position < text.length()) {
            var c = text.charAt(position);
            if (Character.isWhitespace(c) || c == '=' || c == '>' || c == '/' || c == '<' || c == '"' || c == '\'') {
                break;
            }
            position++;
        }
        if (position == start) {
            throw new MediaException.Malformed("expected an XML name at offset " + start);
        }
        return text.substring(start, position);
    }

    /**
     * Skips whitespace.
     */
    private void skipWhitespace() {
        while (position < text.length() && Character.isWhitespace(text.charAt(position))) {
            position++;
        }
    }

    /**
     * Consumes an expected character.
     *
     * @param expected the character
     * @throws MediaException.Malformed if another character follows
     */
    private void expect(char expected) {
        if (position >= text.length() || text.charAt(position) != expected) {
            throw new MediaException.Malformed("expected '" + expected + "' in XML at offset " + position);
        }
        position++;
    }

    /**
     * Replaces entity and character references.
     *
     * @param raw the raw text
     * @return the text
     * @throws MediaException.Malformed if a reference is unknown or invalid
     */
    static String unescape(String raw) {
        if (raw.indexOf('&') < 0 && !hasControl(raw)) {
            return raw;
        }
        var result = new StringBuilder(raw.length());
        var index = 0;
        while (index < raw.length()) {
            var c = raw.charAt(index);
            if (c != '&') {
                if (!isControl(c)) {
                    result.append(c);
                }
                index++;
                continue;
            }
            var end = raw.indexOf(';', index);
            if (end < 0 || end - index > 12) {
                throw new MediaException.Malformed("unterminated XML reference");
            }
            var name = raw.substring(index + 1, end);
            switch (name) {
                case "lt" -> result.append('<');
                case "gt" -> result.append('>');
                case "amp" -> result.append('&');
                case "quot" -> result.append('"');
                case "apos" -> result.append('\'');
                default -> {
                    if (!name.startsWith("#")) {
                        throw new MediaException.Malformed("unknown XML entity &" + name + ";");
                    }
                    try {
                        var code = name.startsWith("#x") || name.startsWith("#X")
                                ? Integer.parseInt(name.substring(2), 16) : Integer.parseInt(name.substring(1));
                        if (!Character.isValidCodePoint(code) || code < 0x20 && isControl((char) code)
                                || code >= 0xD800 && code <= 0xDFFF || code == 0xFFFE || code == 0xFFFF) {
                            throw new MediaException.Malformed("invalid XML character reference &" + name + ";");
                        }
                        result.appendCodePoint(code);
                    } catch (NumberFormatException exception) {
                        throw new MediaException.Malformed("invalid XML character reference &" + name + ";");
                    }
                }
            }
            index = end + 1;
        }
        return result.toString();
    }

    /** Appends {@code text[start, end)} to {@code out} without the control characters XML 1.0 cannot hold. */
    private static void appendChars(StringBuilder out, String text, int start, int end) {
        for (var i = start; i < end; i++) {
            var c = text.charAt(i);
            if (!isControl(c)) {
                out.append(c);
            }
        }
    }

    /** Returns whether {@link #unescape} would drop a character of {@code text}. */
    private static boolean hasControl(String text) {
        for (var i = 0; i < text.length(); i++) {
            if (isControl(text.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /** Returns whether {@code c} is a C0 control other than tab, line feed and carriage return. */
    private static boolean isControl(char c) {
        return c < 0x20 && c != 0x09 && c != 0x0A && c != 0x0D;
    }

    /**
     * Escapes text for element content or a double-quoted attribute value, dropping characters
     * XML 1.0 cannot hold.
     *
     * @param text the text to escape
     * @return the escaped text
     */
    static String escape(String text) {
        var result = new StringBuilder(text.length() + 16);
        for (var i = 0; i < text.length(); i++) {
            var c = text.charAt(i);
            switch (c) {
                case '&' -> result.append("&amp;");
                case '<' -> result.append("&lt;");
                case '>' -> result.append("&gt;");
                case '"' -> result.append("&quot;");
                default -> {
                    if (c >= 0x20 || c == '\t' || c == '\n') {
                        result.append(c);
                    } else if (c == '\r') {
                        result.append("&#xD;");
                    }
                }
            }
        }
        return result.toString();
    }

    /**
     * Holds one element.
     *
     * @param namespace the namespace, empty when none
     * @param name the local name
     * @param prefix the prefix written, empty when none
     * @param attributes the attributes other than namespace declarations
     * @param children the child elements and text, in order
     * @param declared the namespace declarations of this element, keyed by prefix
     */
    record Element(String namespace, String name, String prefix, List<Attribute> attributes, List<Object> children,
                   Map<String, String> declared) {
        /**
         * Returns whether the element has a namespace and local name.
         *
         * @param namespace the namespace URI
         * @param name the local name
         * @return {@code true} on a match
         */
        boolean is(String namespace, String name) {
            return this.namespace.equals(namespace) && this.name.equals(name);
        }

        /**
         * Returns an attribute value.
         *
         * @param namespace the namespace, empty for an unprefixed attribute
         * @param name the local name
         * @return the value, or {@code null}
         */
        String attribute(String namespace, String name) {
            for (var attribute : attributes) {
                if (attribute.namespace().equals(namespace) && attribute.name().equals(name)) {
                    return attribute.value();
                }
            }
            return null;
        }

        /**
         * Returns the child elements.
         *
         * @return the elements in order
         */
        List<Element> elements() {
            var result = new ArrayList<Element>();
            for (var child : children) {
                if (child instanceof Element element) {
                    result.add(element);
                }
            }
            return result;
        }

        /**
         * Returns the concatenated character data of the element itself.
         *
         * @return the text
         */
        String text() {
            var result = new StringBuilder();
            for (var child : children) {
                if (child instanceof String string) {
                    result.append(string);
                }
            }
            return result.toString();
        }
    }

    /**
     * Holds one attribute.
     *
     * @param namespace the namespace, empty for an unprefixed attribute
     * @param name the local name
     * @param prefix the prefix written
     * @param value the value, references replaced
     */
    record Attribute(String namespace, String name, String prefix, String value) {
    }
}
