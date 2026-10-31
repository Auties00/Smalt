package com.github.auties00.smalt.video.h264.encoder;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.VideoFrame;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Shared helpers of the encoder tests: synthetic pictures, encoding with reconstruction capture,
 * Annex B conversion and strict ffmpeg decoding.
 */
final class EncoderHarness {
    private static final Path WORK = Scratch.directory("h264enc-work");

    private EncoderHarness() {
        throw new AssertionError();
    }

    /** The result of encoding a sequence. */
    record Encoded(H264Setup config, List<Packet> packets, List<byte[]> reconstructions, byte[] sps,
                   byte[] pps, byte[] avcC, long[] typeCounts) {
        byte[] annexB() {
            var out = new ByteArrayOutputStream();
            writeUnit(out, sps);
            writeUnit(out, pps);
            for (var packet : packets) {
                var data = packet.data();
                var offset = 0;
                while (offset < data.length) {
                    var length = ((data[offset] & 0xFF) << 24) | ((data[offset + 1] & 0xFF) << 16)
                            | ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
                    out.write(0);
                    out.write(0);
                    out.write(0);
                    out.write(1);
                    out.write(data, offset + 4, length);
                    offset += 4 + length;
                }
            }
            return out.toByteArray();
        }

        long totalBytes() {
            return packets.stream().mapToLong(Packet::size).sum();
        }

        private static void writeUnit(ByteArrayOutputStream out, byte[] unit) {
            out.write(0);
            out.write(0);
            out.write(0);
            out.write(1);
            out.write(unit, 0, unit.length);
        }
    }

    static Encoded encode(H264Setup config, List<? extends Picture> pictures) {
        return encode(config, pictures, 1.0);
    }

    static Encoded encode(H264Setup config, List<? extends Picture> pictures, double aq) {
        return encode(config, pictures, aq, 3200);
    }

    static Encoded encode(H264Setup config, List<? extends Picture> pictures, double aq, int macroblockBits) {
        var packets = new ArrayList<Packet>();
        var reconstructions = new ArrayList<byte[]>();
        try (var encoder = config.encoder()) {
            encoder.core().setAdaptiveQuantization(aq);
            encoder.core().setMacroblockBitLimit(macroblockBits);
            long pts = 0;
            for (var picture : pictures) {
                encoder.encode(new VideoFrame(picture, pts++, 0, false), packets::add);
                reconstructions.add(encoder.core().lastReconstructionI420());
            }
            encoder.flush(packets::add);
            return new Encoded(config, packets, reconstructions, encoder.core().sequenceParameterSet(),
                    encoder.core().pictureParameterSet(), encoder.codecConfig().orElseThrow(),
                    encoder.core().typeCounts());
        }
    }

    /** Returns a file of the work directory, creating the directory. */
    static Path workFile(String name) {
        try {
            Files.createDirectories(WORK);
            return WORK.resolve(name);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static Path write(String name, byte[] data) {
        try {
            Files.createDirectories(WORK);
            var file = WORK.resolve(name);
            Files.write(file, data);
            return file;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** Decodes an Annex B stream with ffmpeg, failing on any decoder message. */
    static byte[] decodeStrict(Path file) {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var executable = Oracle.find(Oracle.Tool.FFMPEG).orElseThrow();
        var command = List.of(executable.toString(), "-v", "warning", "-err_detect", "explode", "-f", "h264", "-i",
                file.toString(), "-fps_mode", "passthrough", "-f", "rawvideo", "-pix_fmt", "yuv420p", "-");
        try {
            var process = new ProcessBuilder(command).start();
            var errors = new ByteArrayOutputStream();
            var stderr = new Thread(() -> {
                try (var stream = process.getErrorStream()) {
                    stream.transferTo(errors);
                } catch (IOException ignored) {
                    // process ended
                }
            });
            stderr.start();
            process.getOutputStream().close();
            var output = process.getInputStream().readAllBytes();
            if (!process.waitFor(5, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                fail("ffmpeg timed out");
            }
            stderr.join();
            var messages = errors.toString();
            if (process.exitValue() != 0 || !messages.isBlank()) {
                fail("ffmpeg reported problems decoding " + file + " (exit " + process.exitValue() + "):\n" + messages);
            }
            return output;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    /** Returns the first value of every syntax element ffmpeg's trace_headers filter reports for a stream. */
    static java.util.Map<String, Long> traceHeaders(Path file) {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var executable = Oracle.find(Oracle.Tool.FFMPEG).orElseThrow();
        var command = List.of(executable.toString(), "-v", "trace", "-f", "h264", "-i", file.toString(), "-c", "copy",
                "-bsf:v", "trace_headers", "-f", "null", "-");
        try {
            var process = new ProcessBuilder(command).redirectErrorStream(true).start();
            process.getOutputStream().close();
            var text = new String(process.getInputStream().readAllBytes());
            process.waitFor(5, TimeUnit.MINUTES);
            var values = new java.util.LinkedHashMap<String, Long>();
            var pattern = java.util.regex.Pattern.compile("\\[trace_headers @ [^\\]]*\\]\\s+\\d+\\s+(\\S+)\\s+[01]+ = (-?\\d+)");
            for (var line : text.split("\\R")) {
                var matcher = pattern.matcher(line);
                if (matcher.find()) {
                    values.putIfAbsent(matcher.group(1), Long.parseLong(matcher.group(2)));
                }
            }
            return values;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    /** Asserts that ffmpeg decodes the stream to exactly the encoder's reconstruction. */
    static void assertBitExact(String name, Encoded encoded) {
        var file = write(name + ".264", encoded.annexB());
        var decoded = decodeStrict(file);
        var frameSize = encoded.reconstructions().getFirst().length;
        assertEquals((long) frameSize * encoded.reconstructions().size(), decoded.length,
                "decoded size of " + name);
        for (var i = 0; i < encoded.reconstructions().size(); i++) {
            var expected = encoded.reconstructions().get(i);
            for (var j = 0; j < frameSize; j++) {
                if (expected[j] != decoded[i * frameSize + j]) {
                    fail(name + ": frame " + i + " differs at byte " + j + " of " + frameSize + " (expected "
                            + (expected[j] & 0xFF) + ", got " + (decoded[i * frameSize + j] & 0xFF) + ")");
                }
            }
        }
    }

    /** Returns a camera-like synthetic 4:2:0 picture: gradients and shapes moving over time, plus noise. */
    static YuvPicture synthetic(int width, int height, int frame, long seed, double noise) {
        var picture = YuvPicture.allocate(width, height, ChromaFormat.YUV420, false, ColorInfo.BT709_LIMITED);
        var random = new Random(seed * 1_000_003L + frame);
        var y = picture.y();
        var shiftX = frame * 3;
        var shiftY = frame * 2;
        for (var row = 0; row < height; row++) {
            for (var col = 0; col < width; col++) {
                var u = col + shiftX;
                var v = row + shiftY;
                var value = 64 + ((u * 3 + v * 2) & 127)
                        + (int) (40 * Math.sin(u * 0.05) * Math.cos(v * 0.07))
                        + (((u / 16 + v / 16) & 1) == 0 ? 20 : 0);
                if (Math.abs(col - (width / 2 + (int) (width / 4.0 * Math.sin(frame * 0.2)))) < width / 8
                        && Math.abs(row - height / 2) < height / 6) {
                    value = 220 - ((col + row) & 31);
                }
                value += (int) Math.round(random.nextGaussian() * noise);
                y[row * width + col] = (byte) Math.clamp(value, 0, 255);
            }
        }
        var chromaWidth = picture.chromaWidth();
        var chromaHeight = picture.chromaHeight();
        for (var row = 0; row < chromaHeight; row++) {
            for (var col = 0; col < chromaWidth; col++) {
                var u = col + shiftX / 2;
                var v = row + shiftY / 2;
                picture.u()[row * chromaWidth + col] = (byte) Math.clamp(128 + (int) (50 * Math.sin(u * 0.08))
                        + (int) Math.round(random.nextGaussian() * noise / 2), 0, 255);
                picture.v()[row * chromaWidth + col] = (byte) Math.clamp(128 + (int) (50 * Math.cos(v * 0.06))
                        + (int) Math.round(random.nextGaussian() * noise / 2), 0, 255);
            }
        }
        return picture;
    }

    /** Returns a picture unrelated to {@link #synthetic}: a slowly moving high-contrast checkerboard. */
    static YuvPicture checkerboard(int width, int height, int frame) {
        var picture = YuvPicture.allocate(width, height, ChromaFormat.YUV420, false, ColorInfo.BT709_LIMITED);
        for (var row = 0; row < height; row++) {
            for (var col = 0; col < width; col++) {
                var cell = (((col + frame) / 12) + (row / 12)) & 1;
                picture.y()[row * width + col] = (byte) (cell == 0 ? 30 : 225);
            }
        }
        java.util.Arrays.fill(picture.u(), (byte) 90);
        java.util.Arrays.fill(picture.v(), (byte) 200);
        return picture;
    }

    static List<YuvPicture> sequence(int width, int height, int frames, long seed, double noise) {
        var list = new ArrayList<YuvPicture>(frames);
        for (var i = 0; i < frames; i++) {
            list.add(synthetic(width, height, i, seed, noise));
        }
        return list;
    }

    static H264Setup config(int width, int height, H264Profile profile,
                                    H264EncoderSpeed speed, H264RateControl rc, int bitrate,
                                    int quality, int threads) {
        return H264Setup.of(width, height, new Rational(30, 1), new Rational(1, 30), profile, 0, rc, bitrate, 0,
                0, quality, 60, speed, ColorInfo.BT709_LIMITED, threads);
    }

    static void assertTrueMessage(boolean condition, String message) {
        assertTrue(condition, message);
    }
}
