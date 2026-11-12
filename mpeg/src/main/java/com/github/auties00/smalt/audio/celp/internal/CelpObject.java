package com.github.auties00.smalt.audio.celp.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.aac.internal.ep.ErrorProtectionDecoder;
import com.github.auties00.smalt.internal.bits.MsbBitReader;

import java.util.Arrays;
import java.util.List;

/**
 * Decodes a CELP audio object from the access units of its elementary streams: the base layer, as one access unit or,
 * for ER CELP with {@code epConfig} 1 or 3, as five error sensitivity category instances, then the bitrate scalable
 * layers in the order of {@code CELP-BRS-id} and the bandwidth scalable layer (ISO/IEC 14496-3 subclause 3.3).
 *
 * <p>Instances are not thread-safe.
 */
public final class CelpObject {
    /**
     * The contents of an access unit that is absent.
     */
    private static final byte[] NO_DATA = new byte[0];

    /**
     * The configuration of the base layer.
     */
    private final CelpConfig base;

    /**
     * The structure of the base layer.
     */
    private final CelpLayout layout;

    /**
     * The number of elementary streams of the base layer, 1 or 5.
     */
    private final int baseStreams;

    /**
     * The track index of each bitrate scalable layer, by layer, or -1 where the object has no track.
     */
    private final int[] bitrateTracks = {-1, -1, -1};

    /**
     * The number of bitrate scalable layers decoded.
     */
    private final int bitrateLayers;

    /**
     * The track index of the bandwidth scalable layer, or -1 when it is not decoded.
     */
    private final int bandwidthTrack;

    /**
     * The number of elementary streams of the object, the units {@link #decode} expects.
     */
    private final int streams;

    /**
     * The error protection decoder of each elementary stream, or {@code null} without error protection.
     */
    private final ErrorProtectionDecoder[] protection;

    /**
     * The reader of the frame fields.
     */
    private final CelpFrameReader reader;

    /**
     * The fields of the current frame.
     */
    private final CelpFrame frame = new CelpFrame();

    /**
     * The decoder of the speech.
     */
    private final CelpCore core;

    /**
     * The bit reader of each unit of a frame.
     */
    private final MsbBitReader[] readers;

    /**
     * The speech of a frame, in 16-bit units.
     */
    private final double[] speech;

    /**
     * The units of a frame after error protection decoding, and their sizes.
     */
    private final byte[][] units;

    private final int[] sizes;

    /**
     * Creates the decoder of an object.
     *
     * @param configs the {@code AudioSpecificConfig} of each elementary stream, in stream order, the base layer first
     * @param maxLayer the highest layer to decode, from 0 (the base layer) to 4, counting the bitrate scalable layers
     * from 1 and the bandwidth scalable layer after them; -1 for every layer
     * @param postFilter whether to run the post-processor
     * @param limits the resource limits
     * @throws MediaException.Malformed if a configuration is invalid or the configurations do not describe the
     * layers of one object
     * @throws MediaException.Unsupported if a configuration is not of a CELP object, or maps error protection classes
     * without direct mapping
     */
    public CelpObject(List<byte[]> configs, int maxLayer, boolean postFilter, MediaLimits limits) {
        if (configs.isEmpty()) {
            throw new IllegalArgumentException("no configuration");
        }
        var parsed = new CelpConfig[configs.size()];
        for (var i = 0; i < parsed.length; i++) {
            parsed[i] = CelpConfig.parse(configs.get(i));
        }
        this.base = parsed[0];
        if (base.layer() != CelpConfig.BASE) {
            throw new MediaException.Malformed("the first CELP stream is not a base layer");
        }
        this.layout = new CelpLayout(base);
        var baseCount = base.epConfig() == 1 ? CelpFrameReader.CATEGORIES : 1;
        for (var i = 1; i < baseCount; i++) {
            if (i >= parsed.length || parsed[i].layer() != CelpConfig.BASE) {
                throw new MediaException.Malformed("ER CELP base layer with epConfig 1 in fewer than "
                        + CelpFrameReader.CATEGORIES + " streams");
            }
            if (parsed[i].objectType() != base.objectType() || parsed[i].epConfig() != base.epConfig()) {
                throw new MediaException.Malformed("CELP layers of different object types or epConfig");
            }
        }
        this.baseStreams = baseCount;
        var bandwidth = -1;
        var bandwidthConfiguration = -1;
        for (var i = baseCount; i < parsed.length; i++) {
            var config = parsed[i];
            if (config.objectType() != base.objectType() || config.epConfig() != base.epConfig()) {
                throw new MediaException.Malformed("CELP layers of different object types or epConfig");
            }
            switch (config.layer()) {
                case CelpConfig.BITRATE -> {
                    var id = config.enhancementId();
                    if (id <= base.enhancementLayers() && bitrateTracks[id - 1] < 0) {
                        bitrateTracks[id - 1] = i;
                    }
                }
                case CelpConfig.BANDWIDTH -> {
                    if (base.bandwidthScalable() && bandwidth < 0) {
                        bandwidth = i;
                        bandwidthConfiguration = config.bandwidthConfiguration();
                    }
                }
                default -> throw new MediaException.Malformed("more than one CELP base layer");
            }
        }
        var limit = maxLayer < 0 ? Integer.MAX_VALUE : maxLayer;
        var layers = 0;
        while (layers < base.enhancementLayers() && bitrateTracks[layers] >= 0 && layers < limit) {
            layers++;
        }
        this.bitrateLayers = layers;
        var decodeBandwidth = bandwidth >= 0 && layers + 1 <= limit && !layout.rpe && !layout.wideband;
        this.bandwidthTrack = decodeBandwidth ? bandwidth : -1;
        this.streams = parsed.length;
        if (base.epConfig() >= 2) {
            this.protection = new ErrorProtectionDecoder[parsed.length];
            for (var i = 0; i < parsed.length; i++) {
                var config = parsed[i].errorProtection();
                if (config == null || config.concatenatedFrames() != base.errorProtection().concatenatedFrames()) {
                    throw new MediaException.Malformed("CELP streams with different error protection framing");
                }
                protection[i] = new ErrorProtectionDecoder(config, limits);
            }
        } else {
            this.protection = null;
        }
        this.reader = new CelpFrameReader(base, layout, bandwidthConfiguration);
        this.core = new CelpCore(base, layout, decodeBandwidth ? bandwidthConfiguration : -1, postFilter);
        this.units = new byte[parsed.length + CelpFrameReader.CATEGORIES][];
        this.sizes = new int[units.length];
        this.readers = new MsbBitReader[units.length];
        for (var i = 0; i < readers.length; i++) {
            readers[i] = new MsbBitReader(NO_DATA);
        }
        this.speech = new double[2 * layout.frameSize];
    }

    /**
     * {@return the sampling rate of the decoded speech: that of the base layer, doubled with the bandwidth
     * extension}
     */
    public int sampleRate() {
        return bandwidthTrack >= 0 ? 2 * base.sampleRate() : base.sampleRate();
    }

    /**
     * {@return the samples of a decoded frame}
     */
    public int frameSize() {
        return bandwidthTrack >= 0 ? 2 * layout.frameSize : layout.frameSize;
    }

    /**
     * {@return the number of elementary streams of the object, the access units of a frame}
     */
    public int streams() {
        return streams;
    }

    /**
     * {@return the number of layers decoded: the base layer, the bitrate scalable layers and the bandwidth scalable
     * layer}
     */
    public int layers() {
        return 1 + bitrateLayers + (bandwidthTrack >= 0 ? 1 : 0);
    }

    /**
     * {@return the source coder frames one access unit of each stream carries, more than 1 when the error
     * protection tool concatenates frames}
     */
    public int framesPerUnit() {
        return protection == null ? 1 : base.errorProtection().concatenatedFrames();
    }

    /**
     * Decodes the access units of one frame of each elementary stream, or of one EP frame with the error protection
     * tool.
     *
     * @param data the access units, in stream order; fewer than {@link #streams()} decode the layers they hold up to
     * the first missing one
     * @param lengths the length of each access unit
     * @param count the number of access units
     * @param out the speech of each frame, {@link #frameSize()} samples per frame back to back, scaled so that 16-bit
     * full scale spans {@code [-1, 1]}
     * @return the number of frames decoded
     * @throws MediaException.Malformed if the error protection tool rejects an access unit
     */
    public int decode(byte[][] data, int[] lengths, int count, float[] out) {
        if (protection == null) {
            decodeFrame(data, lengths, Math.min(count, streams), out, 0);
            return 1;
        }
        var available = Math.min(count, streams);
        for (var i = 0; i < available; i++) {
            protection[i].decode(data[i], 0, lengths[i]);
        }
        var frames = protection[0].frames();
        for (var f = 0; f < frames; f++) {
            var intact = 0;
            while (intact < available && protection[intact].intact(f)) {
                intact++;
            }
            var unitCount = 0;
            for (var i = 0; i < intact; i++) {
                var stream = protection[i];
                if (base.epConfig() == 3) {
                    var classes = stream.classCount();
                    var expected = i == 0 ? CelpFrameReader.CATEGORIES : 1;
                    for (var c = 0; c < expected; c++) {
                        if (c < classes) {
                            units[unitCount] = stream.classData(f, c);
                            sizes[unitCount] = (stream.classBits(f, c) + 7) >>> 3;
                        } else {
                            units[unitCount] = null;
                            sizes[unitCount] = 0;
                        }
                        unitCount++;
                    }
                } else {
                    var bytes = (int) Math.min((stream.frameBits(f) + 7) >>> 3, Integer.MAX_VALUE - 8);
                    var target = units[unitCount] == null || units[unitCount].length < bytes
                            ? new byte[Math.max(bytes, 1)] : units[unitCount];
                    sizes[unitCount] = stream.concatenate(f, target);
                    units[unitCount] = target;
                    unitCount++;
                }
            }
            if (intact == 0) {
                Arrays.fill(out, f * frameSize(), (f + 1) * frameSize(), 0);
                continue;
            }
            decodeFrame(units, sizes, unitCount, out, f * frameSize());
        }
        return frames;
    }

    /**
     * Decodes one frame from the units of its streams.
     */
    private void decodeFrame(byte[][] data, int[] lengths, int count, float[] out, int offset) {
        var baseUnits = base.epConfig() == 3 ? CelpFrameReader.CATEGORIES : protection != null ? 1 : baseStreams;
        if (count < baseUnits || lengths[0] == 0 || data[0] == null) {
            Arrays.fill(out, offset, offset + frameSize(), 0);
            return;
        }
        for (var i = 0; i < count; i++) {
            var bytes = data[i];
            readers[i].reset(bytes == null ? NO_DATA : bytes, 0, bytes == null ? 0 : lengths[i]);
        }
        if (!base.errorResilient()) {
            reader.readBase(readers[0], frame);
        } else {
            var first = readers[0];
            reader.readVoiceActivity(first, frame);
            if (frame.txFlag == 1) {
                for (var c = 0; c < CelpFrameReader.CATEGORIES; c++) {
                    reader.readCategory(c, baseUnits == 1 ? first : readers[c], frame);
                }
            } else {
                reader.readSid(first, frame);
            }
        }
        var layers = 0;
        for (var layer = 0; layer < bitrateLayers; layer++) {
            var track = bitrateTracks[layer] - baseStreams + baseUnits;
            if (track >= count) {
                break;
            }
            if (frame.txFlag == 1) {
                reader.readBitrateLayer(layer, readers[track], frame);
            }
            layers++;
        }
        var wideband = false;
        if (bandwidthTrack >= 0 && layers == bitrateLayers) {
            var track = bandwidthTrack - baseStreams + baseUnits;
            if (track < count) {
                if (frame.txFlag == 1) {
                    reader.readBandwidthLayer(readers[track], frame);
                } else if (frame.txFlag == 2) {
                    reader.readBandwidthLsp(readers[track], frame, true);
                }
                wideband = true;
            }
        }
        core.decode(frame, layers, wideband, speech);
        var samples = frameSize();
        if (bandwidthTrack >= 0 && !wideband) {
            // the narrowband speech of a frame without its bandwidth layer, repeated to the output rate
            for (var i = samples - 1; i >= 0; i--) {
                out[offset + i] = (float) (speech[i >> 1] / 32768.0);
            }
            return;
        }
        for (var i = 0; i < samples; i++) {
            out[offset + i] = (float) (speech[i] / 32768.0);
        }
    }
}
