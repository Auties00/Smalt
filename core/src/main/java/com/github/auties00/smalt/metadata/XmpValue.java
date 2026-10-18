package com.github.auties00.smalt.metadata;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Holds the value of an XMP property, following the XMP data model (ISO 16684-1): a simple text
 * value, an array of values, or a structure of named fields.
 *
 * @apiNote Values are immutable and thread-safe; build them with {@link #text(String)},
 * {@link #ordered(String...)}, {@link #unordered(String...)}, {@link #alternative(String)} or the
 * record constructors.
 */
public sealed interface XmpValue permits XmpValue.Text, XmpValue.Array, XmpValue.Struct {
    /**
     * Returns a simple text value without language.
     *
     * @param value the text
     * @return the value
     * @throws NullPointerException if {@code value} is {@code null}
     */
    static Text text(String value) {
        return new Text(value, null);
    }

    /**
     * Returns a simple text value qualified by a language.
     *
     * @param value the text
     * @param language the {@code xml:lang} qualifier, such as {@code "x-default"} or {@code "en-US"},
     *     or {@code null} for none
     * @return the value
     */
    static Text text(String value, String language) {
        return new Text(value, language);
    }

    /**
     * Returns an ordered array ({@code rdf:Seq}) of text items.
     *
     * @param items the text items, in order
     * @return the value
     * @throws NullPointerException if an item is {@code null}
     */
    static Array ordered(String... items) {
        return array(Array.Kind.ORDERED, items);
    }

    /**
     * Returns an unordered array ({@code rdf:Bag}) of text items.
     *
     * @param items the text items
     * @return the value
     * @throws NullPointerException if an item is {@code null}
     */
    static Array unordered(String... items) {
        return array(Array.Kind.UNORDERED, items);
    }

    /**
     * Returns a language alternative ({@code rdf:Alt}) with one {@code x-default} item, the form
     * of {@code dc:title} and {@code dc:description}.
     *
     * @param value the default text
     * @return the value
     * @throws NullPointerException if {@code value} is {@code null}
     */
    static Array alternative(String value) {
        return new Array(Array.Kind.ALTERNATIVE, List.of(new Text(value, XmpPacket.DEFAULT_LANGUAGE)));
    }

    /**
     * Builds an array of text items.
     *
     * @param kind  the array kind
     * @param items the text items, in order
     * @return the value
     */
    private static Array array(Array.Kind kind, String... items) {
        var values = new ArrayList<XmpValue>(items.length);
        for (var item : items) {
            values.add(text(item));
        }
        return new Array(kind, values);
    }

    /**
     * Returns the text this value stands for: the text of a simple value, the
     * {@code x-default} or first item of a language alternative, or the first item of another
     * array.
     *
     * @return the text, or empty for a structure or an empty array
     */
    Optional<String> asText();

    /**
     * A simple text value, possibly qualified by a language.
     *
     * <p>Two text values are equal when their texts and language qualifiers are equal. Instances are
     * immutable and thread-safe.
     */
    final class Text implements XmpValue {
        private final String value;
        private final String language;

        /**
         * Constructs a text value whose language qualifier may be absent.
         *
         * @param value the text
         * @param language the {@code xml:lang} qualifier, such as {@code "x-default"} or {@code "en-US"},
         * or {@code null} for none
         */
        public Text(String value, String language) {
            this.value = Objects.requireNonNull(value, "value");
            this.language = language;
        }

        /**
         * {@return the text}
         */
        public String value() {
            return value;
        }

        /**
         * {@return the {@code xml:lang} qualifier, or empty when the text has none}
         */
        public Optional<String> language() {
            return Optional.ofNullable(language);
        }

        @Override
        public Optional<String> asText() {
            return Optional.of(value);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Text that && value.equals(that.value) && Objects.equals(language, that.language);
        }

        @Override
        public int hashCode() {
            return 31 * value.hashCode() + Objects.hashCode(language);
        }

        @Override
        public String toString() {
            return "Text[value=" + value + ", language=" + language() + "]";
        }
    }

    /**
     * An array of values.
     *
     * @param kind  the array kind
     * @param items the items, in order
     */
    record Array(Kind kind, List<XmpValue> items) implements XmpValue {
        /**
         * Validates the value and copies the items.
         *
         * @throws NullPointerException if an argument or an item is {@code null}
         */
        public Array {
            Objects.requireNonNull(kind, "kind");
            items = List.copyOf(items);
        }

        @Override
        public Optional<String> asText() {
            if (kind == Kind.ALTERNATIVE) {
                for (var item : items) {
                    if (item instanceof Text text && text.language().filter(XmpPacket.DEFAULT_LANGUAGE::equals).isPresent()) {
                        return Optional.of(text.value());
                    }
                }
            }
            return items.isEmpty() ? Optional.empty() : items.getFirst().asText();
        }

        /**
         * Names the three RDF containers.
         */
        public enum Kind {
            /**
             * An ordered array, {@code rdf:Seq}.
             */
            ORDERED,

            /**
             * An unordered array, {@code rdf:Bag}.
             */
            UNORDERED,

            /**
             * An alternative array, {@code rdf:Alt}; with language qualifiers, a language
             * alternative.
             */
            ALTERNATIVE
        }
    }

    /**
     * A structure of named fields.
     *
     * @param fields the fields, in order
     */
    record Struct(List<XmpProperty> fields) implements XmpValue {
        /**
         * Copies the fields.
         *
         * @throws NullPointerException if the list or a field is {@code null}
         */
        public Struct {
            fields = List.copyOf(fields);
        }

        @Override
        public Optional<String> asText() {
            return Optional.empty();
        }

        /**
         * Returns the value of a field.
         *
         * @param namespace the namespace of the field
         * @param name      the local name of the field
         * @return the value, or empty when absent
         */
        public Optional<XmpValue> field(String namespace, String name) {
            for (var field : fields) {
                if (field.namespace().equals(namespace) && field.name().equals(name)) {
                    return Optional.of(field.value());
                }
            }
            return Optional.empty();
        }
    }
}
