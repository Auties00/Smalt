package com.github.auties00.smalt.container.ts;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;

/**
 * Splits the PES payloads of one elementary stream into the packets of its track.
 *
 * <p>A parser starts unconfigured: it learns the format of its stream from the first units it
 * parses and then describes it as a {@link Track}. The demuxer feeds every parser the start of
 * the input to configure it, then creates {@linkplain #fresh() fresh} parsers that share the
 * configured track for each read pass. Instances are not thread-safe.
 */
abstract class ElementaryParser {
    /**
     * The program map entry of the stream.
     */
    final Psi.StreamEntry entry;

    /**
     * The resource bounds.
     */
    final MediaLimits limits;

    /**
     * The track, or {@code null} until the parser is configured.
     */
    Track track;

    /**
     * Constructs a parser.
     *
     * @param entry the program map entry of the stream
     * @param limits the resource bounds
     */
    ElementaryParser(Psi.StreamEntry entry, MediaLimits limits) {
        this.entry = entry;
        this.limits = limits;
    }

    /**
     * Holds one reassembled PES packet.
     *
     * @param data the bytes holding the payload
     * @param offset the index of the payload, after the PES header
     * @param length the payload length
     * @param pts the 33-bit presentation timestamp, or {@link TsClock#NONE}
     * @param dts the 33-bit decoding timestamp, or {@link TsClock#NONE}
     * @param fileOffset the offset of the transport packet that started the PES packet
     * @param corrupt whether a transport packet of the PES packet was lost or damaged
     * @param randomAccess whether the adaptation field flagged a random access point
     */
    record Pes(byte[] data, int offset, int length, long pts, long dts, long fileOffset, boolean corrupt,
               boolean randomAccess) {
    }

    /**
     * Receives the packets a parser produces.
     */
    @FunctionalInterface
    interface UnitSink {
        /**
         * Receives one packet.
         *
         * @param packet the packet, in the track's time base
         * @param fileOffset the offset of the transport packet that started the PES packet holding
         * the packet's first byte, where a read must restart to reproduce it
         */
        void accept(Packet packet, long fileOffset);
    }

    /**
     * Returns whether the parser knows the format of its stream.
     *
     * @return {@code true} once {@link #track()} is set
     */
    final boolean configured() {
        return track != null;
    }

    /**
     * Returns the track.
     *
     * @return the track, or {@code null} when the parser is not configured
     */
    final Track track() {
        return track;
    }

    /**
     * {@return the identifier of the track, which also keys the stream on the timeline}
     */
    final int trackId() {
        return entry.trackId();
    }

    /**
     * Returns whether the stream is carried in sections rather than PES packets.
     *
     * @return {@code true} for a section-carried stream, which receives {@link #section} calls
     */
    boolean sectionCarried() {
        return false;
    }

    /**
     * Parses one complete section of a section-carried stream.
     *
     * <p>The default implementation ignores the section.
     *
     * @param section the section, its {@code CRC_32} verified
     * @param fileOffset the offset of the transport packet in which the section started
     * @param clock the timeline
     * @param sink receives the packets completed by the section
     */
    void section(byte[] section, long fileOffset, TsClock clock, UnitSink sink) {
    }

    /**
     * Lets a section-carried parser release what it held back until the timeline had a time, after a
     * transport packet of a PES stream was processed.
     *
     * <p>The default implementation does nothing.
     *
     * @param clock the timeline
     * @param sink receives the released packets
     */
    void tick(TsClock clock, UnitSink sink) {
    }

    /**
     * Parses one PES packet.
     *
     * @param pes the packet
     * @param clock the timeline
     * @param sink receives the packets completed by this payload
     */
    abstract void accept(Pes pes, TsClock clock, UnitSink sink);

    /**
     * Completes the packets still buffered at the end of the input.
     *
     * @param clock the timeline
     * @param sink receives the packets
     */
    abstract void flush(TsClock clock, UnitSink sink);

    /**
     * Creates a parser with an empty state that describes its stream with this parser's track.
     *
     * @return the parser
     */
    abstract ElementaryParser fresh();

    /**
     * Makes the parser emit the units it would otherwise drop because no decoder can start at
     * them (video before the first key frame), so that a seek search sees every unit.
     *
     * <p>The default implementation does nothing: other parsers drop nothing.
     */
    void keepLeadingUnits() {
    }
}
