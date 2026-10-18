package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.internal.subtitle.CueSegmenter;
import com.github.auties00.smalt.internal.subtitle.TimedText;
import com.github.auties00.smalt.internal.subtitle.WebVttSamples;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns the cue packets of a text subtitle track into the gapless, non-overlapping samples an MP4
 * subtitle track stores.
 *
 * <p>Packets may overlap and leave gaps, as cues do. The timeline is cut wherever a cue starts or
 * ends ({@link CueSegmenter}); each piece becomes one sample holding every cue shown during it:
 * for {@link Codec#MOV_TEXT} the packet itself when one cue is shown, the cue texts on separate
 * lines when several are, and an empty sample in a gap; for {@link Codec#WEBVTT} one {@code vttc}
 * box per cue, or a {@code vtte} box in a gap. The timeline starts at zero, so a first cue that
 * starts later is preceded by an empty sample. A packet without a duration lasts until the next
 * packet starts, and is dropped when it is the last. An empty 3GPP sample only clears the screen,
 * so it adds no cue.
 *
 * <p>Instances are not thread-safe.
 */
final class TextSamples {
    /**
     * Receives the samples.
     */
    @FunctionalInterface
    interface Sink {
        /**
         * Receives one sample.
         *
         * @param trackId the track identifier
         * @param data the sample bytes, a new array
         * @param time the sample time in track time-base units
         * @param duration the sample duration in track time-base units, positive
         */
        void sample(int trackId, byte[] data, long time, long duration);
    }

    /**
     * The track identifier.
     */
    private final int trackId;

    /**
     * The codec, {@link Codec#MOV_TEXT} or {@link Codec#WEBVTT}.
     */
    private final Codec codec;

    /**
     * The cutter of the timeline.
     */
    private final CueSegmenter<Object> segmenter;

    /**
     * The packet without a duration waiting for the next start, or {@code null}.
     */
    private Packet open;

    /**
     * Constructs the sample writer of one track.
     *
     * @param trackId the track identifier
     * @param codec {@link Codec#MOV_TEXT} or {@link Codec#WEBVTT}
     * @param sink the receiver of the samples
     */
    TextSamples(int trackId, Codec codec, Sink sink) {
        this.trackId = trackId;
        this.codec = codec;
        this.segmenter = new CueSegmenter<>((start, end, cues) -> sink.sample(trackId, encode(cues), start, end - start));
    }

    /**
     * Returns whether a codec is written through this class.
     *
     * @param codec the codec to look up
     * @return {@code true} for {@link Codec#MOV_TEXT} and {@link Codec#WEBVTT}
     */
    static boolean handles(Codec codec) {
        return codec == Codec.MOV_TEXT || codec == Codec.WEBVTT;
    }

    /**
     * Adds one cue packet.
     *
     * @param packet the packet, in start order
     */
    void offer(Packet packet) {
        var start = packet.pts() != Packet.NO_TIMESTAMP ? packet.pts() : packet.dts();
        if (start == Packet.NO_TIMESTAMP) {
            start = segmenter.position();
        }
        if (open != null) {
            add(open, startOf(open), start);
            open = null;
        }
        if (packet.duration() <= 0) {
            open = packet.withTiming(start, start, 0);
            return;
        }
        add(packet, start, start + packet.duration());
    }

    /**
     * Emits the remaining samples.
     */
    void finish() {
        open = null;
        segmenter.finish();
    }

    /**
     * Returns the start of a packet whose timing was normalized.
     *
     * @param packet a packet whose duration was resolved
     * @return its presentation timestamp
     */
    private static long startOf(Packet packet) {
        return packet.pts();
    }

    /**
     * Adds the cue of a packet to the timeline.
     *
     * @param packet the packet holding the cue
     * @param start the cue start
     * @param end the cue end
     */
    private void add(Packet packet, long start, long end) {
        if (codec == Codec.MOV_TEXT) {
            if (!TimedText.isEmpty(packet.data())) {
                segmenter.offer(start, end, packet.data());
            }
        } else {
            segmenter.offer(start, end, WebVttSamples.cue(packet));
        }
    }

    /**
     * Encodes the sample of the cues shown during one piece of the timeline.
     *
     * @param cues the cue values: sample bytes for 3GPP timed text, cues for WebVTT
     * @return the sample
     */
    private byte[] encode(List<Object> cues) {
        if (codec == Codec.MOV_TEXT) {
            if (cues.isEmpty()) {
                return TimedText.emptySample();
            }
            if (cues.size() == 1) {
                return ((byte[]) cues.getFirst()).clone();
            }
            var samples = new ArrayList<byte[]>(cues.size());
            for (var cue : cues) {
                samples.add((byte[]) cue);
            }
            return TimedText.merge(samples);
        }
        var list = new ArrayList<WebVttSamples.Cue>(cues.size());
        for (var cue : cues) {
            list.add((WebVttSamples.Cue) cue);
        }
        return WebVttSamples.encode(list);
    }

    /**
     * {@return the track identifier}
     */
    int trackId() {
        return trackId;
    }
}
