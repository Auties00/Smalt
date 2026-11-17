package com.github.auties00.smalt.audio.aac.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.bits.MsbBitWriter;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Holds an AAC {@code program_config_element}: the channel elements a program uses and where
 * their loudspeakers sit.
 *
 * <p>The element lists alone place every loudspeaker at normal height. A
 * {@code height_extension_element} at the head of the comment field (ISO/IEC 14496-3 as amended by
 * Amd. 4) gives each front, side and back element a height layer: an 8-bit sync word {@code 0xAC},
 * two bits per element in list order, alignment to a byte, then a CRC-8 (polynomial
 * x<sup>8</sup> + x<sup>2</sup> + x + 1, initial value {@code 0xFF}, most significant bit first) of
 * the sync word, the height fields and the alignment bits. A comment that does not start with a valid
 * extension is a plain comment, and its elements stay at normal height.
 *
 * @param instanceTag the element instance tag of the configuration itself
 * @param objectType the two-bit object type field (the audio object type minus one)
 * @param samplingIndex the sampling frequency index
 * @param front the front channel elements, from the center outwards
 * @param side the side channel elements, from front to back
 * @param back the back channel elements, from the outside inwards
 * @param lfe the element instance tags of the LFE elements
 * @param couplingElements the coupling channel elements the program uses
 * @param channels the number of output channels: two per pair element, one otherwise
 */
public record ProgramConfig(int instanceTag, int objectType, int samplingIndex, List<Element> front,
                            List<Element> side, List<Element> back, List<Integer> lfe,
                            List<Element> couplingElements, int channels) {
    /**
     * The sync word that opens a {@code height_extension_element}.
     */
    private static final int HEIGHT_SYNC = 0xAC;

    /**
     * The generator polynomial of the height extension CRC, without its x<sup>8</sup> term.
     */
    private static final int HEIGHT_CRC_POLYNOMIAL = 0x07;

    /**
     * The initial value of the height extension CRC.
     */
    private static final int HEIGHT_CRC_INITIAL = 0xFF;

    /**
     * The height layer of a channel element; the ordinal is the value of the height field.
     */
    public enum Height {
        /**
         * The loudspeakers at ear level.
         */
        NORMAL,

        /**
         * The loudspeakers above the listener.
         */
        TOP,

        /**
         * The loudspeakers below the listener.
         */
        BOTTOM
    }

    /**
     * Identifies one element a program uses.
     *
     * @param pair whether the element is a channel pair element (for coupling elements: whether
     * the coupling is independently switched)
     * @param tag the element instance tag
     * @param height the height layer of the element's loudspeakers
     */
    public record Element(boolean pair, int tag, Height height) {
        /**
         * Creates an element at normal height.
         *
         * @param pair whether the element is a channel pair element
         * @param tag the element instance tag
         */
        public Element(boolean pair, int tag) {
            this(pair, tag, Height.NORMAL);
        }
    }

    /**
     * Freezes the element lists.
     *
     * @throws NullPointerException if a list is {@code null}
     */
    public ProgramConfig {
        front = List.copyOf(Objects.requireNonNull(front, "front"));
        side = List.copyOf(Objects.requireNonNull(side, "side"));
        back = List.copyOf(Objects.requireNonNull(back, "back"));
        lfe = List.copyOf(Objects.requireNonNull(lfe, "lfe"));
        couplingElements = List.copyOf(Objects.requireNonNull(couplingElements, "couplingElements"));
    }

    /**
     * Parses a {@code program_config_element} after its element identifier.
     *
     * <p>The reader must be positioned so that its byte boundaries coincide with those the
     * element's {@code byte_alignment()} refers to: the start of the {@code AudioSpecificConfig}
     * or of the raw data block that carries it.
     *
     * @param reader the bit source
     * @return the configuration
     * @throws MediaException.Malformed if the element is truncated or declares no channel
     */
    public static ProgramConfig parse(AacBitReader reader) {
        var instanceTag = reader.read(4);
        var objectType = reader.read(2);
        var samplingIndex = reader.read(4);
        var frontCount = reader.read(4);
        var sideCount = reader.read(4);
        var backCount = reader.read(4);
        var lfeCount = reader.read(2);
        var assocCount = reader.read(3);
        var couplingCount = reader.read(4);
        if (reader.readFlag()) {
            reader.read(4);
        }
        if (reader.readFlag()) {
            reader.read(4);
        }
        if (reader.readFlag()) {
            reader.read(3);
        }
        var channels = 0;
        var front = new ArrayList<Element>(frontCount);
        for (var i = 0; i < frontCount; i++) {
            var element = new Element(reader.readFlag(), reader.read(4));
            channels += element.pair() ? 2 : 1;
            front.add(element);
        }
        var side = new ArrayList<Element>(sideCount);
        for (var i = 0; i < sideCount; i++) {
            var element = new Element(reader.readFlag(), reader.read(4));
            channels += element.pair() ? 2 : 1;
            side.add(element);
        }
        var back = new ArrayList<Element>(backCount);
        for (var i = 0; i < backCount; i++) {
            var element = new Element(reader.readFlag(), reader.read(4));
            channels += element.pair() ? 2 : 1;
            back.add(element);
        }
        var lfe = new ArrayList<Integer>(lfeCount);
        for (var i = 0; i < lfeCount; i++) {
            lfe.add(reader.read(4));
            channels++;
        }
        for (var i = 0; i < assocCount; i++) {
            reader.read(4);
        }
        var coupling = new ArrayList<Element>(couplingCount);
        for (var i = 0; i < couplingCount; i++) {
            coupling.add(new Element(reader.readFlag(), reader.read(4)));
        }
        reader.alignToByte();
        var comment = new byte[reader.read(8)];
        for (var i = 0; i < comment.length; i++) {
            comment[i] = (byte) reader.read(8);
        }
        reader.checkNotOverrun();
        if (channels == 0) {
            throw new MediaException.Malformed("AAC program configuration declares no channel");
        }
        var heights = parseHeights(comment, frontCount + sideCount + backCount);
        if (heights != null) {
            applyHeights(front, heights, 0);
            applyHeights(side, heights, frontCount);
            applyHeights(back, heights, frontCount + sideCount);
        }
        return new ProgramConfig(instanceTag, objectType, samplingIndex, front, side, back, lfe, coupling, channels);
    }

    /**
     * Reads the {@code height_extension_element} a comment field starts with.
     *
     * @param comment the comment field
     * @param elements the number of front, side and back elements
     * @return the height of each element in list order, or {@code null} when the comment is too short for an
     * extension, its sync word or CRC does not match, or a height field holds the reserved value
     */
    private static Height[] parseHeights(byte[] comment, int elements) {
        var checked = heightBytes(elements);
        if (comment.length < checked + 1 || (comment[0] & 0xFF) != HEIGHT_SYNC
                || (comment[checked] & 0xFF) != crc(comment, checked)) {
            return null;
        }
        var layers = Height.values();
        var heights = new Height[elements];
        for (var i = 0; i < elements; i++) {
            var value = comment[1 + i / 4] >>> (6 - 2 * (i % 4)) & 3;
            if (value >= layers.length) {
                return null;
            }
            heights[i] = layers[value];
        }
        return heights;
    }

    /**
     * Replaces elements with copies at the height the extension gives them.
     *
     * @param elements the elements of one list, updated in place
     * @param heights the height of every element of the three lists
     * @param offset the index in {@code heights} of the list's first element
     */
    private static void applyHeights(List<Element> elements, Height[] heights, int offset) {
        for (var i = 0; i < elements.size(); i++) {
            var element = elements.get(i);
            elements.set(i, new Element(element.pair(), element.tag(), heights[offset + i]));
        }
    }

    /**
     * Returns the size of a {@code height_extension_element} before its CRC.
     *
     * @param elements the number of front, side and back elements
     * @return the bytes the sync word, the height fields and their alignment take
     */
    private static int heightBytes(int elements) {
        return 1 + (elements + 3) / 4;
    }

    /**
     * Computes the CRC of a {@code height_extension_element}.
     *
     * @param data the bytes that start with the sync word
     * @param length the number of bytes the CRC covers
     * @return the CRC
     */
    private static int crc(byte[] data, int length) {
        var crc = HEIGHT_CRC_INITIAL;
        for (var i = 0; i < length; i++) {
            crc ^= data[i] & 0xFF;
            for (var bit = 0; bit < 8; bit++) {
                crc = (crc & 0x80) != 0 ? (crc << 1 ^ HEIGHT_CRC_POLYNOMIAL) & 0xFF : crc << 1 & 0xFF;
            }
        }
        return crc;
    }

    /**
     * Writes this configuration as a {@code program_config_element} without its element identifier,
     * with no mixdown, associated data or coupling elements. The comment holds a
     * {@code height_extension_element} when an element sits outside the normal height, and is empty
     * otherwise.
     *
     * <p>The element's {@code byte_alignment()} aligns to the writer's byte boundaries, so the writer
     * must start where the {@code AudioSpecificConfig} or raw data block that carries the element starts.
     *
     * @param writer the bit sink
     * @throws IllegalArgumentException if a list is longer than its count field holds
     */
    public void write(MsbBitWriter writer) {
        if (front.size() > 15 || side.size() > 15 || back.size() > 15 || lfe.size() > 3) {
            throw new IllegalArgumentException("too many elements for a program config element");
        }
        writer.write(instanceTag, 4);
        writer.write(objectType, 2);
        writer.write(samplingIndex, 4);
        writer.write(front.size(), 4);
        writer.write(side.size(), 4);
        writer.write(back.size(), 4);
        writer.write(lfe.size(), 2);
        writer.write(0, 3);
        writer.write(0, 4);
        writer.write(0, 3);
        for (var list : List.of(front, side, back)) {
            for (var element : list) {
                writer.write(element.pair() ? 1 : 0, 1);
                writer.write(element.tag(), 4);
            }
        }
        for (var tag : lfe) {
            writer.write(tag, 4);
        }
        writer.alignWithZeros();
        var extension = heightExtension();
        writer.write(extension.length, 8);
        for (var value : extension) {
            writer.write(value & 0xFF, 8);
        }
    }

    /**
     * Builds the {@code height_extension_element} of this configuration.
     *
     * @return the element, or an empty array when every element sits at normal height
     */
    private byte[] heightExtension() {
        var elements = new ArrayList<Element>(front);
        elements.addAll(side);
        elements.addAll(back);
        if (elements.stream().allMatch(element -> element.height() == Height.NORMAL)) {
            return new byte[0];
        }
        var checked = heightBytes(elements.size());
        var extension = new byte[checked + 1];
        extension[0] = (byte) HEIGHT_SYNC;
        for (var i = 0; i < elements.size(); i++) {
            extension[1 + i / 4] |= (byte) (elements.get(i).height().ordinal() << (6 - 2 * (i % 4)));
        }
        extension[checked] = (byte) crc(extension, checked);
        return extension;
    }
}
