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
     * Identifies one element a program uses.
     *
     * @param pair whether the element is a channel pair element (for coupling elements: whether
     * the coupling is independently switched)
     * @param tag the element instance tag
     */
    public record Element(boolean pair, int tag) {
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
        var commentBytes = reader.read(8);
        reader.skip(8L * commentBytes);
        reader.checkNotOverrun();
        if (channels == 0) {
            throw new MediaException.Malformed("AAC program configuration declares no channel");
        }
        return new ProgramConfig(instanceTag, objectType, samplingIndex, front, side, back, lfe, coupling, channels);
    }

    /**
     * Writes this configuration as a {@code program_config_element} without its element identifier,
     * with no mixdown, associated data or coupling elements and an empty comment.
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
        writer.write(0, 8);
    }
}
