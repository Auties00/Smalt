package com.github.auties00.smalt.audio.telephony;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioDecoder;
import com.github.auties00.smalt.audio.AudioEncoder;
import com.github.auties00.smalt.audio.IntegerAudioBuffer;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.Muxer;
import com.github.auties00.smalt.container.mp4.Mp4Muxer;
import com.github.auties00.smalt.container.mp4.Mp4MuxerOptions;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Consumer;

/**
 * Helpers of the ADPCM and telephony codec tests: demuxing a file into its first audio track and packets, decoding
 * packets to interleaved 16-bit or float samples, encoding planar samples, muxing a track, a deterministic speech-like
 * test signal, and SHA-256 digests of 16-bit little-endian PCM.
 */
public final class TelephonyStreams {
    private TelephonyStreams() {
    }

    /** A track and its packets. */
    public record Stream(AudioTrack track, List<Packet> packets) {
    }

    public static Stream demux(byte[] file) {
        try (var demuxer = Containers.openDemuxer(MediaSource.of(file))) {
            var track = (AudioTrack) demuxer.tracks().stream().filter(t -> t instanceof AudioTrack).findFirst()
                    .orElseThrow();
            var packets = new ArrayList<Packet>();
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                if (packet.trackId() == track.id()) {
                    packets.add(packet);
                }
            }
            return new Stream(track, packets);
        }
    }

    public static Stream demux(Path file) {
        try {
            return demux(Files.readAllBytes(file));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** Decodes packets through the integer output, interleaving the channels. */
    public static short[] decodeShorts(AudioDecoder decoder, List<Packet> packets) {
        var out = new ArrayList<short[]>();
        var total = 0;
        for (var packet : packets) {
            var holder = new short[1][];
            decoder.decodeIntegers(packet, block -> {
                var channels = block.layout().channels();
                var frames = block.frames();
                var interleaved = new short[frames * channels];
                for (var c = 0; c < channels; c++) {
                    var plane = block.planes()[c];
                    for (var i = 0; i < frames; i++) {
                        interleaved[i * channels + c] = (short) plane[i];
                    }
                }
                holder[0] = interleaved;
            });
            if (holder[0] != null) {
                out.add(holder[0]);
                total += holder[0].length;
            }
        }
        var result = new short[total];
        var position = 0;
        for (var block : out) {
            System.arraycopy(block, 0, result, position, block.length);
            position += block.length;
        }
        return result;
    }

    /** Decodes packets through the float output, interleaving the channels. */
    public static float[] decodeFloats(AudioDecoder decoder, List<Packet> packets) {
        var out = new ArrayList<Float>();
        Consumer<AudioBuffer> sink = block -> {
            var channels = block.layout().channels();
            for (var i = 0; i < block.frames(); i++) {
                for (var c = 0; c < channels; c++) {
                    out.add(block.plane(c)[i]);
                }
            }
        };
        for (var packet : packets) {
            decoder.decode(packet, sink);
        }
        decoder.flush(sink);
        var result = new float[out.size()];
        for (var i = 0; i < result.length; i++) {
            result[i] = out.get(i);
        }
        return result;
    }

    /** Encodes planar 16-bit samples through the integer input, in blocks of 1000 samples, and flushes. */
    public static List<Packet> encode(AudioEncoder encoder, int[][] planes) {
        var packets = new ArrayList<Packet>();
        var frames = planes[0].length;
        for (var start = 0; start < frames; start += 1000) {
            var count = Math.min(1000, frames - start);
            var block = new int[planes.length][count];
            for (var c = 0; c < planes.length; c++) {
                System.arraycopy(planes[c], start, block[c], 0, count);
            }
            encoder.encode(new IntegerAudioBuffer(encoder.layout(), encoder.sampleRate(), 16, block, count),
                    packets::add);
        }
        encoder.flush(packets::add);
        return packets;
    }

    /** Returns the track an encoder's packets belong to. */
    public static AudioTrack track(AudioEncoder encoder) {
        return AudioTrack.builder().id(1).codec(encoder.codec()).timeBase(Rational.timeBaseOf(encoder.sampleRate()))
                .sampleRate(encoder.sampleRate()).layout(encoder.layout()).bitsPerSample(encoder.bitsPerSample())
                .codecConfig(encoder.codecConfig().orElse(null)).build();
    }

    /** Writes a track and its packets into a file of a container. */
    public static byte[] mux(ContainerFormat format, AudioTrack track, List<Packet> packets) {
        var sink = MediaSink.memory();
        try (Muxer muxer = format == ContainerFormat.MP4
                ? new Mp4Muxer(sink, Mp4MuxerOptions.builder().brand(Mp4MuxerOptions.Brand.QUICKTIME).build())
                : Containers.openMuxer(format, sink)) {
            muxer.addTrack(track);
            for (var packet : packets) {
                muxer.writePacket(packet.withTrackId(track.id()));
            }
            muxer.finish();
        }
        return sink.toByteArray();
    }

    /** Returns a deterministic speech-like signal: a gliding harmonic tone under an envelope, plus noise. */
    public static int[][] signal(int channels, int rate, int frames, double peak) {
        var planes = new int[channels][frames];
        var seed = 0x2545F4914F6CDD1DL;
        for (var c = 0; c < channels; c++) {
            var phase = 0.0;
            for (var i = 0; i < frames; i++) {
                var t = (double) i / rate;
                var pitch = 120 + 60 * StrictMath.sin(2 * Math.PI * 0.7 * t) + 40 * c;
                phase += 2 * Math.PI * pitch / rate;
                var envelope = 0.55 + 0.45 * StrictMath.sin(2 * Math.PI * 3.1 * t + c);
                var value = 0.0;
                for (var harmonic = 1; harmonic <= 6; harmonic++) {
                    value += StrictMath.sin(harmonic * phase) / harmonic;
                }
                seed ^= seed << 13;
                seed ^= seed >>> 7;
                seed ^= seed << 17;
                var noise = ((seed >>> 11) * 0x1p-53 - 0.5) * 0.05;
                planes[c][i] = (int) Math.round(Math.max(-1, Math.min(1, (value * 0.45 * envelope + noise) * peak))
                        * 32767);
            }
        }
        return planes;
    }

    /** Interleaves planar samples to 16-bit words. */
    public static short[] interleave(int[][] planes) {
        var frames = planes[0].length;
        var out = new short[frames * planes.length];
        for (var i = 0; i < frames; i++) {
            for (var c = 0; c < planes.length; c++) {
                out[i * planes.length + c] = (short) planes[c][i];
            }
        }
        return out;
    }

    /** Converts 16-bit samples to the floats a decoder's float output holds. */
    public static float[] floats(short[] samples) {
        var out = new float[samples.length];
        for (var i = 0; i < samples.length; i++) {
            out[i] = samples[i] * 0x1p-15f;
        }
        return out;
    }

    /** Returns the SHA-256 of samples as 16-bit little-endian PCM, in lowercase hex. */
    public static String sha256(short[] samples) {
        var bytes = ByteBuffer.allocate(samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        bytes.asShortBuffer().put(samples);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.array()));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** Returns the signal-to-noise ratio of a reconstruction, in decibels. */
    public static double snr(short[] reference, short[] actual) {
        var signal = 0.0;
        var noise = 0.0;
        var n = Math.min(reference.length, actual.length);
        for (var i = 0; i < n; i++) {
            signal += (double) reference[i] * reference[i];
            var difference = (double) reference[i] - actual[i];
            noise += difference * difference;
        }
        return noise == 0 ? Double.POSITIVE_INFINITY : 10 * Math.log10(signal / noise);
    }
}
