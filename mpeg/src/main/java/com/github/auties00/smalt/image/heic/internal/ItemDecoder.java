package com.github.auties00.smalt.image.heic.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.nal.NalRecords;
import com.github.auties00.smalt.internal.nal.NalUnits;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.video.hevc.internal.HevcDecoderEngine;
import com.github.auties00.smalt.video.hevc.internal.HevcFrame;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * Decodes the HEVC payloads of HEIC image items and sequence samples with the HEVC decoding
 * engine of this module.
 *
 * <p>An item is decoded on a fresh engine: the parameter sets of its {@code hvcC} configuration
 * first, then the items it is predicted from ({@code pred} references), then the item itself,
 * each payload a sequence of NAL units prefixed by their length. A sequence keeps one engine for
 * all its samples, because each sample predicts from the ones before it. Instances are not
 * thread-safe: a grid decoded in parallel uses one instance per thread.
 */
final class ItemDecoder {
    /**
     * The resource bounds of every picture.
     */
    private final MediaLimits limits;

    /**
     * The most pictures the engine decodes concurrently; 1 decodes on the calling thread.
     */
    private final int threads;

    /**
     * The engine of the sequence or item being decoded, or {@code null}.
     */
    private HevcDecoderEngine engine;

    /**
     * The configuration record whose parameter sets were fed last, or {@code null}.
     */
    private byte[] configuration;

    /**
     * The size of the NAL unit length prefixes of the current configuration.
     */
    private int lengthSize = 4;

    /**
     * The last picture the item decode produced.
     */
    private HevcFrame last;

    /**
     * Constructs a decoder.
     *
     * @param limits the bounds checked against every picture
     * @param threads the most pictures decoded concurrently, and the bound of the wavefront rows
     * decoded concurrently; 1 decodes on the calling thread
     */
    ItemDecoder(MediaLimits limits, int threads) {
        this.limits = limits;
        this.threads = threads;
    }

    /**
     * Decodes a coded item, after the items it is predicted from.
     *
     * <p>The returned picture is the last one the engine outputs, cropped by its conformance
     * window when read through its {@link HevcFrame#sps}. It stays valid until the next call on
     * this decoder.
     *
     * @param configurations the {@code hvcC} record of each payload
     * @param payloads the payloads in decoding order, the item's own payload last
     * @return the decoded picture
     * @throws MediaException.Malformed if a payload is corrupt or no picture is output
     * @throws MediaException.Unsupported if the item uses a coding tool the engine lacks
     * @throws MediaException.LimitExceeded if the picture exceeds the limits
     */
    HevcFrame decodeItem(List<byte[]> configurations, List<byte[]> payloads) {
        last = null;
        open(frame -> last = frame);
        try {
            for (var i = 0; i < payloads.size(); i++) {
                configure(configurations.get(i));
                feed(payloads.get(i), Packet.NO_TIMESTAMP, 0);
            }
            engine.flush();
        } finally {
            close();
        }
        if (last == null) {
            throw new MediaException.Malformed("heic: the item shows no picture");
        }
        return last;
    }

    /**
     * Starts a new decoding state.
     *
     * @param sink receives the pictures in output order; their samples may be reused once it
     * returns
     */
    void open(Consumer<HevcFrame> sink) {
        close();
        engine = new HevcDecoderEngine(limits, false, threads, sink);
        configuration = null;
        lengthSize = 4;
    }

    /**
     * Feeds the parameter sets of a configuration record, unless they were fed last.
     *
     * @param record the {@code hvcC} record, or {@code null} when the payloads carry their
     * parameter sets
     * @throws MediaException.Malformed if the record is corrupt
     */
    void configure(byte[] record) {
        if (record == null || Arrays.equals(record, configuration)) {
            return;
        }
        if (!NalRecords.looksLikeHevc(record)) {
            throw new MediaException.Malformed("heic: the hvcC property is not a configuration record");
        }
        var parsed = NalRecords.parseHevcStrict(record);
        engine.beginPacket(Packet.NO_TIMESTAMP, 0);
        for (var unit : parsed.parameterSets()) {
            engine.decodeNal(unit, 0, unit.length);
        }
        configuration = record;
        lengthSize = parsed.lengthSize();
    }

    /**
     * Decodes one payload of length-prefixed NAL units as one access unit.
     *
     * @param data the payload
     * @param pts the presentation timestamp the pictures of the payload carry
     * @param duration the duration the pictures of the payload carry
     * @throws MediaException.Malformed if a length prefix runs past the payload or a NAL unit is
     * corrupt
     */
    void feed(byte[] data, long pts, long duration) {
        engine.beginPacket(pts, duration);
        var broken = NalUnits.forEachLengthPrefixed(data, lengthSize, engine::decodeNal);
        if (broken < data.length) {
            if (broken + lengthSize > data.length) {
                throw new MediaException.Malformed("heic: truncated NAL unit length prefix");
            }
            throw new MediaException.Malformed("heic: NAL unit length " + NalUnits.readLength(data, broken, lengthSize)
                    + " exceeds the payload");
        }
        engine.endAccessUnit();
    }

    /**
     * Outputs every picture still waiting in the engine.
     */
    void flush() {
        engine.flush();
    }

    /**
     * Discards the decoding state, waiting for the pictures in flight.
     */
    void close() {
        if (engine != null) {
            var closing = engine;
            engine = null;
            closing.close();
        }
    }
}
