package com.github.auties00.smalt.jit;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.webp.WebpAnimationEncoder;
import com.github.auties00.smalt.image.webp.WebpAnimationEncoderOptions;
import com.github.auties00.smalt.image.webp.WebpDecoder;
import com.github.auties00.smalt.image.webp.WebpEncoder;
import com.github.auties00.smalt.image.webp.WebpEncoderOptions;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoder;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.av1.Av1Decoder;
import com.github.auties00.smalt.video.av1.Av1Encoder;
import com.github.auties00.smalt.video.av1.Av1EncoderOptions;
import com.github.auties00.smalt.video.av1.Av1EncoderSpeed;
import com.github.auties00.smalt.video.h264.H264Decoder;
import com.github.auties00.smalt.video.h264.H264DecoderOptions;
import com.github.auties00.smalt.video.h264.encoder.H264Encoder;
import com.github.auties00.smalt.video.h264.encoder.H264EncoderOptions;
import com.github.auties00.smalt.video.h264.encoder.H264EncoderSpeed;
import com.github.auties00.smalt.video.h264.encoder.H264Profile;
import com.github.auties00.smalt.video.hevc.HevcDecoder;
import com.github.auties00.smalt.video.hevc.HevcDecoderOptions;
import com.github.auties00.smalt.video.vp8.Vp8Decoder;
import com.github.auties00.smalt.video.vp8.Vp8Encoder;
import com.github.auties00.smalt.video.vp8.Vp8EncoderOptions;
import com.github.auties00.smalt.video.vp8.Vp8EncoderSpeed;
import com.github.auties00.smalt.video.vp9.Vp9Decoder;
import com.github.auties00.smalt.video.vp9.Vp9Encoder;
import com.github.auties00.smalt.video.vp9.Vp9EncoderOptions;
import com.github.auties00.smalt.video.vp9.Vp9EncoderSpeed;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * The child process of {@link JitRobustnessTest}: runs media workloads round after round in a
 * fresh JVM until their steady-state heap allocation per frame settles under a bound, or a
 * deadline passes.
 *
 * <p>A Vector API operation the JIT fails to compile runs its allocating Java fallback on every
 * call, so a workload stuck on such a fallback allocates far more per frame than its bound and
 * never settles; a healthy workload settles as soon as its kernels are compiled. Arguments are
 * {@code seconds=N} (the deadline) followed by workload specifications, each a name and
 * {@code key=value} options joined by colons, for example
 * {@code h264enc:speed=BALANCED:threads=2:bound=65536} ({@code rt=true} selects the real-time preset of
 * calls); several workloads run interleaved in one
 * JVM. The process prints one {@code ROUND} line per workload and round, then {@code PASS} or {@code FAIL}, and
 * exits with 0 or 1.
 */
public final class JitWorkload {
    private static final int STABLE_ROUNDS = 3;
    private static final int MIN_ROUNDS = 5;

    private static long sink;

    // Reached reflectively: the tests' module reads only java.base, while this process runs on the
    // class path, where java.management is readable.
    private static final Object THREADS;
    private static final Method ALLOCATED;

    static {
        try {
            THREADS = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null);
            ALLOCATED = Class.forName("com.sun.management.ThreadMXBean").getMethod("getTotalThreadAllocatedBytes");
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private JitWorkload() {
    }

    interface Workload {
        String name();

        long bound();

        int round();
    }

    public static void main(String[] args) throws Exception {
        var deadline = System.nanoTime() + Long.parseLong(args[0].substring("seconds=".length())) * 1_000_000_000L;
        var workloads = new ArrayList<Workload>();
        for (var i = 1; i < args.length; i++) {
            workloads.add(create(args[i]));
        }
        var history = new HashMap<String, List<Long>>();
        for (var round = 0; ; round++) {
            var settled = round >= MIN_ROUNDS;
            for (var workload : workloads) {
                var before = allocated();
                var start = System.nanoTime();
                var frames = workload.round();
                var elapsed = System.nanoTime() - start;
                var perFrame = (allocated() - before) / Math.max(1, frames);
                var values = history.computeIfAbsent(workload.name(), key -> new ArrayList<>());
                values.add(perFrame);
                System.out.printf(Locale.ROOT, "ROUND %s %d %d bytes/frame %.1f ms bound %d%n", workload.name(), round,
                        perFrame, elapsed / 1e6, workload.bound());
                var recent = values.subList(Math.max(0, values.size() - STABLE_ROUNDS), values.size());
                settled &= recent.size() == STABLE_ROUNDS && recent.stream().allMatch(value -> value <= workload.bound());
            }
            if (settled) {
                System.out.println("PASS " + (sink & 1));
                System.exit(0);
            }
            if (System.nanoTime() > deadline) {
                System.out.println("FAIL " + (sink & 1));
                System.exit(1);
            }
        }
    }

    private static long allocated() {
        try {
            return (long) ALLOCATED.invoke(THREADS);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    static Workload create(String spec) throws Exception {
        var parts = spec.split(":");
        var options = new HashMap<String, String>();
        var key = "";
        for (var i = 1; i < parts.length; i++) {
            var equals = parts[i].indexOf('=');
            if (equals > 0 && parts[i].substring(0, equals).chars().allMatch(Character::isLetter)) {
                key = parts[i].substring(0, equals);
                options.put(key, parts[i].substring(equals + 1));
            } else {
                // A colon inside a value, such as the drive of a Windows path.
                options.merge(key, ":" + parts[i], String::concat);
            }
        }
        var bound = Long.parseLong(options.getOrDefault("bound", "65536"));
        var threads = Integer.parseInt(options.getOrDefault("threads", "1"));
        return switch (parts[0]) {
            case "h264enc" -> h264Encode(bound, threads, H264EncoderSpeed.valueOf(options.getOrDefault("speed", "BALANCED")),
                    profile(options.getOrDefault("profile", "main")), options.get("input"),
                    Integer.parseInt(options.getOrDefault("width", "640")), Integer.parseInt(options.getOrDefault("height", "360")),
                    Boolean.parseBoolean(options.getOrDefault("rt", "false")));
            case "h264dec" -> h264Decode(bound, threads, options.get("input"));
            case "hevcdec" -> hevcDecode(bound, threads, Path.of(options.get("input")));
            case "vp8dec" -> vp8Decode(bound, Path.of(options.get("input")));
            case "vp8enc" -> vp8Encode(bound, threads, Integer.parseInt(options.getOrDefault("speed", "6")),
                    Boolean.parseBoolean(options.getOrDefault("resilient", "true")),
                    Integer.parseInt(options.getOrDefault("width", "640")), Integer.parseInt(options.getOrDefault("height", "360")));
            case "vp9enc" -> vp9Encode(bound, threads, Integer.parseInt(options.getOrDefault("speed", "6")),
                    Boolean.parseBoolean(options.getOrDefault("resilient", "true")),
                    Integer.parseInt(options.getOrDefault("layers", "1")),
                    Integer.parseInt(options.getOrDefault("width", "640")), Integer.parseInt(options.getOrDefault("height", "360")));
            case "av1enc" -> av1Encode(bound, threads, Av1EncoderSpeed.valueOf(options.getOrDefault("speed", "FASTER")),
                    Integer.parseInt(options.getOrDefault("width", "640")), Integer.parseInt(options.getOrDefault("height", "360")));
            case "av1dec" -> ivfDecode("av1dec", bound, Path.of(options.get("input")), Codec.AV1,
                    track -> new Av1Decoder(track));
            case "vp9dec" -> ivfDecode("vp9dec", bound, Path.of(options.get("input")), Codec.VP9,
                    track -> new Vp9Decoder(track));
            case "proresdec" -> IntraJitWorkloads.proresDecode(bound);
            case "proresenc" -> IntraJitWorkloads.proresEncode(bound);
            case "dnxhddec" -> IntraJitWorkloads.dnxhdDecode(bound);
            case "dnxhdenc" -> IntraJitWorkloads.dnxhdEncode(bound);
            case "webpenc" -> webpEncode(bound);
            case "webpdec" -> webpDecode(bound);
            case "webpanim" -> webpAnimation(bound, Integer.parseInt(options.getOrDefault("concurrent", "2")),
                    Integer.parseInt(options.getOrDefault("frames", "8")));
            case "capture" -> named("capture", bound, CaptureWorkloads::round);
            case "opusvoice" -> AudioJitWorkloads.opusVoiceNote(bound, seconds(options, 8));
            case "opuscall" -> AudioJitWorkloads.opusCall(bound, seconds(options, 6));
            case "aacenc" -> AudioJitWorkloads.aacEncode(bound, seconds(options, 6));
            case "amrnb" -> AudioJitWorkloads.amr("amrnb", Codec.AMR_NB, bound, seconds(options, 4));
            case "amrwb" -> AudioJitWorkloads.amr("amrwb", Codec.AMR_WB, bound, seconds(options, 4));
            case "heaac" -> AudioJitWorkloads.heAac(bound, seconds(options, 6));
            case "ac3" -> AudioJitWorkloads.ac3(bound, seconds(options, 6));
            case "flacenc" -> AudioJitWorkloads.flacEncode(bound, seconds(options, 6));
            case "mp3dec" -> AudioJitWorkloads.mp3Decode(bound, Path.of(options.get("input")));
            case "mp3enc" -> AudioJitWorkloads.mp3Encode(bound, seconds(options, 6));
            case "resample" -> AudioJitWorkloads.resample(bound, seconds(options, 10));
            case "voice" -> AudioJitWorkloads.voiceCall(bound, seconds(options, 6));
            case "jpegsend" -> ImageJitWorkloads.jpegSend(bound, Integer.parseInt(options.getOrDefault("count", "2")));
            case "pngdec" -> ImageJitWorkloads.pngDecode(bound, Integer.parseInt(options.getOrDefault("count", "2")));
            case "gifdec" -> ImageJitWorkloads.gifDecode(bound, Integer.parseInt(options.getOrDefault("count", "4")));
            case "convert" -> ImageJitWorkloads.convert(bound, Integer.parseInt(options.getOrDefault("count", "2")));
            case "effects" -> ImageJitWorkloads.effects(bound, Integer.parseInt(options.getOrDefault("count", "1")));
            default -> throw new IllegalArgumentException(spec);
        };
    }

    private static double seconds(HashMap<String, String> options, double otherwise) {
        return Double.parseDouble(options.getOrDefault("seconds", Double.toString(otherwise)));
    }

    private static H264Profile profile(String name) {
        return switch (name) {
            case "baseline" -> H264Profile.CONSTRAINED_BASELINE;
            case "high" -> H264Profile.HIGH;
            default -> H264Profile.MAIN;
        };
    }

    static Workload named(String name, long bound, java.util.function.IntSupplier round) {
        return new Workload() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public long bound() {
                return bound;
            }

            @Override
            public int round() {
                return round.getAsInt();
            }
        };
    }

    /** Synthesizes natural-looking video: smooth value noise and sinusoids panning at sub-sample speeds. */
    static List<YuvPicture> synthesize(int width, int height, int frames) {
        var lattice = new int[64 * 64];
        var seed = 0x9E3779B9L;
        for (var i = 0; i < lattice.length; i++) {
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            lattice[i] = (int) (seed >>> 56);
        }
        var pictures = new ArrayList<YuvPicture>(frames);
        for (var t = 0; t < frames; t++) {
            var picture = YuvPicture.allocate(width, height, ChromaFormat.YUV420, false, ColorInfo.BT709_LIMITED);
            var y = picture.y();
            for (var row = 0; row < height; row++) {
                for (var column = 0; column < width; column++) {
                    var u = (column + t * 1.37) / 9.0;
                    var v = (row - t * 0.61) / 9.0;
                    var noise = valueNoise(lattice, u, v);
                    var wave = 40 * Math.sin((column - t * 2.3) / 23.0) * Math.cos((row + t * 1.1) / 31.0);
                    var spot = (column - width / 2.0 - 60 * Math.sin(t / 9.0)) * (column - width / 2.0 - 60 * Math.sin(t / 9.0))
                            + (row - height / 2.0) * (row - height / 2.0) < 1600 ? 50 : 0;
                    y[row * picture.yStride() + column] = (byte) clamp((int) (60 + noise * 0.5 + wave + spot));
                }
            }
            for (var row = 0; row < picture.chromaHeight(); row++) {
                for (var column = 0; column < picture.chromaWidth(); column++) {
                    var noise = valueNoise(lattice, (column + t * 0.7) / 5.0 + 17, (row + t * 0.3) / 5.0 + 5);
                    picture.u()[row * picture.uvStride() + column] = (byte) clamp((int) (96 + noise / 4));
                    picture.v()[row * picture.uvStride() + column] = (byte) clamp((int) (160 - noise / 4));
                }
            }
            pictures.add(picture);
        }
        return pictures;
    }

    /** Reads up to {@code frames} pictures of a raw planar 4:2:0 file. */
    static List<YuvPicture> read(Path file, int width, int height, int frames) throws Exception {
        var size = width * height * 3 / 2;
        var bytes = Files.readAllBytes(file);
        var pictures = new ArrayList<YuvPicture>();
        for (var offset = 0; offset + size <= bytes.length && pictures.size() < frames; offset += size) {
            var picture = YuvPicture.allocate(width, height, ChromaFormat.YUV420, false, ColorInfo.BT709_LIMITED);
            System.arraycopy(bytes, offset, picture.y(), 0, width * height);
            System.arraycopy(bytes, offset + width * height, picture.u(), 0, width * height / 4);
            System.arraycopy(bytes, offset + width * height * 5 / 4, picture.v(), 0, width * height / 4);
            pictures.add(picture);
        }
        return pictures;
    }

    private static double valueNoise(int[] lattice, double u, double v) {
        var x0 = (int) Math.floor(u);
        var y0 = (int) Math.floor(v);
        var fx = u - x0;
        var fy = v - y0;
        var a = lattice[(y0 & 63) * 64 + (x0 & 63)];
        var b = lattice[(y0 & 63) * 64 + ((x0 + 1) & 63)];
        var c = lattice[((y0 + 1) & 63) * 64 + (x0 & 63)];
        var d = lattice[((y0 + 1) & 63) * 64 + ((x0 + 1) & 63)];
        var sx = fx * fx * (3 - 2 * fx);
        var sy = fy * fy * (3 - 2 * fy);
        return (a * (1 - sx) + b * sx) * (1 - sy) + (c * (1 - sx) + d * sx) * sy;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static H264EncoderOptions options(H264Profile profile, H264EncoderSpeed speed, int threads) {
        return H264EncoderOptions.forFile().toBuilder()
                .timeBase(new Rational(1, 30))
                .profile(profile)
                .bitrate(800_000)
                .speed(speed)
                .threads(threads)
                .build();
    }

    // rt=true runs the real-time preset of calls.
    private static Workload h264Encode(long bound, int threads, H264EncoderSpeed speed,
                                       H264Profile profile, String input, int width, int height, boolean realtime)
            throws Exception {
        var pictures = input == null ? synthesize(width, height, 24) : read(Path.of(input), width, height, 24);
        var options = realtime
                ? H264EncoderOptions.forRealtime().toBuilder().timeBase(new Rational(1, 30)).bitrate(800_000)
                        .threads(threads).build()
                : options(profile, speed, threads);
        var encoder = new H264Encoder(width, height, options);
        long[] pts = {0};
        Consumer<Packet> output = packet -> sink += packet.size();
        var name = realtime ? "h264enc-rt-t" + threads : "h264enc-" + speed + "-" + profile + "-t" + threads;
        return named(name, bound, () -> {
            for (var picture : pictures) {
                encoder.encode(new VideoFrame(picture, pts[0]++, 1, false), output);
            }
            return pictures.size();
        });
    }

    private static Workload vp8Encode(long bound, int threads, int speed, boolean errorResilient, int width, int height) {
        var pictures = synthesize(width, height, 24);
        var encoder = new Vp8Encoder(width, height, Vp8EncoderOptions.builder()
                .timeBase(new Rational(1, 30))
                .bitrate(800_000)
                .quantizers(4, 63)
                .noPeriodicKeyframes()
                .speed(Vp8EncoderSpeed.of(speed))
                .errorResilient(errorResilient)
                .bufferSize(Duration.ofMillis(1000))
                .initialBufferLevel(Duration.ofMillis(500))
                .optimalBufferLevel(Duration.ofMillis(600))
                .threads(threads)
                .build());
        long[] pts = {0};
        Consumer<Packet> output = packet -> sink += packet.size();
        return named("vp8enc-s" + speed + (errorResilient ? "-er" : "") + "-t" + threads, bound, () -> {
            for (var picture : pictures) {
                encoder.encode(new VideoFrame(picture, pts[0]++, 1, false), output);
            }
            return pictures.size();
        });
    }

    private static Workload vp9Encode(long bound, int threads, int speed, boolean errorResilient, int layers, int width,
                                      int height) {
        var pictures = synthesize(width, height, 24);
        var encoder = new Vp9Encoder(width, height, Vp9EncoderOptions.builder()
                .timeBase(new Rational(1, 30))
                .bitrate(800_000)
                .quantizers(2, 52)
                .noPeriodicKeyframes()
                .speed(Vp9EncoderSpeed.of(speed))
                .errorResilient(errorResilient)
                .adaptiveQuantization(true)
                .temporalLayers(layers)
                .bufferSize(Duration.ofMillis(1000))
                .initialBufferLevel(Duration.ofMillis(500))
                .optimalBufferLevel(Duration.ofMillis(600))
                .tileColumnsLog2(2)
                .threads(threads)
                .build());
        long[] pts = {0};
        Consumer<Packet> output = packet -> sink += packet.size();
        return named("vp9enc-s" + speed + (errorResilient ? "-er" : "") + "-l" + layers + "-t" + threads, bound, () -> {
            for (var picture : pictures) {
                encoder.encode(new VideoFrame(picture, pts[0]++, 1, false), output);
            }
            return pictures.size();
        });
    }

    private static Workload av1Encode(long bound, int threads, Av1EncoderSpeed speed, int width, int height) {
        var pictures = synthesize(width, height, 24);
        var encoder = new Av1Encoder(width, height, Av1EncoderOptions.forRealtime().toBuilder().bitrate(800_000).speed(speed)
                .dropFrames(false).threads(threads).tileColumnsLog2(threads > 1 ? 1 : 0)
                .timeBase(new Rational(1, 30)).build());
        long[] pts = {0};
        Consumer<Packet> output = packet -> sink += packet.size();
        return named("av1enc-" + speed.name().toLowerCase(Locale.ROOT) + "-t" + threads, bound, () -> {
            for (var picture : pictures) {
                encoder.encode(new VideoFrame(picture, pts[0]++, 1, false), output);
            }
            return pictures.size();
        });
    }

    private static Workload h264Decode(long bound, int threads, String input) throws Exception {
        var packets = new ArrayList<Packet>();
        Optional<byte[]> codecConfig;
        String name;
        if (input != null) {
            for (var unit : nalUnits(Files.readAllBytes(Path.of(input)))) {
                packets.add(new Packet(1, unit, Packet.NO_TIMESTAMP, Packet.NO_TIMESTAMP, 0, false));
            }
            codecConfig = Optional.empty();
            name = "h264dec-file-t" + threads;
        } else {
            var encoder = new H264Encoder(640, 360, options(H264Profile.HIGH, H264EncoderSpeed.FAST, 2));
            long[] pts = {0};
            for (var picture : synthesize(640, 360, 30)) {
                encoder.encode(new VideoFrame(picture, pts[0]++, 1, false), packets::add);
            }
            encoder.flush(packets::add);
            codecConfig = encoder.codecConfig();
            encoder.close();
            name = "h264dec-t" + threads;
        }
        var track = VideoTrack.builder().id(1).codec(Codec.H264).timeBase(new Rational(1, 30))
                .codecConfig(codecConfig.orElse(null))
                .build();
        var decoder = new H264Decoder(track, H264DecoderOptions.builder().threads(threads).build());
        int[] count = {0};
        Consumer<VideoFrame> output = frame -> {
            count[0]++;
            sink += ((YuvPicture) frame.picture()).y()[17];
        };
        return named(name, bound, () -> {
            count[0] = 0;
            for (var packet : packets) {
                decoder.decode(packet, output);
            }
            decoder.flush(output);
            return count[0];
        });
    }

    private static Workload hevcDecode(long bound, int threads, Path input) throws Exception {
        var packets = new ArrayList<Packet>();
        for (var unit : nalUnits(Files.readAllBytes(input))) {
            packets.add(new Packet(1, unit, Packet.NO_TIMESTAMP, Packet.NO_TIMESTAMP, 0, false));
        }
        var track = VideoTrack.builder().id(1).codec(Codec.HEVC).timeBase(new Rational(1, 30)).build();
        var decoder = new HevcDecoder(track, HevcDecoderOptions.builder().threads(threads).build());
        int[] count = {0};
        Consumer<VideoFrame> output = frame -> {
            count[0]++;
            sink += ((YuvPicture) frame.picture()).y()[17];
        };
        return named("hevcdec-t" + threads, bound, () -> {
            count[0] = 0;
            for (var packet : packets) {
                decoder.decode(packet, output);
            }
            decoder.flush(output);
            return count[0];
        });
    }

    private static List<byte[]> nalUnits(byte[] data) {
        var starts = new ArrayList<Integer>();
        for (var i = 0; i + 3 <= data.length; i++) {
            if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) {
                starts.add(i);
                i += 2;
            }
        }
        var units = new ArrayList<byte[]>();
        for (var k = 0; k < starts.size(); k++) {
            var end = k + 1 < starts.size() ? starts.get(k + 1) : data.length;
            units.add(Arrays.copyOfRange(data, starts.get(k), end));
        }
        return units;
    }

    private static List<byte[]> ivfFrames(byte[] bytes) {
        var frames = new ArrayList<byte[]>();
        for (var position = (bytes[6] & 0xFF) | (bytes[7] & 0xFF) << 8; position + 12 <= bytes.length; ) {
            var length = (bytes[position] & 0xFF) | (bytes[position + 1] & 0xFF) << 8 | (bytes[position + 2] & 0xFF) << 16
                    | (bytes[position + 3] & 0xFF) << 24;
            frames.add(Arrays.copyOfRange(bytes, position + 12, position + 12 + length));
            position += 12 + length;
        }
        return frames;
    }

    private static Workload vp8Decode(long bound, Path input) throws Exception {
        var bytes = Files.readAllBytes(input);
        var width = (bytes[12] & 0xFF) | (bytes[13] & 0xFF) << 8;
        var height = (bytes[14] & 0xFF) | (bytes[15] & 0xFF) << 8;
        var frames = ivfFrames(bytes);
        var track = VideoTrack.builder().id(1).codec(Codec.VP8).timeBase(new Rational(1, 30)).width(width)
                .height(height)
                .build();
        var decoder = new Vp8Decoder(track);
        int[] count = {0};
        long[] pts = {0};
        Consumer<VideoFrame> output = frame -> {
            count[0]++;
            sink += ((YuvPicture) frame.picture()).y()[17];
        };
        return named("vp8dec", bound, () -> {
            count[0] = 0;
            for (var frame : frames) {
                decoder.decode(new Packet(1, frame, pts[0], pts[0], 1, false), output);
                pts[0]++;
            }
            return count[0];
        });
    }

    /** Decodes every frame of an IVF file per round with the decoder the factory creates. */
    private static Workload ivfDecode(String name, long bound, Path input, Codec codec,
                                      java.util.function.Function<VideoTrack, VideoDecoder> factory) throws Exception {
        var bytes = Files.readAllBytes(input);
        var width = (bytes[12] & 0xFF) | (bytes[13] & 0xFF) << 8;
        var height = (bytes[14] & 0xFF) | (bytes[15] & 0xFF) << 8;
        var frames = ivfFrames(bytes);
        var track = VideoTrack.builder().id(1).codec(codec).timeBase(new Rational(1, 30)).width(width).height(height)
                .build();
        var decoder = factory.apply(track);
        int[] count = {0};
        long[] pts = {0};
        Consumer<VideoFrame> output = frame -> {
            count[0]++;
            sink += ((YuvPicture) frame.picture()).y()[17];
        };
        return named(name, bound, () -> {
            count[0] = 0;
            for (var frame : frames) {
                decoder.decode(new Packet(1, frame, pts[0], pts[0], 1, false), output);
                pts[0]++;
            }
            return count[0];
        });
    }

    private static List<RgbaPicture> stickers() {
        return stickers(256, 2);
    }

    /** Returns {@code count} frames of a moving sticker of {@code size} square pixels with a round alpha mask. */
    private static List<RgbaPicture> stickers(int size, int count) {
        var stickers = new ArrayList<RgbaPicture>();
        var half = size / 2;
        for (var picture : synthesize(size, size, count)) {
            var pixels = new byte[size * size * 4];
            for (var y = 0; y < size; y++) {
                for (var x = 0; x < size; x++) {
                    var luma = picture.y()[y * picture.yStride() + x] & 0xFF;
                    var o = (y * size + x) * 4;
                    pixels[o] = (byte) luma;
                    pixels[o + 1] = (byte) (255 - luma);
                    pixels[o + 2] = (byte) (luma ^ x);
                    pixels[o + 3] = (byte) clamp((int) ((half - 8 - Math.hypot(x - half, y - half)) * 4));
                }
            }
            stickers.add(RgbaPicture.of(size, size, size * 4, pixels, true));
        }
        return stickers;
    }

    /**
     * Encodes lossy animated stickers as the sticker pipeline does: 512x512 frames with alpha,
     * {@code concurrent} attempts at falling qualities encoded at once, each encoder running its
     * own workers.
     */
    private static Workload webpAnimation(long bound, int concurrent, int frameCount) {
        var frames = stickers(512, frameCount);
        int[] qualities = {75, 50, 35, 20};
        ExecutorService attempts = Executors.newFixedThreadPool(concurrent, Thread.ofPlatform().daemon().factory());
        return named("webpanim-c" + concurrent, bound, () -> {
            var results = new ArrayList<Future<byte[]>>();
            for (var attempt = 0; attempt < concurrent; attempt++) {
                var quality = qualities[attempt % qualities.length];
                results.add(attempts.submit(() -> {
                    var encoder = new WebpAnimationEncoder(512, 512, WebpAnimationEncoderOptions.lossy(quality));
                    for (var frame : frames) {
                        encoder.addFrame(frame, 40);
                    }
                    return encoder.finish();
                }));
            }
            try {
                for (var result : results) {
                    sink += result.get().length;
                }
            } catch (InterruptedException | ExecutionException exception) {
                throw new IllegalStateException(exception);
            }
            return frames.size() * concurrent;
        });
    }

    private static Workload webpEncode(long bound) {
        var stickers = stickers();
        var lossy = WebpEncoderOptions.lossy(75);
        var lossless = WebpEncoderOptions.lossless();
        return named("webpenc", bound, () -> {
            for (var sticker : stickers) {
                sink += WebpEncoder.encode(sticker, lossy).length + WebpEncoder.encode(sticker, lossless).length;
            }
            return stickers.size() * 2;
        });
    }

    private static Workload webpDecode(long bound) {
        var images = new ArrayList<byte[]>();
        for (var sticker : stickers()) {
            images.add(WebpEncoder.encode(sticker, WebpEncoderOptions.lossy(75)));
            images.add(WebpEncoder.encode(sticker, WebpEncoderOptions.lossless()));
        }
        return named("webpdec", bound, () -> {
            for (var image : images) {
                sink += WebpDecoder.decode(MediaSource.of(image), ImageDecoderOptions.defaults()).hashCode();
            }
            return images.size();
        });
    }

    /** Runs the capture conversion kernels on synthetic frames. */
    static final class CaptureWorkloads {
        private CaptureWorkloads() {
        }

        static int round() {
            for (var i = 0; i < 10; i++) {
                com.github.auties00.smalt.capture.internal.convert.CaptureTrainingRound.round();
            }
            return 30;
        }
    }
}
