package com.github.auties00.smalt.audio.dts.internal;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.dts.DtsDecoder;
import com.github.auties00.smalt.audio.dts.DtsDecoderOptions;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Decodes synthetic DTS access units with Smalt and with ffmpeg, used as a black box, for the tests of the termination
 * frames, the arrangements above nine and the peak bit rate smoothing buffer.
 */
final class DtsUnits {
    private DtsUnits() {
    }

    /**
     * {@return a decoder under a 48 kHz track}
     */
    static DtsDecoder decoder() {
        var track = AudioTrack.builder().codec(Codec.DTS).timeBase(Rational.timeBaseOf(48000)).sampleRate(48000)
                .build();
        return new DtsDecoder(track, DtsDecoderOptions.defaults());
    }

    /**
     * {@return the buffers of access units decoded one per packet by one decoder}
     */
    static List<AudioBuffer> decode(List<byte[]> units) {
        var decoder = decoder();
        var buffers = new ArrayList<AudioBuffer>();
        for (var unit : units) {
            decoder.decode(new Packet(0, unit, 0, 0, 0, true), buffer -> buffers.add(buffer.copy()));
        }
        return buffers;
    }

    /**
     * {@return the interleaved samples of buffers that share a channel count}
     */
    static float[] interleave(List<AudioBuffer> buffers) {
        var channels = buffers.getFirst().channels();
        var total = buffers.stream().mapToInt(AudioBuffer::frames).sum();
        var out = new float[total * channels];
        var position = 0;
        for (var buffer : buffers) {
            if (buffer.channels() != channels) {
                throw new AssertionError("buffers of " + channels + " and " + buffer.channels() + " channels");
            }
            for (var i = 0; i < buffer.frames(); i++) {
                for (var ch = 0; ch < channels; ch++) {
                    out[position++] = buffer.plane(ch)[i];
                }
            }
        }
        return out;
    }

    /**
     * {@return the interleaved samples of buffers as 24-bit samples in the high bits of 32-bit ones, as ffmpeg writes
     * its lossless output}
     */
    static int[] interleave32(List<AudioBuffer> buffers) {
        var samples = interleave(buffers);
        var out = new int[samples.length];
        for (var i = 0; i < samples.length; i++) {
            out[i] = Math.round(samples[i] * 8388608.0f) << 8;
        }
        return out;
    }

    /**
     * {@return the concatenated access units}
     */
    static byte[] stream(List<byte[]> units) {
        var out = new ByteArrayOutputStream();
        units.forEach(out::writeBytes);
        return out.toByteArray();
    }

    /**
     * Writes access units to a scratch file.
     *
     * @param name the file name, unique within the calling test class
     * @return the file
     */
    static Path write(String name, List<byte[]> units) throws IOException {
        var file = Scratch.directory("dts-units").resolve(name.replaceAll("[^A-Za-z0-9.-]", "-") + "-"
                + Thread.currentThread().threadId() + ".dts");
        Files.write(file, stream(units));
        return file;
    }

    /**
     * Runs ffmpeg on access units, written as a raw DTS stream.
     *
     * @param name the file name, unique within the calling test class
     * @param format the output sample format, {@code f32le} or {@code s32le}
     * @return the exit code and outputs
     */
    static Oracle.Result ffmpeg(String name, List<byte[]> units, String format) throws IOException {
        var file = write(name, units);
        return Oracle.execute(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), null, List.of("-cpuflags", "0", "-v",
                "error", "-f", "dts", "-i", file.toString(), "-f", format, "-"));
    }

    /**
     * {@return ffmpeg's floating-point output of access units, failing on an error}
     */
    static float[] ffmpegFloat(String name, List<byte[]> units) throws IOException {
        var result = ffmpeg(name, units, "f32le");
        if (result.exitCode() != 0) {
            throw new AssertionError("ffmpeg failed on " + name + ": " + result.stderr());
        }
        var samples = new float[result.stdout().length / 4];
        ByteBuffer.wrap(result.stdout()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(samples);
        return samples;
    }

    /**
     * {@return ffmpeg's 32-bit integer output of access units, failing on an error}
     */
    static int[] ffmpegInt(String name, List<byte[]> units) throws IOException {
        var result = ffmpeg(name, units, "s32le");
        if (result.exitCode() != 0) {
            throw new AssertionError("ffmpeg failed on " + name + ": " + result.stderr());
        }
        var samples = new int[result.stdout().length / 4];
        ByteBuffer.wrap(result.stdout()).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(samples);
        return samples;
    }
}
