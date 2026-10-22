package com.github.auties00.smalt.image.heic.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.nal.NalRecords;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.video.hevc.internal.HevcDecoderEngine;

import java.util.Arrays;
import java.util.function.Consumer;

/**
 * Decodes the HEVC payloads of {@code hvc1} items and HEVC sequence samples with the HEVC decoding
 * engine of this module.
 */
final class HevcItemDecoder extends ItemDecoder {
    /**
     * The engine of the sequence or item being decoded, or {@code null}.
     */
    private HevcDecoderEngine engine;

    /**
     * The configuration record whose parameter sets were fed last, or {@code null}.
     */
    private byte[] configuration;

    /**
     * Constructs a decoder.
     *
     * @param limits the bounds checked against every picture
     * @param threads the most pictures decoded concurrently, and the bound of the wavefront rows
     * decoded concurrently; 1 decodes on the calling thread
     */
    HevcItemDecoder(MediaLimits limits, int threads) {
        super(limits, threads);
    }

    @Override
    void open(Consumer<CodedFrame> sink) {
        close();
        engine = new HevcDecoderEngine(limits, false, threads, frame -> sink.accept(new HevcTile(frame)));
        configuration = null;
        lengthSize = 4;
    }

    @Override
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

    @Override
    void feed(byte[] data, long pts, long duration) {
        engine.beginPacket(pts, duration);
        forEachNal(data, engine::decodeNal);
        engine.endAccessUnit();
    }

    @Override
    void flush() {
        engine.flush();
    }

    @Override
    void close() {
        if (engine != null) {
            var closing = engine;
            engine = null;
            closing.close();
        }
    }
}
