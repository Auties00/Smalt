package com.github.auties00.smalt.container.subtitle;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.Muxer;
import com.github.auties00.smalt.internal.subtitle.TtmlDocument;
import com.github.auties00.smalt.internal.subtitle.TtmlWriter;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Writes a TTML file from one {@link Codec#TTML} track.
 *
 * <p>A track of one document, or of documents that are all the same, is written as that document,
 * unchanged. A track of several documents, as the samples of an MP4 {@code stpp} track are, is written as
 * one document holding their cues: each document's cues are cut to its packet's span, the pieces of a cue
 * that continues across documents joined, and the result written in the IMSC 1.0.1 text profile, its
 * styles and placements kept as far as cues carry them. The file is written by {@link #finish()}.
 *
 * <p>Instances are not thread-safe.
 */
public final class TtmlMuxer implements Muxer {
    /**
     * The output.
     */
    private final MediaSink output;

    /**
     * The documents received.
     */
    private final List<Document> documents = new ArrayList<>();

    /**
     * The declared track, or {@code null}.
     */
    private Track track;

    /**
     * Whether {@link #finish()} or {@link #close()} ran.
     */
    private boolean finished;

    /**
     * Holds one document and its span.
     *
     * @param data the document bytes
     * @param startMicros the packet start
     * @param endMicros the packet end, {@code Long.MAX_VALUE} for a packet without a duration
     */
    private record Document(byte[] data, long startMicros, long endMicros) {
    }

    /**
     * Constructs a muxer writing to a sink from its current position.
     *
     * @param output the sink; not closed by the muxer
     */
    public TtmlMuxer(MediaSink output) {
        this.output = Objects.requireNonNull(output, "output");
    }

    /**
     * Returns whether the muxer accepts tracks of a codec.
     *
     * @param codec the codec to look up
     * @return {@code true} for {@link Codec#TTML} only
     */
    public static boolean supports(Codec codec) {
        return codec == Codec.TTML;
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if a track is already declared or its codec is not {@link Codec#TTML}
     */
    @Override
    public void addTrack(Track track) {
        Objects.requireNonNull(track, "track");
        if (finished || !documents.isEmpty()) {
            throw new IllegalStateException("tracks must be declared before the first packet");
        }
        if (this.track != null) {
            throw new IllegalArgumentException("a TTML file holds one track");
        }
        if (track.codec() != Codec.TTML) {
            throw new IllegalArgumentException("TTML files cannot carry " + track.codec().displayName());
        }
        this.track = track;
    }

    @Override
    public void writePacket(Packet packet) {
        Objects.requireNonNull(packet, "packet");
        if (finished) {
            throw new IllegalStateException("the muxer is finished");
        }
        if (track == null || packet.trackId() != track.id()) {
            throw new IllegalArgumentException("packet for undeclared track " + packet.trackId());
        }
        var timeBase = track.timeBase();
        var time = packet.pts() != Packet.NO_TIMESTAMP ? packet.pts() : packet.dts();
        var start = time == Packet.NO_TIMESTAMP ? 0 : micros(timeBase, time);
        var end = packet.duration() > 0 ? start + micros(timeBase, packet.duration()) : Long.MAX_VALUE;
        documents.add(new Document(packet.data().clone(), start, end));
    }

    /**
     * {@inheritDoc}
     *
     * @throws MediaException.Malformed if the track has several documents and one is not a well-formed TTML
     * document
     * @throws MediaException.Io {@inheritDoc}
     */
    @Override
    public void finish() {
        if (finished) {
            throw new IllegalStateException("the muxer is already finished");
        }
        finished = true;
        if (!documents.isEmpty() && documents.stream().allMatch(document -> Arrays.equals(document.data(),
                documents.getFirst().data()))) {
            output.write(ByteBuffer.wrap(documents.getFirst().data()));
            return;
        }
        var cues = new ArrayList<TtmlDocument.Cue>();
        for (var document : documents) {
            for (var cue : TtmlDocument.parse(document.data()).cues()) {
                var start = Math.max(cue.startMicros(), document.startMicros());
                var end = Math.min(cue.endMicros(), document.endMicros());
                if (end > start) {
                    cues.add(new TtmlDocument.Cue(start, end, cue.text()));
                }
            }
        }
        cues.sort(Comparator.comparingLong(TtmlDocument.Cue::startMicros));
        var joined = new ArrayList<TtmlDocument.Cue>(cues.size());
        for (var cue : cues) {
            var merged = false;
            for (var index = joined.size() - 1; index >= 0 && index >= joined.size() - 16; index--) {
                var previous = joined.get(index);
                if (previous.endMicros() == cue.startMicros() && previous.text().equals(cue.text())) {
                    joined.set(index, new TtmlDocument.Cue(previous.startMicros(), cue.endMicros(), previous.text()));
                    merged = true;
                    break;
                }
            }
            if (!merged) {
                joined.add(cue);
            }
        }
        output.write(ByteBuffer.wrap(TtmlWriter.writeStyled(joined)));
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
            return value < 0 ? 0 : Long.MAX_VALUE / 4;
        }
    }
}
