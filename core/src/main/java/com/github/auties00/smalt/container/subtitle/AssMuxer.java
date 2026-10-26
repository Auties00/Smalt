package com.github.auties00.smalt.container.subtitle;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.Muxer;
import com.github.auties00.smalt.container.internal.ConfigChanges;
import com.github.auties00.smalt.internal.subtitle.AssEvents;
import com.github.auties00.smalt.internal.subtitle.AssFile;
import com.github.auties00.smalt.internal.subtitle.AssScript;
import com.github.auties00.smalt.internal.subtitle.TextFiles;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Writes a SubStation Alpha ({@code .ssa}) or Advanced SubStation Alpha ({@code .ass}) script from one
 * {@link Codec#ASS} track.
 *
 * <p>The script is the track configuration, the header of the script (a script with one {@code Default}
 * style when the track has none), with one {@code Dialogue} line per packet inserted at the end of its
 * {@code [Events]} section, in the field order of that section's {@code Format} line, and the sections
 * after it kept in place. The events are written in their read order (the first field of the Matroska
 * block form), so that a script read by {@link AssDemuxer} is written back with its events in their
 * original order; events without a read order follow in the order they arrive. Times are rounded to the
 * centisecond. The script keeps the version its header declares unless the muxer was asked for another
 * ({@link #AssMuxer(MediaSink, AssScriptType)}). The file is UTF-8 with line feeds; it is written by
 * {@link #finish()}, since events are reordered.
 *
 * <p>Instances are not thread-safe.
 */
public final class AssMuxer implements Muxer {
    /**
     * The output.
     */
    private final MediaSink output;

    /**
     * The version to write, or {@code null} for the header's.
     */
    private final AssScriptType type;

    /**
     * The events received.
     */
    private final List<AssFile.Timed> events = new ArrayList<>();

    /**
     * The declared track, or {@code null}.
     */
    private Track track;

    /**
     * Whether {@link #finish()} or {@link #close()} ran.
     */
    private boolean finished;

    /**
     * Constructs a muxer that keeps the version of the track's script.
     *
     * @param output the sink, written from its position; not closed by the muxer
     */
    public AssMuxer(MediaSink output) {
        this.output = Objects.requireNonNull(output, "output");
        this.type = null;
    }

    /**
     * Constructs a muxer that writes one version of the format, converting the script header when it has the
     * other.
     *
     * <p>Converting to ASS turns the styles into {@code [V4+ Styles]} ones (the tertiary color becomes the
     * outline color, the legacy alignment the numpad one) and the events' {@code Marked} fields into layer 0;
     * converting to SSA drops the underline, strikeout, scaling, spacing, angle and layers.
     *
     * @param output the sink, written from its position; not closed by the muxer
     * @param type the version to write
     */
    public AssMuxer(MediaSink output, AssScriptType type) {
        this.output = Objects.requireNonNull(output, "output");
        this.type = Objects.requireNonNull(type, "type");
    }

    /**
     * Returns whether the muxer accepts tracks of a codec.
     *
     * @param codec the codec to look up
     * @return {@code true} for {@link Codec#ASS} only
     */
    public static boolean supports(Codec codec) {
        return codec == Codec.ASS;
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if a track is already declared or its codec is not {@link Codec#ASS}
     */
    @Override
    public void addTrack(Track track) {
        Objects.requireNonNull(track, "track");
        if (finished || !events.isEmpty()) {
            throw new IllegalStateException("tracks must be declared before the first packet");
        }
        if (this.track != null) {
            throw new IllegalArgumentException("a subtitle file holds one track");
        }
        if (track.codec() != Codec.ASS) {
            throw new IllegalArgumentException("SSA and ASS files cannot carry " + track.codec().displayName());
        }
        this.track = track;
    }

    /**
     * {@inheritDoc}
     *
     * @throws MediaException.Unsupported if the packet changes the script header
     */
    @Override
    public void writePacket(Packet packet) {
        Objects.requireNonNull(packet, "packet");
        if (finished) {
            throw new IllegalStateException("the muxer is finished");
        }
        if (track == null || packet.trackId() != track.id()) {
            throw new IllegalArgumentException("packet for undeclared track " + packet.trackId());
        }
        ConfigChanges.reject(packet, track.codecConfig().orElse(null), "an SSA or ASS file");
        var timeBase = track.timeBase();
        var time = packet.pts() != Packet.NO_TIMESTAMP ? packet.pts() : packet.dts();
        var start = time == Packet.NO_TIMESTAMP ? 0 : Math.max(0, micros(timeBase, time));
        var length = micros(timeBase, Math.max(0, packet.duration()));
        var end = start > Long.MAX_VALUE / 2 - length ? Long.MAX_VALUE / 2 : start + length;
        var block = TextFiles.normalizeLineBreaks(new String(packet.data(), StandardCharsets.UTF_8));
        while (block.endsWith("\n")) {
            block = block.substring(0, block.length() - 1);
        }
        events.add(new AssFile.Timed(AssEvents.parseBlock(block), start, end));
    }

    @Override
    public void finish() {
        if (finished) {
            throw new IllegalStateException("the muxer is already finished");
        }
        finished = true;
        var config = track == null ? null : track.codecConfig().orElse(null);
        var header = config == null ? AssEvents.DEFAULT_HEADER : TextFiles.decode(config);
        if (type != null) {
            header = AssScript.convert(header, type == AssScriptType.V4);
        }
        var ordered = new ArrayList<AssFile.Timed>(events.size());
        var unordered = new ArrayList<AssFile.Timed>();
        for (var event : events) {
            (event.event().readOrder() >= 0 ? ordered : unordered).add(event);
        }
        ordered.sort(Comparator.comparingLong(event -> event.event().readOrder()));
        ordered.addAll(unordered);
        output.write(ByteBuffer.wrap(AssFile.write(header, ordered).getBytes(StandardCharsets.UTF_8)));
    }

    @Override
    public void close() {
        finished = true;
    }

    /**
     * Converts a time to microseconds, saturating instead of overflowing.
     *
     * @param timeBase the time base
     * @param value the time in time-base units
     * @return the time in microseconds
     */
    private static long micros(Rational timeBase, long value) {
        try {
            return timeBase.toMicros(value);
        } catch (ArithmeticException exception) {
            return value < 0 ? 0 : Long.MAX_VALUE / 2;
        }
    }
}
