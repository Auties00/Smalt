package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.internal.subtitle.CueMerger;
import com.github.auties00.smalt.subtitle.SubtitleCue;
import com.github.auties00.smalt.subtitle.SubtitleDecoder;
import com.github.auties00.smalt.subtitle.SubtitleEncoder;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.DataTrack;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Copies or converts one text subtitle track.
 *
 * <p>A copied track keeps its packets and configuration; each packet is moved to the output
 * timeline and cut to the kept range (a packet that starts before the range start begins at zero,
 * one that ends after the range end stops there), and packets wholly outside the range are
 * dropped. A converted track decodes each packet into a cue ({@link SubtitleDecoder}), joins the
 * pieces of cues an MP4 track split, cuts the cue to the kept range in the same way, and encodes
 * it for the output codec ({@link SubtitleEncoder}); a WebVTT or ASS track converted to the same
 * codec keeps its header.
 *
 * <p>After the transcode seeks to a trimmed start, the stage is first {@linkplain #prime(Packet) primed}
 * with the packets that start before the range, read from the start of the input; the packets the
 * seeked demuxer delivers again (the same timing and payload, as many times as they were primed)
 * are then ignored, while a packet the scan did not reach is processed when the seeked demuxer
 * delivers it.
 *
 * <p>An instance is not thread-safe.
 */
final class SubtitleStage implements Stage {
    private final Track source;

    /**
     * Whether packets are copied rather than converted.
     */
    private final boolean copy;

    private final DataTrack declared;
    private final OutputMuxer output;

    /**
     * The start of the kept range in microseconds.
     */
    private final long startMicros;

    /**
     * The end of the kept range in microseconds, or {@code -1} for the end of the track.
     */
    private final long endMicros;

    /**
     * The decoder of a converted track, or {@code null} for a copied one.
     */
    private final SubtitleDecoder decoder;

    /**
     * The encoder of a converted track, or {@code null} for a copied one.
     */
    private final SubtitleEncoder encoder;

    /**
     * The joiner of split cues of a converted track, or {@code null} for a copied one.
     */
    private final CueMerger merger;

    /**
     * The end of the last packet written, on the output timeline in microseconds.
     */
    private long position;

    /**
     * Whether a packet starting at or after the end of the range was seen.
     */
    private boolean done;

    /**
     * How many packets of each timing and payload {@link #prime(Packet)} received and the seeked
     * demuxer has not delivered again.
     */
    private final Map<PrimedPacket, Integer> primed = new HashMap<>();

    /**
     * Identifies a primed packet by its timing and payload, so that a different packet with the
     * same timestamp, such as another cue that appears at the same time, is not taken for it.
     *
     * @param time the timestamp in the source time base
     * @param duration the duration in the source time base
     * @param data the payload
     */
    private record PrimedPacket(long time, long duration, ByteBuffer data) {
        /**
         * {@return the identity of a packet}
         *
         * @param packet a packet of the input track
         */
        static PrimedPacket of(Packet packet) {
            return new PrimedPacket(SubtitleStage.time(packet), packet.duration(), ByteBuffer.wrap(packet.data().clone()));
        }
    }

    /**
     * Constructs a stage and declares its output track.
     *
     * @param source the input track, whose codec is a text subtitle codec
     * @param codec the output codec; the source codec copies the packets unless {@code convert}
     * is set
     * @param convert whether to decode and encode even when the codec does not change
     * @param outputId the output track identifier
     * @param output the muxer receiving the packets
     * @param startMicros the start of the kept range, in microseconds
     * @param endMicros the end of the kept range in microseconds, or {@code -1} for the end of the track
     */
    SubtitleStage(Track source, Codec codec, boolean convert, int outputId, OutputMuxer output, long startMicros,
                  long endMicros) {
        this.source = source;
        this.copy = !convert && codec == source.codec();
        this.output = output;
        this.startMicros = startMicros;
        this.endMicros = endMicros;
        if (copy) {
            var builder = source instanceof DataTrack data ? data.toBuilder()
                    : DataTrack.builder().codec(codec).timeBase(source.timeBase()).codecConfig(source.codecConfig().orElse(null));
            this.declared = builder.id(outputId).duration(-1).language(source.language().orElse(null)).build();
            this.decoder = null;
            this.encoder = null;
            this.merger = null;
        } else {
            var created = SubtitleEncoder.track(codec, outputId).toBuilder().language(source.language().orElse(null));
            if ((codec == Codec.WEBVTT || codec == Codec.ASS) && codec == source.codec()) {
                source.codecConfig().ifPresent(created::codecConfig);
            }
            this.declared = created.build();
            this.decoder = new SubtitleDecoder(source);
            this.encoder = new SubtitleEncoder(declared);
            this.merger = new CueMerger(this::writeCue);
        }
        output.declare(declared);
    }

    @Override
    public int sourceTrackId() {
        return source.id();
    }

    @Override
    public void accept(Packet packet) {
        if (!primed.isEmpty() && startsBeforeRange(packet)) {
            var key = PrimedPacket.of(packet);
            var count = primed.get(key);
            if (count != null) {
                if (count == 1) {
                    primed.remove(key);
                } else {
                    primed.put(key, count - 1);
                }
                return;
            }
        }
        process(packet);
    }

    /**
     * {@return whether a packet starts before the kept range, as every primed packet does}
     *
     * @param packet a packet of the input track
     */
    private boolean startsBeforeRange(Packet packet) {
        var time = time(packet);
        return time != Packet.NO_TIMESTAMP && source.timeBase().toMicros(time) < startMicros;
    }

    /**
     * Processes a packet that starts before the kept range and was read ahead of the seek point,
     * so that the same packet delivered again by the seeked demuxer is ignored.
     *
     * @param packet a packet of the input track
     */
    void prime(Packet packet) {
        primed.merge(PrimedPacket.of(packet), 1, Integer::sum);
        process(packet);
    }

    /**
     * {@return the timestamp of a packet in the source time base: its presentation time, else its
     * decoding time, else {@link Packet#NO_TIMESTAMP}}
     *
     * @param packet a packet of the input track
     */
    private static long time(Packet packet) {
        return packet.pts() != Packet.NO_TIMESTAMP ? packet.pts() : packet.dts();
    }

    /**
     * Copies or decodes a packet.
     *
     * @param packet a packet of the input track
     */
    private void process(Packet packet) {
        if (done) {
            return;
        }
        if (copy) {
            copyPacket(packet);
            return;
        }
        var cue = decoder.decode(packet);
        if (cue == null) {
            return;
        }
        if (endMicros >= 0 && cue.startMicros() >= endMicros) {
            done = true;
            return;
        }
        merger.offer(cue);
    }

    /**
     * Retimes and writes a copied packet.
     *
     * @param packet a packet of the input track
     */
    private void copyPacket(Packet packet) {
        var timeBase = source.timeBase();
        var time = time(packet);
        if (time == Packet.NO_TIMESTAMP) {
            return;
        }
        var start = timeBase.toMicros(time);
        var end = timeBase.toMicros(time + Math.max(0, packet.duration()));
        if (endMicros >= 0 && start >= endMicros) {
            done = true;
            return;
        }
        if (end <= startMicros && packet.duration() > 0) {
            return;
        }
        var outputStart = Math.max(0, start - startMicros);
        var outputEnd = endMicros >= 0 ? Math.min(end, endMicros) - startMicros : end - startMicros;
        var pts = Rational.MICROSECONDS.rescale(outputStart, timeBase);
        var duration = packet.duration() <= 0 ? packet.duration()
                : Math.max(0, Rational.MICROSECONDS.rescale(outputEnd, timeBase) - pts);
        output.write(packet.withTrackId(declared.id()).withTiming(pts, pts, duration));
        position = Math.max(position, Math.max(outputStart, outputEnd));
    }

    /**
     * Cuts a joined cue to the kept range and writes it.
     *
     * @param cue the cue on the input timeline
     */
    private void writeCue(SubtitleCue cue) {
        var end = endMicros >= 0 ? Math.min(cue.endMicros(), endMicros) : cue.endMicros();
        if (end <= startMicros && cue.durationMicros() > 0 || endMicros >= 0 && cue.startMicros() >= endMicros) {
            return;
        }
        var outputStart = Math.max(0, cue.startMicros() - startMicros);
        var outputEnd = Math.max(outputStart, end - startMicros);
        output.write(encoder.encode(cue.withTiming(outputStart, outputEnd), decoder.markup()));
        position = Math.max(position, outputEnd);
    }

    @Override
    public void finish() {
        if (merger != null) {
            merger.finish();
        }
        done = true;
    }

    @Override
    public boolean done() {
        return done;
    }

    @Override
    public long positionMicros() {
        return position;
    }

    @Override
    public TranscodedStream result() {
        return new TranscodedStream(source.id(), declared, copy);
    }

    @Override
    public void close() {
    }
}
