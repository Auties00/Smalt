package com.github.auties00.smalt.image.heic.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.nal.NalRecords;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.video.h264.internal.H264DecoderEngine;

import java.util.Arrays;
import java.util.function.Consumer;

/**
 * Decodes the AVC payloads of {@code avc1} items (ISO/IEC 23008-12 Annex E) and AVC sequence
 * samples with the H.264 decoding engine of this module.
 *
 * <p>Each payload is one access unit; a picture is finished when the next one starts or when the
 * decoder is flushed. A failure of a NAL unit fails the payload that carried it.
 */
final class AvcItemDecoder extends ItemDecoder {
    /**
     * The engine of the sequence or item being decoded, or {@code null}.
     */
    private H264DecoderEngine engine;

    /**
     * The configuration record whose parameter sets were fed last, or {@code null}.
     */
    private byte[] configuration;

    /**
     * Constructs a decoder.
     *
     * @param limits the bounds checked against every picture
     * @param threads the most pictures decoded concurrently; 1 decodes on the calling thread
     */
    AvcItemDecoder(MediaLimits limits, int threads) {
        super(limits, threads);
    }

    @Override
    void open(Consumer<CodedFrame> sink) {
        close();
        engine = new H264DecoderEngine(limits, frame -> sink.accept(new AvcTile(frame)), threads);
        configuration = null;
        lengthSize = 4;
    }

    @Override
    void configure(byte[] record) {
        if (record == null || Arrays.equals(record, configuration)) {
            return;
        }
        if (!NalRecords.looksLikeAvc(record)) {
            throw new MediaException.Malformed("heic: the avcC property is not a configuration record");
        }
        var parsed = NalRecords.parseAvcStrict(record);
        engine.beginPacket(Packet.NO_TIMESTAMP, 0);
        for (var unit : parsed.parameterSets()) {
            engine.decodeNal(unit, 0, unit.length);
        }
        engine.endPacket();
        configuration = record;
        lengthSize = parsed.lengthSize();
    }

    @Override
    void feed(byte[] data, long pts, long duration) {
        engine.beginPacket(pts, duration);
        try {
            forEachNal(data, engine::decodeNal);
        } catch (RuntimeException exception) {
            engine.abandonPacket();
            throw exception;
        }
        engine.endPacket();
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
