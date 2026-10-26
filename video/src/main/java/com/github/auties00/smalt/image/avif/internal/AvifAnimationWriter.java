package com.github.auties00.smalt.image.avif.internal;

import com.github.auties00.smalt.image.AnimationWriter;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.avif.AvifEncoderOptions;
import com.github.auties00.smalt.internal.heif.HeifProperty;
import com.github.auties00.smalt.internal.io.ByteWriter;
import com.github.auties00.smalt.internal.io.FourCc;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.av1.Av1Encoder;
import com.github.auties00.smalt.video.av1.Av1EncoderOptions;
import com.github.auties00.smalt.video.av1.Av1EncoderSpeed;
import com.github.auties00.smalt.video.av1.internal.Obu;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Writes an animated AVIF file: an AV1 image sequence ({@code avis}), with an alpha sequence for
 * transparent animations, and the first frame as the primary image item for readers of still
 * images.
 *
 * <p>When the options allow predicted frames ({@link AvifEncoderOptions#interFrames()}) and the
 * color is lossy 8-bit 4:2:0, the color sequence is coded by the good-quality AV1 encoder at the
 * options' quality: its first frame is a key frame and later frames predict from earlier and later
 * ones, so only the key frames are sync samples. Otherwise every color frame is a key frame
 * coded with the still picture search. Alpha frames are always key frames.
 *
 * <p>Frames are coded as they arrive and kept in memory; {@link #finish()} lays out the file and
 * writes it to the sink. Frame durations are kept to the microsecond: the sequence's timescale is
 * one million. Frames that report alpha but are opaque, and frames before the first transparent
 * one, share one coded opaque alpha frame.
 *
 * @implNote This implementation lays out the tracks and sample tables as libavif's
 * {@code avifEncoderFinish} ({@code src/write.c}, BSD-2) does for image sequences: a
 * {@code pict} color track and an
 * {@code auxv} alpha track referring to it with {@code auxl}, {@code av01} sample entries
 * with {@code av1C} and {@code ccst} (and {@code auxi} for alpha), every sample in one
 * chunk, a sync sample table when not every sample is a key frame, and a repeating
 * edit list for the loop count.
 */
public final class AvifAnimationWriter implements AnimationWriter {
    /**
     * The movie timescale, microseconds.
     */
    private static final long TIMESCALE = 1_000_000L;

    /**
     * The canvas width.
     */
    private final int width;

    /**
     * The canvas height.
     */
    private final int height;

    /**
     * The options.
     */
    private final AvifEncoderOptions options;

    /**
     * The output.
     */
    private final MediaSink sink;

    /**
     * The coded color frames.
     */
    private final List<AvifStillEncoder.Coded> color = new ArrayList<>();

    /**
     * The coded alpha frames, {@code null} entries standing for an opaque frame.
     */
    private final List<AvifStillEncoder.Coded> alpha = new ArrayList<>();

    /**
     * The frame durations in microseconds.
     */
    private final List<Long> durations = new ArrayList<>();

    /**
     * The coded opaque alpha frame, created on first use.
     */
    private AvifStillEncoder.Coded opaque;

    /**
     * The prepared first frame, for its color description and format.
     */
    private EncodeImage first;

    /**
     * Whether any frame is transparent.
     */
    private boolean transparent;

    /**
     * Whether {@link #finish()} or {@link #close()} was called.
     */
    private boolean done;

    /**
     * The video encoder of predicted color frames, or {@code null} when every color frame is a
     * key frame.
     */
    private Av1Encoder video;

    /**
     * Whether each color sample of predicted frames is a key frame.
     */
    private final List<Boolean> sync = new ArrayList<>();

    /**
     * The timestamp of the next color frame, in microseconds.
     */
    private long pts;

    /**
     * Constructs a writer.
     *
     * @param width the canvas width
     * @param height the canvas height
     * @param options the encoder settings, including the loop count
     * @param sink the output
     */
    public AvifAnimationWriter(int width, int height, AvifEncoderOptions options, MediaSink sink) {
        this.width = width;
        this.height = height;
        this.options = options;
        this.sink = sink;
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
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
        var image = EncodeImage.of(picture, options.chromaFormat().orElse(null), options.bitDepth().orElse(0),
                options.color(), options.isLossless(), options.premultipliedAlpha());
        if (first == null) {
            first = image;
            if (options.interFrames() && !options.isLossless() && image.format() == ChromaFormat.YUV420
                    && image.bitDepth() == 8) {
                video = new Av1Encoder(width, height, videoOptions(image, durationMicros));
            }
        }
        if (video != null) {
            video.encode(new VideoFrame(yuv(image), pts, durationMicros, false), this::colorPacket);
            pts += durationMicros;
        } else {
            color.add(AvifStillEncoder.code(image, options.quality(), options, true));
        }
        if (image.alpha() != null) {
            transparent = true;
            alpha.add(AvifStillEncoder.code(image.alphaImage(), options.alphaQuality(), options, true));
        } else {
            alpha.add(null);
        }
        durations.add(durationMicros);
    }

    /**
     * Returns the good-quality AV1 settings of the color sequence: the options' quality, a speed
     * matching the still picture speed, the automatic tiles of the picture size, the options'
     * threads and limits, microsecond timestamps.
     *
     * @param image the first frame
     * @param durationMicros the first frame's duration, for the nominal frame rate
     * @return the settings
     */
    private Av1EncoderOptions videoOptions(EncodeImage image, long durationMicros) {
        var s = options.speed();
        var speed = s <= 2 ? Av1EncoderSpeed.SLOWEST : s == 3 ? Av1EncoderSpeed.SLOWER : s <= 5 ? Av1EncoderSpeed.SLOW
                : s == 6 ? Av1EncoderSpeed.BALANCED : Av1EncoderSpeed.FAST;
        var tiles = AvifStillEncoder.tiling(width, height);
        var threads = options.threads() > 0 ? options.threads() : ParallelBands.THREADS;
        var rate = Math.clamp(1_000_000.0 / durationMicros, 0.1, 1000.0);
        return Av1EncoderOptions.ofQuality(options.quality()).toBuilder().speed(speed)
                .tileColumnsLog2(options.tileColumnsLog2().orElse(tiles >> 8)).threads(Math.clamp(threads, 1, 64))
                .limits(options.limits()).color(image.color()).timeBase(Rational.MICROSECONDS)
                .frameRate(new Rational((long) Math.round(rate * 1000), 1000)).build();
    }

    /**
     * Converts a prepared 8-bit 4:2:0 image to a picture.
     *
     * @param image the prepared image, 8-bit 4:2:0
     * @return the picture
     */
    private static YuvPicture yuv(EncodeImage image) {
        var out = YuvPicture.allocate(image.width(), image.height(), ChromaFormat.YUV420, false, image.color());
        var planes = new byte[][]{out.y(), out.u(), out.v()};
        for (var pl = 0; pl < 3; pl++) {
            var src = image.planes()[pl];
            var dst = planes[pl];
            for (var i = 0; i < dst.length; i++) {
                dst[i] = (byte) src[i];
            }
        }
        return out;
    }

    /**
     * Keeps one packet of the color sequence as a sample, without its temporal delimiter.
     *
     * @param packet the coded temporal unit of one color frame
     */
    private void colorPacket(Packet packet) {
        var data = packet.data();
        if (data.length >= 2 && ((data[0] >> 3) & 15) == Obu.OBU_TD) {
            data = java.util.Arrays.copyOfRange(data, 2, data.length);
        }
        color.add(new AvifStillEncoder.Coded(data, null, 0, 0));
        sync.add(packet.keyframe());
    }

    /**
     * Returns the coded opaque alpha frame.
     *
     * @return the frame
     */
    private AvifStillEncoder.Coded opaque() {
        if (opaque == null) {
            var depth = first.bitDepth();
            var plane = new short[width * height];
            java.util.Arrays.fill(plane, (short) ((1 << depth) - 1));
            var image = new EncodeImage(width, height, ChromaFormat.YUV444, depth, first.color(),
                    new short[][]{plane, plane, plane}, plane);
            opaque = AvifStillEncoder.code(image.alphaImage(), options.alphaQuality(), options, true);
        }
        return opaque;
    }

    @Override
    public void finish() {
        if (done) {
            return;
        }
        done = true;
        if (video != null) {
            video.flush(this::colorPacket);
            var config = video.codecConfig().orElseThrow();
            var profile = (config[1] & 0xFF) >> 5;
            var level = config[1] & 0x1F;
            color.replaceAll(c -> new AvifStillEncoder.Coded(c.data(), config, profile, level));
            video.close();
        }
        if (color.isEmpty()) {
            throw new IllegalStateException("an animation needs at least one frame");
        }
        sink.write(build());
    }

    /**
     * Lays out the file.
     *
     * @return the file
     */
    private byte[] build() {
        var samples = new ByteWriter(4096);
        var colorSizes = new int[color.size()];
        for (var i = 0; i < color.size(); i++) {
            samples.bytes(color.get(i).data());
            colorSizes[i] = color.get(i).data().length;
        }
        var colorEnd = samples.size();
        int[] alphaSizes = null;
        if (transparent) {
            alphaSizes = new int[alpha.size()];
            for (var i = 0; i < alpha.size(); i++) {
                var coded = alpha.get(i) != null ? alpha.get(i) : opaque();
                samples.bytes(coded.data());
                alphaSizes[i] = coded.data().length;
            }
        }
        var data = samples.toByteArray();
        var alphaList = new ArrayList<AvifStillEncoder.Coded>();
        if (transparent) {
            alphaList.add(alpha.getFirst() != null ? alpha.getFirst() : opaque());
        }
        var brands = new ArrayList<>(List.of("avif", "avis", "msf1", "iso8", "mif1", "miaf"));
        var profileBrands = AvifStillEncoder.brands(List.of(color.getFirst()), alphaList);
        for (var brand : profileBrands) {
            if (!brands.contains(brand)) {
                brands.add(brand);
            }
        }
        var writer = new AvifWriter("avis", brands);
        var depth = first.bitDepth();
        var channels = first.format() == ChromaFormat.MONOCHROME ? 1 : 3;
        var props = new ArrayList<AvifWriter.Property>();
        props.add(AvifWriter.ispe(width, height));
        props.add(AvifWriter.pixi(depth, channels));
        props.add(AvifWriter.av1C(color.getFirst().av1C()));
        props.addAll(AvifStillEncoder.colorProperties(first.color(), options));
        props.addAll(AvifStillEncoder.orientation(options.orientation()));
        var colorId = writer.addSampleItem("av01", 0, colorSizes[0], props.toArray(AvifWriter.Property[]::new));
        writer.primary(colorId);
        if (transparent) {
            var firstAlpha = alphaList.getFirst();
            var alphaId = writer.addSampleItem("av01", colorEnd, alphaSizes[0], AvifWriter.ispe(width, height),
                    AvifWriter.pixi(depth, 1), AvifWriter.av1C(firstAlpha.av1C()),
                    AvifWriter.auxC(HeifProperty.AuxiliaryType.ALPHA_URN));
            writer.reference("auxl", alphaId, colorId);
            if (options.premultipliedAlpha()) {
                writer.reference("prem", colorId, alphaId);
            }
        }
        writer.addMetadata(colorId, options.exif().orElse(null), options.xmp().orElse(null));
        var finalAlphaSizes = alphaSizes;
        var alphaConfig = transparent ? alphaList.getFirst().av1C() : null;
        writer.movie(data, base -> moov(base, colorSizes, colorEnd, finalAlphaSizes, alphaConfig));
        return writer.write();
    }

    /**
     * Builds the {@code moov} box.
     *
     * @param base the file offset of the first sample
     * @param colorSizes the size of each color sample
     * @param alphaOffset the offset of the first alpha sample within the samples
     * @param alphaSizes the size of each alpha sample, or {@code null} without alpha
     * @param alphaConfig the {@code av1C} record of the alpha samples, or {@code null}
     * @return the box
     */
    private byte[] moov(long base, int[] colorSizes, int alphaOffset, int[] alphaSizes, byte[] alphaConfig) {
        long duration = 0;
        for (var d : durations) {
            duration += d;
        }
        var loops = options.loopCount();
        var movieDuration = loops == 0 ? 0xFFFFFFFFL : Math.min(0xFFFFFFFEL, duration * loops);
        var out = new ByteWriter(1024 + 8 * (colorSizes.length + (alphaSizes == null ? 0 : alphaSizes.length)));
        out.begin(FourCc.of("moov"));
        mvhd(out, movieDuration, alphaSizes != null ? 3 : 2);
        trak(out, 1, 0, base, colorSizes, color.getFirst().av1C(), duration, movieDuration, false, syncSamples());
        if (alphaSizes != null) {
            trak(out, 2, 1, base + alphaOffset, alphaSizes, alphaConfig, duration, movieDuration, true, null);
        }
        out.end();
        return out.toByteArray();
    }

    /**
     * Returns the sync flag of each color sample, or {@code null} when every sample is a key
     * frame.
     *
     * @return the flags, or {@code null}
     */
    private boolean[] syncSamples() {
        if (!sync.contains(Boolean.FALSE)) {
            return null;
        }
        var flags = new boolean[sync.size()];
        for (var i = 0; i < flags.length; i++) {
            flags[i] = sync.get(i);
        }
        return flags;
    }

    /**
     * Writes the {@code mvhd} box.
     *
     * @param out the output
     * @param duration the movie duration, in the timescale
     * @param nextTrackId the identifier after the last track's
     */
    private static void mvhd(ByteWriter out, long duration, int nextTrackId) {
        out.beginFull(FourCc.of("mvhd"), 0, 0);
        out.u32be(0);
        out.u32be(0);
        out.u32be(TIMESCALE);
        out.u32be(duration);
        out.u32be(0x00010000);
        out.u16be(0x0100);
        out.u16be(0);
        out.u32be(0);
        out.u32be(0);
        matrix(out);
        out.zeros(24);
        out.u32be(nextTrackId);
        out.end();
    }

    /**
     * Writes the unity transformation matrix.
     *
     * @param out the output
     */
    private static void matrix(ByteWriter out) {
        long[] unity = {0x00010000, 0, 0, 0, 0x00010000, 0, 0, 0, 0x40000000};
        for (var v : unity) {
            out.u32be(v);
        }
    }

    /**
     * Writes a {@code trak} box.
     *
     * @param out the output
     * @param id the track identifier
     * @param reference the track an alpha track describes, 0 for none
     * @param chunkOffset the file offset of the track's single chunk
     * @param sizes the sample sizes
     * @param av1C the codec configuration record
     * @param duration the media duration, in the timescale
     * @param movieDuration the presentation duration, in the timescale
     * @param alphaTrack whether the track is the alpha track
     * @param sync the sync flag of each sample, or {@code null} when every sample is one
     */
    private void trak(ByteWriter out, int id, int reference, long chunkOffset, int[] sizes, byte[] av1C,
                      long duration, long movieDuration, boolean alphaTrack, boolean[] sync) {
        out.begin(FourCc.of("trak"));
        out.beginFull(FourCc.of("tkhd"), 0, 1);
        out.u32be(0);
        out.u32be(0);
        out.u32be(id);
        out.u32be(0);
        out.u32be(movieDuration);
        out.zeros(16);
        matrix(out);
        out.u32be((long) width << 16);
        out.u32be((long) height << 16);
        out.end();
        if (reference != 0) {
            out.begin(FourCc.of("tref"));
            out.begin(FourCc.of("auxl"));
            out.u32be(reference);
            out.end();
            out.end();
        }
        out.begin(FourCc.of("edts"));
        out.beginFull(FourCc.of("elst"), 0, 1);
        out.u32be(1);
        out.u32be(movieDuration);
        out.u32be(0);
        out.u16be(1);
        out.u16be(0);
        out.end();
        out.end();
        out.begin(FourCc.of("mdia"));
        out.beginFull(FourCc.of("mdhd"), 0, 0);
        out.u32be(0);
        out.u32be(0);
        out.u32be(TIMESCALE);
        out.u32be(duration);
        out.u16be(('u' - 0x60) << 10 | ('n' - 0x60) << 5 | ('d' - 0x60));
        out.u16be(0);
        out.end();
        AvifWriter.hdlr(out, alphaTrack ? "auxv" : "pict");
        out.begin(FourCc.of("minf"));
        out.beginFull(FourCc.of("vmhd"), 0, 1);
        out.zeros(8);
        out.end();
        out.begin(FourCc.of("dinf"));
        out.beginFull(FourCc.of("dref"), 0, 0);
        out.u32be(1);
        out.beginFull(FourCc.of("url "), 0, 1);
        out.end();
        out.end();
        out.end();
        stbl(out, chunkOffset, sizes, av1C, alphaTrack, sync);
        out.end();
        out.end();
        out.end();
    }

    /**
     * Writes an {@code stbl} box: the sample entry, the decoding times, the chunk, the sample
     * sizes and, when not every sample is a sync sample, the sync sample table.
     *
     * @param out the output
     * @param chunkOffset the file offset of the single chunk
     * @param sizes the sample sizes
     * @param av1C the codec configuration record
     * @param alphaTrack whether the track is the alpha track
     * @param sync the sync flag of each sample, or {@code null} when every sample is one
     */
    private void stbl(ByteWriter out, long chunkOffset, int[] sizes, byte[] av1C, boolean alphaTrack,
                      boolean[] sync) {
        out.begin(FourCc.of("stbl"));
        out.beginFull(FourCc.of("stsd"), 0, 0);
        out.u32be(1);
        out.begin(FourCc.of("av01"));
        out.zeros(6);
        out.u16be(1);
        out.zeros(16);
        out.u16be(width);
        out.u16be(height);
        out.u32be(0x00480000);
        out.u32be(0x00480000);
        out.u32be(0);
        out.u16be(1);
        out.zeros(32);
        out.u16be(0x0018);
        out.u16be(0xFFFF);
        AvifWriter.av1C(out, av1C);
        out.beginFull(FourCc.of("ccst"), 0, 0);
        out.u32be(0xC0000000L | (15L << 26));
        out.end();
        if (alphaTrack) {
            out.beginFull(FourCc.of("auxi"), 0, 0);
            out.bytes(HeifProperty.AuxiliaryType.ALPHA_URN.getBytes(StandardCharsets.US_ASCII));
            out.u8(0);
            out.end();
        } else {
            var c = first.color();
            AvifWriter.nclx(out, c.primaries().code(), c.transfer().code(), c.matrix().code(),
                    c.range() == ColorRange.FULL);
            options.colorProfile().ifPresent(profile -> {
                out.begin(FourCc.of("colr"));
                out.fourCc("prof");
                out.bytes(profile.toByteArray());
                out.end();
            });
        }
        out.end();
        out.end();
        var runs = new ArrayList<long[]>();
        for (var d : durations) {
            if (!runs.isEmpty() && runs.getLast()[1] == d) {
                runs.getLast()[0]++;
            } else {
                runs.add(new long[]{1, d});
            }
        }
        out.beginFull(FourCc.of("stts"), 0, 0);
        out.u32be(runs.size());
        for (var run : runs) {
            out.u32be(run[0]);
            out.u32be(run[1]);
        }
        out.end();
        out.beginFull(FourCc.of("stsc"), 0, 0);
        out.u32be(1);
        out.u32be(1);
        out.u32be(sizes.length);
        out.u32be(1);
        out.end();
        out.beginFull(FourCc.of("stsz"), 0, 0);
        out.u32be(0);
        out.u32be(sizes.length);
        for (var size : sizes) {
            out.u32be(size);
        }
        out.end();
        if (sync != null) {
            var count = 0;
            for (var s : sync) {
                count += s ? 1 : 0;
            }
            out.beginFull(FourCc.of("stss"), 0, 0);
            out.u32be(count);
            for (var i = 0; i < sync.length; i++) {
                if (sync[i]) {
                    out.u32be(i + 1);
                }
            }
            out.end();
        }
        out.beginFull(FourCc.of("stco"), 0, 0);
        out.u32be(1);
        out.u32be(chunkOffset);
        out.end();
        out.end();
    }

    @Override
    public void close() {
        done = true;
        if (video != null) {
            video.close();
            video = null;
        }
    }
}
