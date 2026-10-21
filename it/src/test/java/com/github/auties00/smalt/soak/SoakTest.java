package com.github.auties00.smalt.soak;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioDecoders;
import com.github.auties00.smalt.audio.AudioEncoderOptions;
import com.github.auties00.smalt.audio.AudioReader;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.image.ImageEncoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.ops.ResizeFilter;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.property.Streams;
import com.github.auties00.smalt.property.ThreadDigest;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.transcode.OutputContainer;
import com.github.auties00.smalt.transcode.TranscodeOptions;
import com.github.auties00.smalt.transcode.Transcoder;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoDecoders;
import com.github.auties00.smalt.video.VideoReader;
import jdk.jfr.Recording;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the real-time encoders and decoders, a transcode of every common container and the picture operations in a
 * loop for {@code smalt.soak.minutes} minutes under a JFR recording, and checks that the process stays flat: the heap
 * after a full collection, the live thread count, the open file descriptors and the bytes allocated per round do not
 * grow from the early rounds to the late ones, and every round produces the output of the first. Enabled only with
 * the property (the weekly soak workflow sets it): {@code mvn -pl it -am verify -Dtest=SoakTest
 * -Dsurefire.failIfNoSpecifiedTests=false -Dsmalt.soak.minutes=300}.
 */
@DisplayName("Soak")
@EnabledIfSystemProperty(named = "smalt.soak.minutes", matches = "\\d+(\\.\\d+)?")
class SoakTest {
    /** Rounds whose measurements set the early reference, after two warm-up rounds. */
    private static final int REFERENCE_ROUNDS = 3;

    record Measurement(long heapAfterGc, int threads, long descriptors, long allocated) {
    }

    @Test
    @DisplayName("keeps heap, threads, file descriptors and allocation per round flat, and the output stable")
    void flat() throws Exception {
        var minutes = Double.parseDouble(System.getProperty("smalt.soak.minutes"));
        var deadline = System.nanoTime() + (long) (minutes * 60e9);
        var recording = new Recording();
        recording.setName("smalt-soak");
        recording.enable("jdk.GarbageCollection");
        recording.enable("jdk.ObjectAllocationSample").withPeriod(Duration.ofSeconds(1));
        recording.enable("jdk.ThreadStart");
        recording.enable("jdk.ThreadEnd");
        recording.enable("jdk.CPULoad").withPeriod(Duration.ofSeconds(10));
        recording.setToDisk(true);
        recording.setMaxAge(Duration.ofHours(1));
        recording.start();
        var measurements = new ArrayList<Measurement>();
        Map<String, String> reference = null;
        try {
            var round = 0;
            do {
                var before = allocated();
                var outputs = round();
                var allocated = allocated() - before;
                if (reference == null) {
                    reference = outputs;
                } else {
                    assertEquals(reference, outputs, "outputs of round " + round);
                }
                measurements.add(measure(allocated));
                System.out.println("soak round " + round + " " + measurements.getLast());
                round++;
            } while (System.nanoTime() < deadline || measurements.size() < 2 + 2 * REFERENCE_ROUNDS);
        } finally {
            recording.stop();
            Files.createDirectories(Path.of("target", "soak"));
            recording.dump(Path.of("target", "soak", "soak.jfr"));
            recording.close();
        }
        assertFlat(measurements);
    }

    static void assertFlat(List<Measurement> measurements) {
        var early = measurements.subList(2, 2 + REFERENCE_ROUNDS);
        var late = measurements.subList(measurements.size() - REFERENCE_ROUNDS, measurements.size());
        var earlyHeap = median(early.stream().mapToLong(Measurement::heapAfterGc).toArray());
        var lateHeap = median(late.stream().mapToLong(Measurement::heapAfterGc).toArray());
        assertTrue(lateHeap <= earlyHeap + (32L << 20), "heap after GC grew from " + (earlyHeap >> 20) + " to "
                + (lateHeap >> 20) + " MiB");
        var earlyThreads = early.stream().mapToInt(Measurement::threads).max().orElseThrow();
        var lateThreads = late.stream().mapToInt(Measurement::threads).max().orElseThrow();
        // The shared helpers start on demand and stop after a second idle, up to twice smalt.threads.
        assertTrue(lateThreads <= earlyThreads + 2 * Runtime.getRuntime().availableProcessors(),
                "threads grew from " + earlyThreads + " to " + lateThreads);
        var earlyFds = early.stream().mapToLong(Measurement::descriptors).max().orElseThrow();
        var lateFds = late.stream().mapToLong(Measurement::descriptors).max().orElseThrow();
        assertTrue(lateFds <= earlyFds + 8, "open file descriptors grew from " + earlyFds + " to " + lateFds);
        var earlyBytes = median(early.stream().mapToLong(Measurement::allocated).toArray());
        var lateBytes = median(late.stream().mapToLong(Measurement::allocated).toArray());
        assertTrue(lateBytes <= earlyBytes * 3 / 2, "allocation per round grew from " + (earlyBytes >> 20) + " to "
                + (lateBytes >> 20) + " MiB");
    }

    /** Runs every workload once and returns a digest of each output. */
    static Map<String, String> round() {
        var outputs = new LinkedHashMap<String, String>();
        for (var codec : List.of(Codec.VP8, Codec.VP9, Codec.AV1, Codec.H264)) {
            var stream = Streams.video(codec, 320, 180, 30, builder -> builder.realtime(true));
            outputs.put(codec + " encode", ThreadDigest.sha256Packets(stream));
            var decoder = VideoDecoders.create((VideoTrack) stream.track(), VideoDecoderOptions.defaults());
            var digest = new StringBuilder();
            try (decoder) {
                for (var packet : stream.packets()) {
                    decoder.decode(packet, frame -> digest.append(ThreadDigest.sha256(
                            ImageOps.toRgba(frame.picture()).pixels()), 0, 8));
                }
                decoder.flush(frame -> digest.append('f'));
            }
            outputs.put(codec + " decode", digest.toString());
        }
        var opus = Streams.audio(Codec.OPUS, ChannelLayout.STEREO, 48_000, 96_000,
                builder -> builder.realtime(true).bitrate(64_000));
        outputs.put("opus encode", ThreadDigest.sha256Packets(opus));
        var decoded = new long[1];
        try (var decoder = AudioDecoders.create((AudioTrack) opus.track())) {
            java.util.function.Consumer<AudioBuffer> sink = buffer -> decoded[0] += buffer.frames();
            opus.packets().forEach(packet -> decoder.decode(packet, sink));
            decoder.flush(sink);
        }
        outputs.put("opus decode", Long.toString(decoded[0]));
        var source = mux(OutputContainer.MP4, List.of(Streams.video(Codec.H264, 320, 180, 30,
                builder -> builder.realtime(false)), Streams.audio(Codec.AAC, ChannelLayout.STEREO, 48_000, 48_000,
                builder -> builder)));
        for (var target : List.of(OutputContainer.WEBM, OutputContainer.MATROSKA, OutputContainer.MPEG_TS,
                OutputContainer.MP4)) {
            var output = MediaSink.memory();
            Transcoder.transcode(MediaSource.of(source), output, TranscodeOptions.of(target));
            var bytes = output.toByteArray();
            var frames = 0;
            try (var reader = VideoReader.open(MediaSource.of(bytes))) {
                while (reader.read() != null) {
                    frames++;
                }
            }
            var samples = 0L;
            try (var reader = AudioReader.open(MediaSource.of(bytes))) {
                for (var block = reader.read(); block != null; block = reader.read()) {
                    samples += block.frames();
                }
            }
            outputs.put("transcode " + target, ThreadDigest.sha256(bytes) + " " + frames + " " + samples);
        }
        var picture = ThreadDigest.picture(1280, 720);
        for (var format : List.of(ImageFormat.JPEG, ImageFormat.WEBP, ImageFormat.PNG, ImageFormat.AVIF)) {
            var encoded = Images.encode(picture, format, ImageEncoderOptions.ofQuality(75));
            var back = Images.decode(MediaSource.of(encoded)).picture();
            outputs.put(format + " round trip", ThreadDigest.sha256(encoded) + " "
                    + ThreadDigest.sha256(ImageOps.toRgba(back).pixels()));
        }
        outputs.put("resize", ThreadDigest.sha256(ImageOps.toRgba(ImageOps.resize(picture, 640, 360,
                ResizeFilter.LANCZOS3)).pixels()));
        return outputs;
    }

    private static byte[] mux(OutputContainer container, List<Streams.Encoded> streams) {
        var sink = MediaSink.memory();
        try (var muxer = Containers.openMuxer(container, sink)) {
            streams.forEach(stream -> muxer.addTrack(stream.track()));
            var packets = new ArrayList<com.github.auties00.smalt.track.Packet>();
            streams.forEach(stream -> packets.addAll(stream.packets()));
            packets.sort(java.util.Comparator.comparingLong(packet -> {
                var track = packet.trackId() == 1 ? streams.get(0).track() : streams.get(1).track();
                var time = packet.dts() != com.github.auties00.smalt.track.Packet.NO_TIMESTAMP ? packet.dts()
                        : packet.pts();
                return track.timeBase().toMicros(time);
            }));
            packets.forEach(muxer::writePacket);
            muxer.finish();
        }
        return sink.toByteArray();
    }

    private static Measurement measure(long allocated) {
        System.gc();
        System.gc();
        var memory = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
        var threads = ManagementFactory.getThreadMXBean().getThreadCount();
        var system = ManagementFactory.getOperatingSystemMXBean();
        var descriptors = system instanceof com.sun.management.UnixOperatingSystemMXBean unix
                ? unix.getOpenFileDescriptorCount() : -1;
        return new Measurement(memory, threads, descriptors, allocated);
    }

    private static long allocated() {
        return ((com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean()).getTotalThreadAllocatedBytes();
    }

    private static long median(long[] values) {
        var sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }
}
