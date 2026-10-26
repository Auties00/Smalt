package com.github.auties00.smalt.image.heic.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.nal.NalRecords;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.video.hevc.internal.HevcDecoderEngine;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/**
 * Decodes the HEVC payloads of {@code hvc1} items, layered HEVC {@code lhv1} items and HEVC
 * sequence samples with the HEVC decoding engine of this module.
 *
 * <p>A layered item decodes the layers of its target output layer set; its picture is the last one
 * output of the selected layer, or of any layer when none is selected, which is the highest output
 * layer of the item's access unit.
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
     * The target output layer set of the layered item being decoded, or -1 when the payloads are
     * not a layered item and only the base layer is decoded.
     */
    private int outputLayerSet = -1;

    /**
     * The {@code nuh_layer_id} of the layer whose picture a layered item shows, or -1 for the
     * highest output layer.
     */
    private int selectedLayer = -1;

    /**
     * The external base layer of the layered item being decoded, or {@code null}.
     */
    private HevcDecoderEngine.ExternalBaseLayer externalBase;

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

    /**
     * Decodes a layered HEVC item ({@code lhv1}), after the items it is predicted from.
     *
     * @param configurations the {@code lhvC} configuration record of each payload
     * @param payloads the payloads in decoding order, the item's own payload last
     * @param outputLayerSet the target output layer set ({@code tols})
     * @param layer the {@code nuh_layer_id} of the layer to show ({@code lsel}), or -1 for the
     * highest output layer
     * @param base the base layer coded outside the item ({@code exbl}), or {@code null}
     * @return the decoded picture, valid until the next call on this decoder
     * @throws MediaException.Malformed if a payload is corrupt or the layer shows no intact
     * picture
     * @throws MediaException.Unsupported if the item uses a coding tool the engine lacks, or
     * codes its base layer outside it and {@code base} is {@code null}
     * @throws MediaException.LimitExceeded if the picture exceeds the limits
     */
    CodedFrame decodeLayered(List<byte[]> configurations, List<byte[]> payloads, int outputLayerSet, int layer,
                             HevcDecoderEngine.ExternalBaseLayer base) {
        this.outputLayerSet = outputLayerSet;
        this.selectedLayer = layer;
        this.externalBase = base;
        try {
            return decodeItem(configurations, payloads);
        } finally {
            this.outputLayerSet = -1;
            this.selectedLayer = -1;
            this.externalBase = null;
        }
    }

    @Override
    void open(Consumer<CodedFrame> sink) {
        close();
        if (outputLayerSet < 0) {
            engine = new HevcDecoderEngine(limits, false, threads, frame -> sink.accept(new HevcTile(frame)));
            engine.startAnywhere(decodingItem);
        } else {
            var layer = selectedLayer;
            engine = new HevcDecoderEngine(limits, false, threads, outputLayerSet, false, frame -> {
                if (layer < 0 || frame.layerId == layer) {
                    sink.accept(new HevcTile(frame));
                }
            });
            engine.externalBaseLayer(externalBase);
            engine.startAnywhere(decodingItem);
        }
        configuration = null;
        lengthSize = 4;
    }

    @Override
    void configure(byte[] record) {
        if (record == null || Arrays.equals(record, configuration)) {
            return;
        }
        NalRecords.Parsed parsed;
        List<byte[]> units;
        if (outputLayerSet < 0) {
            if (!NalRecords.looksLikeHevc(record)) {
                throw new MediaException.Malformed("heic: the hvcC property is not a configuration record");
            }
            parsed = NalRecords.parseHevcStrict(record);
            units = parsed.parameterSets();
        } else {
            if (record.length == 0 || record[0] != 1) {
                throw new MediaException.Malformed("heic: the lhvC property is not a configuration record");
            }
            parsed = NalRecords.parseLayeredHevcStrict(record);
            // the record lists its arrays in any order, while the sequence parameter sets of the
            // layers above the base layer are parsed against the video parameter set
            units = parsed.parameterSets().stream().sorted(Comparator.comparingInt(HevcItemDecoder::feedOrder))
                    .toList();
        }
        engine.beginPacket(Packet.NO_TIMESTAMP, 0);
        for (var unit : units) {
            engine.decodeNal(unit, 0, unit.length);
        }
        configuration = record;
        lengthSize = parsed.lengthSize();
    }

    /**
     * {@return the rank of a parameter set NAL unit in decoding order: video, sequence, picture
     * parameter sets, then the other units}
     *
     * @param unit the NAL unit
     */
    private static int feedOrder(byte[] unit) {
        if (unit.length == 0) {
            return 3;
        }
        return switch ((unit[0] >> 1) & 0x3F) {
            case 32 -> 0;
            case 33 -> 1;
            case 34 -> 2;
            default -> 3;
        };
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
