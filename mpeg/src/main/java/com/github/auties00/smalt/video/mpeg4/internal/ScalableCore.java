package com.github.auties00.smalt.video.mpeg4.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.internal.codec.ConsumerFailure;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.mpeg4.internal.object.ObjectStream;
import com.github.auties00.smalt.video.mpeg4.internal.object.ScalableStream;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Decodes the base and enhancement layer tracks of a scalable MPEG-4 Part 2 object for {@code Mpeg4Decoder}.
 *
 * <p>Each track carries one layer: its configuration or its first packets hold the layer's video object layer
 * header, its packets the layer's VOPs. Packets are told apart by their track id. Frames take the time of their VOP,
 * in the base track's time base.
 *
 * <p>Instances are not thread-safe.
 */
public final class ScalableCore {
    private final VideoTrack baseTrack;
    private final VideoTrack enhancementTrack;
    private final MediaLimits limits;
    private final boolean highBitDepth;
    private final Rational timeBase;
    private final ParallelBands.Operation operation = new ParallelBands.Operation();
    private final ObjectStream.Sink sink = this::emit;
    private final Layer base = new Layer();
    private final Layer enhancement = new Layer();
    private ScalableStream stream;
    private ConsumerFailure<VideoFrame> output;
    private boolean closed;

    /**
     * The headers of one layer as they arrive.
     */
    private static final class Layer {
        int profileAndLevel;
        int visualVerid = 1;
        VideoObjectLayer signal;
        byte[] header;
        VideoObjectLayer parsed;
    }

    /**
     * Constructs the core of a scalable decoder.
     *
     * @param base the base layer track
     * @param enhancement the enhancement layer track
     * @param limits the resource bounds checked against every picture size
     * @param highBitDepth whether pictures of more than 8 bits keep their depth
     * @throws MediaException.Malformed if a track's configuration is corrupt
     * @throws MediaException.Unsupported if a configuration uses a tool the decoder lacks
     */
    public ScalableCore(VideoTrack base, VideoTrack enhancement, MediaLimits limits, boolean highBitDepth) {
        this.baseTrack = base;
        this.enhancementTrack = enhancement;
        this.limits = limits;
        this.highBitDepth = highBitDepth;
        this.timeBase = base.timeBase();
        base.codecConfig().ifPresent(config -> scan(this.base, config, false));
        enhancement.codecConfig().ifPresent(config -> scan(this.enhancement, config, true));
    }

    /**
     * Decodes one packet of either layer.
     *
     * @param packet a packet of the base or the enhancement track
     * @param output the consumer of the frames that became due
     */
    public void decode(Packet packet, Consumer<VideoFrame> output) {
        try (var _ = ParallelBands.operation(operation)) {
            Objects.requireNonNull(packet, "packet");
            Objects.requireNonNull(output, "output");
            ensureOpen();
            var enhancementLayer = packet.trackId() == enhancementTrack.id() && enhancementTrack.id() != baseTrack.id();
            if (!enhancementLayer && packet.trackId() != baseTrack.id()) {
                throw new IllegalArgumentException("packet of track " + packet.trackId()
                        + " belongs to neither layer");
            }
            var handoff = ConsumerFailure.wrap(output);
            this.output = handoff;
            try {
                var data = packet.data();
                scan(enhancementLayer ? enhancement : base, data, enhancementLayer);
            } catch (RuntimeException exception) {
                throw handoff.translate("MPEG-4 packet", exception);
            } finally {
                this.output = null;
            }
        }
    }

    /**
     * Decodes the VOPs still queued in both layers.
     *
     * @param output the consumer
     */
    public void flush(Consumer<VideoFrame> output) {
        try (var _ = ParallelBands.operation(operation)) {
            Objects.requireNonNull(output, "output");
            ensureOpen();
            var handoff = ConsumerFailure.wrap(output);
            this.output = handoff;
            try {
                if (stream != null) {
                    stream.flush(sink);
                }
            } catch (RuntimeException exception) {
                throw handoff.translate("MPEG-4 stream", exception);
            } finally {
                this.output = null;
            }
        }
    }

    /**
     * Marks the decoder closed.
     */
    public void close() {
        closed = true;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("decoder is closed");
        }
    }

    /**
     * Splits bytes at their start codes: headers update the layer, groups of VOP and VOPs go to the decoder.
     */
    private void scan(Layer layer, byte[] data, boolean enhancementLayer) {
        var position = next(data, 0);
        var gov = -1;
        while (position >= 0) {
            var code = data[position + 3] & 0xFF;
            var end = next(data, position + 4);
            var stop = end < 0 ? data.length : end;
            if (code == 0xB0) {
                layer.profileAndLevel = position + 4 < stop ? data[position + 4] & 0xFF : 0;
            } else if (code == 0xB5) {
                readVisualObject(layer, data, position + 4, stop - position - 4);
            } else if (code >= 0x20 && code <= 0x2F) {
                var header = Arrays.copyOfRange(data, position, stop);
                if (layer.header == null) {
                    var reader = new BitReader();
                    reader.load(data, position + 4, stop - position - 4);
                    layer.parsed = VideoObjectLayer.parse(reader, layer.profileAndLevel, layer.visualVerid, limits,
                            layer.signal);
                    layer.header = header;
                } else if (!Arrays.equals(header, layer.header)) {
                    throw new MediaException.Unsupported("mpeg-4 scalability", "a layer of a scalable object "
                            + "changed its video object layer header");
                }
            } else if (code == 0xB3) {
                gov = position;
            } else if (code == 0xB6) {
                var from = gov >= 0 ? gov : position;
                gov = -1;
                if (start()) {
                    stream.decode(enhancementLayer, data, from, stop - from, sink);
                } else {
                    throw new MediaException.Malformed("MPEG-4 VOP before the video object layer headers of both "
                            + "layers of a scalable object");
                }
            }
            position = end;
        }
    }

    /**
     * Builds the decoder once the headers of both layers are known.
     *
     * @return whether the decoder exists
     */
    private boolean start() {
        if (stream != null) {
            return true;
        }
        if (base.header == null || enhancement.header == null) {
            return false;
        }
        ColorInfo color = base.parsed.color();
        stream = new ScalableStream(base.header, enhancement.header, baseTrack.width(), baseTrack.height(),
                enhancementTrack.width(), enhancementTrack.height(), color, highBitDepth, limits);
        return true;
    }

    private static void readVisualObject(Layer layer, byte[] data, int offset, int length) {
        var reader = new BitReader();
        reader.load(data, offset, length);
        layer.visualVerid = 1;
        if (reader.readFlag()) {
            layer.visualVerid = reader.read(4);
            reader.skip(3);
        }
        var type = reader.read(4);
        layer.signal = null;
        if ((type == 1 || type == 2) && reader.readFlag()) {
            var holder = new VideoObjectLayer();
            reader.skip(3);
            holder.fullRange = reader.readFlag();
            if (reader.readFlag()) {
                holder.colorPrimaries = reader.read(8);
                holder.transferCharacteristics = reader.read(8);
                holder.matrixCoefficients = reader.read(8);
            }
            if (!reader.overrun()) {
                layer.signal = holder;
            }
        }
    }

    private void emit(Picture picture, long time, boolean keyframe) {
        var consumer = output;
        if (consumer == null) {
            return;
        }
        long pts;
        try {
            pts = new Rational(time, stream.timeResolution()).rescale(1, timeBase);
        } catch (ArithmeticException | IllegalArgumentException exception) {
            pts = Packet.NO_TIMESTAMP;
        }
        try {
            consumer.accept(new VideoFrame(picture, pts, Track.UNKNOWN_DURATION, keyframe));
        } catch (RuntimeException exception) {
            output = null;
            throw exception;
        }
    }

    private static int next(byte[] data, int from) {
        for (var i = from; i + 3 < data.length; i++) {
            if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) {
                return i;
            }
        }
        return -1;
    }
}
