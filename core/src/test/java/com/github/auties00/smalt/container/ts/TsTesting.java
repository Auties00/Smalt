package com.github.auties00.smalt.container.ts;

import com.github.auties00.smalt.container.ContainerProbe;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.track.Packet;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Shared helpers of the transport stream tests: ffmpeg-generated fixtures, ffmpeg runs that
 * report their warnings, decoded-output digests, and transport packet inspection.
 */
final class TsTesting {
    static final Path OUTPUT = Scratch.directory("ts-output");

    private TsTesting() {
        throw new AssertionError();
    }

    /** Returns an ffmpeg-generated file of test video and a tone, with the given output options. */
    static Path av(String extension, double seconds, List<String> output) {
        return Fixtures.ffmpeg(extension, List.of(
                "-f", "lavfi", "-i", "testsrc2=size=160x120:rate=30:duration=" + seconds,
                "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=" + seconds), output);
    }

    /** Returns an ffmpeg-generated tone with the given output options. */
    static Path tone(String extension, int rate, int channels, double seconds, List<String> output) {
        return Fixtures.tone(extension, 440, rate, channels, seconds, output);
    }

    /** Writes bytes under {@code target/ts-output} and validates them with the test kit's container validators. */
    static Path save(String name, byte[] data) {
        try {
            Files.createDirectories(OUTPUT);
            var file = OUTPUT.resolve(name);
            Files.write(file, data);
            com.github.auties00.smalt.testkit.validate.Validators.assertValid(file);
            return file;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** Returns an absolute path with forward slashes, the form ffmpeg resolves manifest URLs against. */
    static String ffmpegPath(Path file) {
        return file.toAbsolutePath().toString().replace('\\', '/');
    }

    /**
     * Runs ffmpeg on an input, decoding every stream to nothing, and returns what it printed at
     * warning level; fails when ffmpeg exits with an error under {@code -xerror}.
     */
    static String decodeWarnings(String input) {
        return run(List.of("-hide_banner", "-nostdin", "-v", "warning", "-xerror", "-i", input, "-map", "0", "-f", "null", "-"));
    }

    /** Returns the MD5 of every decoded frame of one stream kind ({@code v} or {@code a}). */
    static String decodedMd5(Path input, String kind) {
        var output = run(List.of("-hide_banner", "-nostdin", "-v", "error", "-i", ffmpegPath(input), "-map", "0:" + kind + ":0",
                "-f", "md5", "-"));
        return output.lines().filter(line -> line.startsWith("MD5=")).findFirst().orElseThrow(() -> new AssertionError(output));
    }

    /** Runs ffmpeg and returns its combined output, failing on a non-zero exit. */
    static String run(List<String> arguments) {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var command = new ArrayList<String>();
        command.add(Oracle.find(Oracle.Tool.FFMPEG).orElseThrow().toString());
        command.addAll(arguments);
        try {
            var process = new ProcessBuilder(command).redirectErrorStream(true).start();
            process.getOutputStream().close();
            var out = new ByteArrayOutputStream();
            var reader = Thread.ofVirtual().start(() -> {
                try {
                    process.getInputStream().transferTo(out);
                } catch (IOException ignored) {
                    // the process ended
                }
            });
            if (!process.waitFor(5, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new AssertionError("ffmpeg timed out: " + command);
            }
            reader.join();
            var text = out.toString(StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                throw new AssertionError("ffmpeg exited with " + process.exitValue() + ": " + command + "\n" + text);
            }
            return text;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    /** Demuxes a transport stream held in memory. */
    static ContainerProbe.Demuxed demux(byte[] data) {
        try (var source = MediaSource.of(data); var demuxer = new TsDemuxer(source, TsDemuxerOptions.defaults())) {
            return ContainerProbe.demuxAll(demuxer);
        }
    }

    /** Demuxes any container held in memory. */
    static ContainerProbe.Demuxed demuxAny(byte[] data) {
        try (var source = MediaSource.of(data); var demuxer = Containers.openDemuxer(source)) {
            return ContainerProbe.demuxAll(demuxer);
        }
    }

    /** Returns the transport packets of a stream of 188-byte packets. */
    static List<byte[]> packets(byte[] stream, int size, int sync) {
        var result = new ArrayList<byte[]>();
        for (var offset = 0; offset + size <= stream.length; offset += size) {
            var packet = new byte[188];
            System.arraycopy(stream, offset + sync, packet, 0, 188);
            result.add(packet);
        }
        return result;
    }

    /** Returns the PID of a transport packet. */
    static int pid(byte[] packet) {
        return (packet[1] & 0x1F) << 8 | (packet[2] & 0xFF);
    }

    /** Returns the program clock reference of a transport packet in 27 MHz ticks, or -1. */
    static long pcr(byte[] packet) {
        if ((packet[3] & 0x20) == 0 || (packet[4] & 0xFF) < 7 || (packet[5] & 0x10) == 0) {
            return -1;
        }
        var base = ((packet[6] & 0xFFL) << 25) | ((packet[7] & 0xFFL) << 17) | ((packet[8] & 0xFFL) << 9)
                | ((packet[9] & 0xFFL) << 1) | ((packet[10] & 0xFFL) >> 7);
        var extension = ((packet[10] & 0x01) << 8) | (packet[11] & 0xFF);
        return base * 300 + extension;
    }

    /** Returns the presentation time of a packet in microseconds of its track. */
    static long micros(com.github.auties00.smalt.track.Track track, Packet packet) {
        return track.timeBase().toMicros(packet.pts());
    }
}
