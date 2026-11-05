package com.github.auties00.smalt.image.heic.internal;

import com.github.auties00.smalt.image.AnimationWriter;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.heic.HeicEncoderOptions;
import com.github.auties00.smalt.internal.heif.HeifProperty;
import com.github.auties00.smalt.internal.heif.HeifTrackWriter;
import com.github.auties00.smalt.internal.heif.HeifWriter;
import com.github.auties00.smalt.internal.io.ByteWriter;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.hevc.encoder.HevcEncoder;
import com.github.auties00.smalt.video.hevc.encoder.HevcEncoderOptions;
import com.github.auties00.smalt.video.hevc.encoder.HevcPacketFormat;
import com.github.auties00.smalt.video.hevc.encoder.HevcProfile;
import com.github.auties00.smalt.video.hevc.encoder.HevcRateControl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Writes an animated HEIC file: an HEVC image sequence ({@code msf1}) with an alpha sequence for transparent
 * animations, and the first frame as the primary image item for readers of still images.
 *
 * <p>The color and alpha sequences are coded by {@link HevcEncoder} at constant quality in the Main or Main 10
 * profile. With {@link HeicEncoderOptions#interFrames()} they hold P and B pictures between IDR pictures, so their
 * samples are stored in decoding order with composition offsets ({@code ctts}), only the IDR pictures are sync
 * samples, and an edit starts the presentation at the first frame; otherwise every frame is an IDR picture. Every
 * sample lasts its frame's duration, also when the samples are reordered.
 * Frames are coded as they arrive and their samples kept in memory; {@link #finish()} lays out the file and writes
 * it to the sink. Frame durations are kept to the microsecond: the sequence's timescale is one million. Frames
 * that report alpha but are opaque, and the frames before the first transparent one, have an opaque alpha
 * picture. A canvas of odd width or height is coded one sample larger, and a clean aperture ({@code clap}) in the
 * sample entry and on the primary item crops it back. Instances are not thread-safe.
 */
public final class HeicAnimationWriter implements AnimationWriter {
    /**
     * The movie timescale, microseconds.
     */
    private static final long TIMESCALE = 1_000_000L;

    /**
     * The most frames between two IDR pictures when frames predict from others.
     */
    private static final int KEYFRAME_INTERVAL = 60;

    /**
     * The longest animation, in microseconds: the track and edit durations are 32-bit fields of the timescale,
     * the all-ones value meaning an indefinite duration.
     */
    private static final long MAX_DURATION = 0xFFFFFFFEL;

    private final int width;

    private final int height;

    private final HeicEncoderOptions options;

    private final MediaSink sink;

    /**
     * The coded bit depth.
     */
    private final int depth;

    /**
     * The encoder settings with the coded bit depth, which prepare each frame.
     */
    private final HeicEncoderOptions frameOptions;

    /**
     * The HEVC settings of the alpha sequence.
     */
    private final HevcEncoderOptions alphaSettings;

    /**
     * The encoder of the color sequence, {@code null} once finished or closed.
     */
    private HevcEncoder color;

    /**
     * The encoder of the alpha sequence, created at the first transparent frame, {@code null} before.
     */
    private HevcEncoder alpha;

    /**
     * The color samples, in decoding order.
     */
    private final List<Packet> colorPackets = new ArrayList<>();

    /**
     * The alpha samples, in decoding order.
     */
    private final List<Packet> alphaPackets = new ArrayList<>();

    /**
     * The presentation time of each frame, in microseconds.
     */
    private final List<Long> times = new ArrayList<>();

    /**
     * The duration of each frame, in microseconds.
     */
    private final List<Long> durations = new ArrayList<>();

    /**
     * The opaque alpha picture, created on first use.
     */
    private Yuv16Picture opaque;

    /**
     * The color description of the frames.
     */
    private ColorInfo frameColor;

    /**
     * The presentation time of the next frame, in microseconds.
     */
    private long pts;

    /**
     * Whether {@link #finish()} or {@link #close()} was called.
     */
    private boolean done;

    /**
     * Constructs a writer.
     *
     * @param width the canvas width
     * @param height the canvas height
     * @param options the encoder settings, including the loop count
     * @param sink the output
     * @throws IllegalArgumentException if the canvas exceeds the HEVC picture size
     */
    public HeicAnimationWriter(int width, int height, HeicEncoderOptions options, MediaSink sink) {
        this.width = width;
        this.height = height;
        this.options = options;
        this.sink = sink;
        this.depth = options.bitDepth().orElse(8);
        this.frameOptions = options.toBuilder().bitDepth(depth).build();
        this.color = new HevcEncoder(width, height, sequenceOptions(options.quality(), options.color()));
        this.alphaSettings = sequenceOptions(options.alphaQuality(), HeicStillEncoder.alphaColor());
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    /**
     * Returns the HEVC settings of a sequence.
     *
     * @param quality the quality, from 0 to 100
     * @param color the color description the stream signals
     * @return the settings
     */
    private HevcEncoderOptions sequenceOptions(int quality, ColorInfo color) {
        var builder = HevcEncoderOptions.builder().profile(depth == 8 ? HevcProfile.MAIN : HevcProfile.MAIN_10)
                .bitDepth(depth).rateControl(HevcRateControl.CONSTANT_QUALITY)
                .quality(HeicStillEncoder.quantizer(quality)).speed(options.speed()).color(color)
                .threads(HeicStillEncoder.threads(options)).packetFormat(HevcPacketFormat.LENGTH_PREFIXED)
                .limits(options.limits()).timeBase(Rational.MICROSECONDS);
        if (options.interFrames()) {
            builder.keyframeInterval(KEYFRAME_INTERVAL);
        } else {
            builder.keyframeInterval(1).bFrames(0);
        }
        return builder.build();
    }

    @Override
    public void write(RgbaPicture picture, long durationMicros) {
        Objects.requireNonNull(picture, "picture");
        if (done) {
            throw new IllegalStateException("the writer is finished");
        }
        if (picture.width() != width || picture.height() != height) {
            throw new IllegalArgumentException("frame is " + picture.width() + "x" + picture.height()
                    + ", canvas is " + width + "x" + height);
        }
        if (durationMicros <= 0) {
            throw new IllegalArgumentException("duration " + durationMicros + " is not positive");
        }
        if (durationMicros > MAX_DURATION - pts) {
            throw new IllegalArgumentException("the animation would last longer than " + MAX_DURATION
                    + " us, the longest the 32-bit durations of its tracks store");
        }
        var image = HeicStillEncoder.prepare(picture, frameOptions);
        if (frameColor == null) {
            frameColor = image.color();
        }
        color.encode(new VideoFrame(HeicStillEncoder.picture(image, false), pts, durationMicros, false),
                colorPackets::add);
        if (image.alpha() != null && alpha == null) {
            alpha = new HevcEncoder(width, height, alphaSettings);
            for (var i = 0; i < times.size(); i++) {
                alpha.encode(new VideoFrame(opaque(), times.get(i), durations.get(i), false), alphaPackets::add);
            }
        }
        if (alpha != null) {
            var plane = image.alpha() != null ? HeicStillEncoder.picture(image, true) : opaque();
            alpha.encode(new VideoFrame(plane, pts, durationMicros, false), alphaPackets::add);
        }
        times.add(pts);
        durations.add(durationMicros);
        pts += durationMicros;
    }

    /**
     * Returns the opaque alpha picture.
     *
     * @return the picture
     */
    private Yuv16Picture opaque() {
        if (opaque == null) {
            var picture = Yuv16Picture.allocate(width, height, ChromaFormat.YUV420, depth, false,
                    HeicStillEncoder.alphaColor());
            Arrays.fill(picture.y(), (short) ((1 << depth) - 1));
            opaque = picture;
        }
        return opaque;
    }

    @Override
    public void finish() {
        if (done) {
            throw new IllegalStateException("the writer is finished");
        }
        if (times.isEmpty()) {
            throw new IllegalStateException("an animation needs at least one frame");
        }
        done = true;
        color.flush(colorPackets::add);
        var colorConfig = color.codecConfig().orElseThrow();
        color.close();
        color = null;
        byte[] alphaConfig = null;
        if (alpha != null) {
            alpha.flush(alphaPackets::add);
            alphaConfig = alpha.codecConfig().orElseThrow();
            alpha.close();
            alpha = null;
        }
        sink.write(build(colorConfig, alphaConfig));
    }

    /**
     * Lays out the file.
     *
     * @param colorConfig the {@code hvcC} record of the color sequence
     * @param alphaConfig the {@code hvcC} record of the alpha sequence, or {@code null} without alpha
     * @return the file
     */
    private byte[] build(byte[] colorConfig, byte[] alphaConfig) {
        var samples = new ByteWriter(4096);
        for (var packet : colorPackets) {
            samples.bytes(packet.data());
        }
        var colorEnd = samples.size();
        for (var packet : alphaPackets) {
            samples.bytes(packet.data());
        }
        var data = samples.toByteArray();
        var tenBit = depth != 8;
        var image = tenBit ? "heix" : "heic";
        var brands = List.of(image, "mif1", "miaf", "msf1", tenBit ? "hevx" : "hevc", "iso8");
        var writer = new HeifWriter("heic", image, brands);
        var codedWidth = (width + 1) & ~1;
        var codedHeight = (height + 1) & ~1;
        var props = new ArrayList<HeifWriter.Property>();
        props.addAll(HeifWriter.colorProperties(frameColor, options.colorProfile().orElse(null)));
        props.addAll(HeifWriter.orientation(options.orientation()));
        var colorId = writer.addSampleItem("hvc1", 0, colorPackets.getFirst().data().length,
                HeicStillEncoder.imageProperties(colorConfig, codedWidth, codedHeight, props, width, height));
        writer.primary(colorId);
        if (alphaConfig != null) {
            var alphaId = writer.addSampleItem("hvc1", colorEnd, alphaPackets.getFirst().data().length,
                    HeicStillEncoder.imageProperties(alphaConfig, codedWidth, codedHeight,
                            List.of(HeifWriter.auxC(HeifProperty.AuxiliaryType.HEVC_ALPHA_URN)), width, height));
            writer.reference("auxl", alphaId, colorId);
            if (options.premultipliedAlpha()) {
                writer.reference("prem", colorId, alphaId);
            }
        }
        writer.addMetadata(colorId, options.exif().orElse(null), options.xmp().orElse(null));
        long duration = 0;
        for (var d : durations) {
            duration += d;
        }
        var total = duration;
        var premultipliedBy = alphaConfig != null && options.premultipliedAlpha() ? 2 : 0;
        var colorTrack = track(1, 0, premultipliedBy, "pict", 0, colorPackets,
                out -> sampleEntry(out, colorConfig, false, codedWidth, codedHeight));
        var tracks = new ArrayList<HeifTrackWriter.Track>();
        tracks.add(colorTrack);
        if (alphaConfig != null) {
            var config = alphaConfig;
            tracks.add(track(2, 1, 0, "auxv", colorEnd, alphaPackets,
                    out -> sampleEntry(out, config, true, codedWidth, codedHeight)));
        }
        writer.movie(data, base -> HeifTrackWriter.moov(base, width, height, TIMESCALE, total, options.loopCount(),
                tracks));
        return writer.write();
    }

    /**
     * Returns the description of a track from its samples.
     *
     * <p>Each sample lasts its frame's duration, so the decoding time of a sample is the sum of the durations of
     * the samples decoded before it, and a sample's duration is also its presentation duration. The composition
     * offsets are the presentation times minus those decoding times, shifted by the one constant that makes them
     * all non-negative, and the edit starts the presentation at the first frame's composition time.
     *
     * @param id the track identifier
     * @param reference the track an auxiliary track describes, 0 for none
     * @param premultipliedBy the alpha track the color of this track is premultiplied by, 0 for none
     * @param handler the handler type
     * @param chunkOffset the offset of the track's samples within the samples
     * @param packets the samples, in decoding order
     * @param sampleEntry writes the sample entry
     * @return the track
     */
    private static HeifTrackWriter.Track track(int id, int reference, int premultipliedBy, String handler,
                                               long chunkOffset, List<Packet> packets,
                                               Consumer<ByteWriter> sampleEntry) {
        var count = packets.size();
        var sizes = new int[count];
        var deltas = new long[count];
        var offsets = new int[count];
        var sync = new boolean[count];
        var allSync = true;
        var reordered = false;
        long decode = 0;
        long shift = 0;
        long earliest = Long.MAX_VALUE;
        for (var i = 0; i < count; i++) {
            var packet = packets.get(i);
            sizes[i] = packet.data().length;
            deltas[i] = packet.duration();
            sync[i] = packet.keyframe();
            allSync &= sync[i];
            shift = Math.max(shift, decode - packet.pts());
            earliest = Math.min(earliest, packet.pts());
            decode += packet.duration();
        }
        decode = 0;
        for (var i = 0; i < count; i++) {
            var packet = packets.get(i);
            offsets[i] = Math.toIntExact(packet.pts() + shift - decode);
            reordered |= offsets[i] != 0;
            decode += packet.duration();
        }
        return new HeifTrackWriter.Track(id, reference, premultipliedBy, handler, chunkOffset, sizes, deltas,
                reordered ? offsets : null, allSync ? null : sync, earliest + shift, sampleEntry);
    }

    /**
     * Writes the {@code hvc1} sample entry of a track: the {@code hvcC} and {@code ccst} boxes, then the
     * {@code auxi} box of the alpha track or the color boxes of the color track (the {@code nclx} and ICC
     * {@code colr} boxes and, for high dynamic range content, {@code clli} and {@code mdcv}), then the clean
     * aperture of an odd canvas.
     *
     * @param out the output
     * @param hvcC the codec configuration record
     * @param alphaTrack whether the track is the alpha track
     * @param codedWidth the coded width
     * @param codedHeight the coded height
     */
    private void sampleEntry(ByteWriter out, byte[] hvcC, boolean alphaTrack, int codedWidth, int codedHeight) {
        HeifTrackWriter.visualSampleEntry(out, "hvc1", codedWidth, codedHeight, entry -> {
            HeifWriter.box(entry, "hvcC", hvcC);
            if (options.interFrames()) {
                HeifTrackWriter.ccst(entry, false, true, 15);
            } else {
                HeifTrackWriter.ccst(entry, true, true, 0);
            }
            if (alphaTrack) {
                HeifTrackWriter.auxi(entry, HeifProperty.AuxiliaryType.HEVC_ALPHA_URN);
            } else {
                for (var property : HeifWriter.colorProperties(frameColor, options.colorProfile().orElse(null))) {
                    entry.bytes(property.box());
                }
            }
            if (codedWidth != width || codedHeight != height) {
                entry.bytes(HeifWriter.clap(width, height, codedWidth, codedHeight).box());
            }
        });
    }

    @Override
    public void close() {
        done = true;
        if (color != null) {
            color.close();
            color = null;
        }
        if (alpha != null) {
            alpha.close();
            alpha = null;
        }
    }
}
