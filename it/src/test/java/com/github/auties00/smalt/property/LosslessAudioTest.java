package com.github.auties00.smalt.property;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioDecoders;
import com.github.auties00.smalt.audio.AudioEncoderOptions;
import com.github.auties00.smalt.audio.AudioEncoders;
import com.github.auties00.smalt.audio.AudioReader;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.flac.FlacEncoder;
import com.github.auties00.smalt.audio.flac.FlacEncoderOptions;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.transcode.OutputContainer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Lossless audio coding is the identity: FLAC (every sample size from 4 to 32 bits, one to eight channels, every
 * compression level, block sizes from the smallest to the largest) and ALAC (encoded by ffmpeg, which Smalt does not
 * encode) decode to every input sample, and so does every integer and floating-point PCM codec through every
 * container that carries it. The inputs are seeded sweeps and noise quantized to the codec's grid, silence,
 * full-scale squares, alternating extremes and lone impulses, of lengths from one sample to several blocks.
 */
@DisplayName("Lossless audio round trips")
class LosslessAudioTest {
    enum Content {
        SWEEP, SILENCE, SQUARE, EXTREMES, IMPULSE
    }

    record Signal(ChannelLayout layout, int sampleRate, int frames, int bits, Content content, long seed) {
        @Override
        public String toString() {
            return layout.channels() + " ch " + sampleRate + " Hz " + frames + " frames " + bits + "-bit "
                    + content.name().toLowerCase();
        }

        float[][] planes() {
            var scale = 1 << Math.min(bits, 24) - 1;
            if (content == Content.SWEEP) {
                return Streams.samples(layout, sampleRate, frames, Math.min(bits, 24), seed);
            }
            var random = new Random(seed);
            var planes = new float[layout.channels()][frames];
            for (var channel = 0; channel < planes.length; channel++) {
                for (var index = 0; index < frames; index++) {
                    var code = switch (content) {
                        case SILENCE -> 0;
                        case SQUARE -> (index / 7 + channel) % 2 == 0 ? scale - 1 : -scale;
                        case EXTREMES -> (index + channel) % 2 == 0 ? scale - 1 : -scale;
                        case IMPULSE -> index == frames / 2 ? (random.nextBoolean() ? scale - 1 : -scale) : 0;
                        default -> throw new AssertionError();
                    };
                    planes[channel][index] = code / (float) scale;
                }
            }
            return planes;
        }
    }

    static Stream<Arguments> flac() {
        var arguments = new ArrayList<Arguments>();
        var seed = 0L;
        var layouts = List.of(ChannelLayout.MONO, ChannelLayout.STEREO, ChannelLayout.defaultFor(3),
                ChannelLayout.SURROUND_5_1, ChannelLayout.SURROUND_7_1);
        for (var bits : new int[]{4, 8, 12, 16, 20, 24, 32}) {
            for (var layout : layouts) {
                for (var content : Content.values()) {
                    var frames = new int[]{1, 15, 4096, 10_007}[(int) (seed % 4)];
                    var rate = new int[]{8000, 44_100, 96_000, 192_000}[(int) (seed / 4 % 4)];
                    var level = (int) (seed % 9);
                    arguments.add(Arguments.of(new Signal(layout, rate, frames, bits, content, seed), level));
                    seed++;
                }
            }
        }
        return arguments.stream();
    }

    static Stream<Arguments> flacBlockSizes() {
        return Stream.of(16, 192, 576, 4608, 32_768, 65_535).map(size -> Arguments.of(size,
                new Signal(ChannelLayout.STEREO, 44_100, 70_000, 16, Content.SWEEP, size)));
    }

    @Nested
    @DisplayName("FLAC")
    class Flac {
        @ParameterizedTest(name = "{0} level {1}")
        @MethodSource("com.github.auties00.smalt.property.LosslessAudioTest#flac")
        @DisplayName("decodes to every input sample")
        void identity(Signal signal, int level) {
            var options = FlacEncoderOptions.ofCompressionLevel(level).toBuilder()
                    .format(signal.sampleRate(), signal.layout())
                    .bitsPerSample(signal.bits())
                    .build();
            assertCodecIdentity(signal.toString(), signal.planes(), new FlacEncoder(options));
        }

        @ParameterizedTest(name = "block size {0}")
        @MethodSource("com.github.auties00.smalt.property.LosslessAudioTest#flacBlockSizes")
        @DisplayName("decodes to every input sample, whatever the block size, also from a FLAC and an Ogg file")
        void blockSizes(int blockSize, Signal signal) {
            var options = FlacEncoderOptions.builder().format(signal.sampleRate(), signal.layout())
                    .bitsPerSample(signal.bits()).blockSize(blockSize).build();
            var planes = signal.planes();
            var encoded = assertCodecIdentity("block size " + blockSize, planes, new FlacEncoder(options));
            for (var container : List.of(OutputContainer.FLAC, OutputContainer.OGG_FLAC, OutputContainer.MATROSKA,
                    OutputContainer.MP4)) {
                assertArrayEquals(flatten(planes), flatten(read(mux(container, encoded))),
                        container + " block size " + blockSize);
            }
        }
    }

    static Stream<Arguments> alac() {
        return Stream.of(
                Arguments.of("s16p", 16, ChannelLayout.MONO), Arguments.of("s16p", 16, ChannelLayout.STEREO),
                Arguments.of("s32p", 24, ChannelLayout.STEREO), Arguments.of("s32p", 24, ChannelLayout.SURROUND_5_1),
                Arguments.of("s16p", 16, ChannelLayout.defaultFor(3)));
    }

    @Nested
    @DisplayName("ALAC encoded by ffmpeg")
    class Alac {
        @ParameterizedTest(name = "{1}-bit {2}")
        @MethodSource("com.github.auties00.smalt.property.LosslessAudioTest#alac")
        @DisplayName("decodes to every sample of the WAV it was encoded from")
        void identity(String sampleFormat, int bits, ChannelLayout layout) {
            OracleEncoders.assumeFfmpegEncoder("alac");
            var planes = new Signal(layout, 44_100, 30_000, bits, Content.SWEEP, bits * 10L + layout.channels())
                    .planes();
            var codec = bits == 16 ? Codec.PCM_S16LE : Codec.PCM_S24LE;
            var wav = OracleEncoders.write("alac-" + bits + "-" + layout.channels() + ".wav",
                    mux(OutputContainer.WAV, pcm(codec, planes, 44_100, layout)));
            var output = OracleEncoders.path("alac-" + bits + "-" + layout.channels() + ".m4a");
            OracleEncoders.ffmpeg(List.of("-i", wav.toString(), "-c:a", "alac", "-sample_fmt", sampleFormat), output);
            assertArrayEquals(flatten(planes), flatten(read(OracleEncoders.read(output))), "ALAC " + layout);
        }
    }

    static Stream<Arguments> pcm() {
        var arguments = new ArrayList<Arguments>();
        for (var codec : Codec.values()) {
            var bits = pcmBits(codec);
            if (bits == 0) {
                continue;
            }
            for (var container : OutputContainer.values()) {
                if (container.supports(codec)) {
                    arguments.add(Arguments.of(codec, container, bits));
                }
            }
        }
        return arguments.stream();
    }

    @Nested
    @DisplayName("PCM")
    class Pcm {
        @ParameterizedTest(name = "{0} in {1}")
        @MethodSource("com.github.auties00.smalt.property.LosslessAudioTest#pcm")
        @DisplayName("reads back every sample through the container")
        void identity(Codec codec, OutputContainer container, int bits) {
            var layout = ChannelLayout.SURROUND_5_1;
            var planes = new Signal(layout, 48_000, 9000, bits, Content.SWEEP, codec.ordinal()).planes();
            if (bits == 0x7FFF) {
                planes = floatPlanes(layout.channels(), 9000, codec.ordinal());
            }
            var data = mux(container, pcm(codec, planes, 48_000, layout));
            assertArrayEquals(flatten(planes), flatten(read(data)), codec + " in " + container);
        }
    }

    /** Returns the sample size whose grid a PCM codec holds exactly, {@code 0x7FFF} for any float, 0 if lossy. */
    private static int pcmBits(Codec codec) {
        return switch (codec) {
            case PCM_U8, PCM_S8 -> 8;
            case PCM_S16LE, PCM_S16BE -> 16;
            case PCM_S24LE, PCM_S24BE, PCM_S32LE, PCM_S32BE, PCM_S64LE, PCM_S64BE -> 24;
            case PCM_F32LE, PCM_F32BE, PCM_F64LE, PCM_F64BE -> 0x7FFF;
            default -> 0;
        };
    }

    private static float[][] floatPlanes(int channels, int frames, long seed) {
        var random = new Random(seed);
        var planes = new float[channels][frames];
        for (var plane : planes) {
            for (var index = 0; index < frames; index++) {
                plane[index] = random.nextFloat() * 2 - 1;
            }
        }
        return planes;
    }

    /** Encodes planes with an encoder, decodes the packets and checks the identity; returns the encoded stream. */
    static Streams.Encoded assertCodecIdentity(String what, float[][] planes,
                                               com.github.auties00.smalt.audio.AudioEncoder encoder) {
        var packets = new ArrayList<Packet>();
        var frames = planes[0].length;
        try (encoder) {
            var block = 1000;
            for (var start = 0; start < frames; start += block) {
                var count = Math.min(block, frames - start);
                var chunk = new float[planes.length][count];
                for (var channel = 0; channel < planes.length; channel++) {
                    System.arraycopy(planes[channel], start, chunk[channel], 0, count);
                }
                encoder.encode(new AudioBuffer(encoder.layout(), encoder.sampleRate(), chunk, count), packets::add);
            }
            encoder.flush(packets::add);
            var track = AudioTrack.builder().id(2).codec(encoder.codec()).timeBase(new Rational(1, encoder.sampleRate()))
                    .sampleRate(encoder.sampleRate()).layout(encoder.layout());
            encoder.codecConfig().ifPresent(track::codecConfig);
            var encoded = new Streams.Encoded(track.build(), packets.stream().map(packet -> packet.withTrackId(2))
                    .toList());
            assertArrayEquals(flatten(planes), flatten(decode(encoded)), what);
            return encoded;
        }
    }

    static Streams.Encoded pcm(Codec codec, float[][] planes, int sampleRate, ChannelLayout layout) {
        var encoder = AudioEncoders.create(codec, AudioEncoderOptions.builder().format(sampleRate, layout).build());
        var packets = new ArrayList<Packet>();
        try (encoder) {
            encoder.encode(new AudioBuffer(layout, sampleRate, planes, planes[0].length), packets::add);
            encoder.flush(packets::add);
            var track = AudioTrack.builder().id(2).codec(codec).timeBase(new Rational(1, sampleRate))
                    .sampleRate(sampleRate).layout(layout);
            encoder.codecConfig().ifPresent(track::codecConfig);
            return new Streams.Encoded(track.build(), packets.stream().map(packet -> packet.withTrackId(2)).toList());
        }
    }

    static byte[] mux(OutputContainer container, Streams.Encoded stream) {
        var sink = MediaSink.memory();
        try (var muxer = Containers.openMuxer(container, sink)) {
            muxer.addTrack(stream.track());
            stream.packets().forEach(muxer::writePacket);
            muxer.finish();
        }
        var data = sink.toByteArray();
        com.github.auties00.smalt.testkit.validate.Validators.assertValid("lossless-" + container.name().toLowerCase(java.util.Locale.ROOT) + "."
                + container.extension(), data);
        return data;
    }

    static float[][] decode(Streams.Encoded stream) {
        var collected = new ArrayList<float[][]>();
        try (var decoder = AudioDecoders.create((AudioTrack) stream.track())) {
            java.util.function.Consumer<AudioBuffer> sink = buffer -> collected.add(copy(buffer));
            stream.packets().forEach(packet -> decoder.decode(packet, sink));
            decoder.flush(sink);
        }
        return join(collected);
    }

    static float[][] read(byte[] file) {
        var collected = new ArrayList<float[][]>();
        try (var reader = AudioReader.open(MediaSource.of(file))) {
            for (var buffer = reader.read(); buffer != null; buffer = reader.read()) {
                collected.add(copy(buffer));
            }
        }
        return join(collected);
    }

    private static float[][] copy(AudioBuffer buffer) {
        var planes = new float[buffer.channels()][];
        for (var channel = 0; channel < planes.length; channel++) {
            planes[channel] = java.util.Arrays.copyOf(buffer.plane(channel), buffer.frames());
        }
        return planes;
    }

    private static float[][] join(List<float[][]> blocks) {
        if (blocks.isEmpty()) {
            return new float[0][0];
        }
        var channels = blocks.getFirst().length;
        var total = blocks.stream().mapToInt(block -> block[0].length).sum();
        var planes = new float[channels][total];
        var at = 0;
        for (var block : blocks) {
            assertEquals(channels, block.length, "channel count changed");
            for (var channel = 0; channel < channels; channel++) {
                System.arraycopy(block[channel], 0, planes[channel], at, block[channel].length);
            }
            at += block[0].length;
        }
        return planes;
    }

    /** Returns the planes one after the other, with a leading channel count so that layouts differ visibly. */
    static float[] flatten(float[][] planes) {
        var frames = planes.length == 0 ? 0 : planes[0].length;
        var all = new float[1 + planes.length * frames];
        all[0] = planes.length;
        for (var channel = 0; channel < planes.length; channel++) {
            System.arraycopy(planes[channel], 0, all, 1 + channel * frames, frames);
        }
        return all;
    }
}
