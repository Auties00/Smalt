package com.github.auties00.smalt.container;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.avi.AviDemuxer;
import com.github.auties00.smalt.container.avi.AviMuxer;
import com.github.auties00.smalt.container.matroska.MatroskaDemuxer;
import com.github.auties00.smalt.container.matroska.MatroskaMuxer;
import com.github.auties00.smalt.container.matroska.MatroskaMuxerOptions;
import com.github.auties00.smalt.container.wav.WavDemuxer;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.audio.AudioPipeline;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the ADPCM and telephony tracks of {@link AviMuxer} and of {@link MatroskaMuxer}'s {@code A_MS/ACM} tracks:
 * the blocks and codes of WAVE files ffmpeg wrote (and the committed IMA and libgsm samples) are muxed, read back by
 * Smalt's demuxers packet for packet with the same track description, described by ffprobe (black box) with the
 * codec, rate, channels and block alignment of the source, and decoded by ffmpeg to the samples of the source file.
 * The block size taken from the first packet, the stream format rewritten when an AVI muxer finishes, the block size
 * a live Matroska header declares, the split of a Matroska frame of several blocks, and the rejection of packets
 * and tracks the format cannot describe run over packets the test assembles.
 */
@DisplayName("AVI and Matroska ADPCM and telephony tracks")
class AdpcmInterleavedMuxTest {
    private static final Path SAMPLES = Path.of("src", "test", "resources", "samples", "wav");

    /** A WAVE file of an ADPCM codec and ffmpeg's name of the codec. */
    record Source(String name, Supplier<byte[]> file, Codec codec, String ffmpegName) {
        @Override
        public String toString() {
            return name;
        }
    }

    /** A container the test writes. */
    enum Target {
        AVI("avi", AviMuxer::new, AviDemuxer::new),
        MATROSKA("mka", MatroskaMuxer::new, MatroskaDemuxer::new);

        final String extension;
        final Function<MediaSink, Muxer> muxer;
        final Function<MediaSource, Demuxer> demuxer;

        Target(String extension, Function<MediaSink, Muxer> muxer, Function<MediaSource, Demuxer> demuxer) {
            this.extension = extension;
            this.muxer = muxer;
            this.demuxer = demuxer;
        }
    }

    /** A track and its packets, as a demuxer returns them. */
    record Read(AudioTrack track, List<Packet> packets) {
    }

    static Supplier<byte[]> ffmpegWav(int rate, int channels, String... options) {
        return () -> {
            var arguments = new ArrayList<>(List.of("-ac", Integer.toString(channels)));
            arguments.addAll(List.of(options));
            arguments.addAll(List.of("-fflags", "+bitexact", "-flags:a", "+bitexact"));
            return Fixtures.read(Fixtures.ffmpeg("wav", List.of("-f", "lavfi", "-i",
                    "anoisesrc=color=pink:amplitude=0.4:sample_rate=" + rate + ":seed=5:duration=0.6"), arguments));
        };
    }

    static Supplier<byte[]> sample(String name) {
        return () -> {
            try {
                return Files.readAllBytes(SAMPLES.resolve(name));
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        };
    }

    static Stream<Source> sources() {
        return Stream.of(
                new Source("IMA mono 8 kHz", ffmpegWav(8000, 1, "-c:a", "adpcm_ima_wav"), Codec.ADPCM_IMA_WAV,
                        "adpcm_ima_wav"),
                new Source("IMA stereo 44.1 kHz, 2048-byte blocks", ffmpegWav(44100, 2, "-c:a", "adpcm_ima_wav",
                        "-block_size", "2048"), Codec.ADPCM_IMA_WAV, "adpcm_ima_wav"),
                new Source("IMA stereo 22.05 kHz sample", sample("ima-adpcm.wav"), Codec.ADPCM_IMA_WAV, "adpcm_ima_wav"),
                new Source("Microsoft ADPCM mono", ffmpegWav(8000, 1, "-c:a", "adpcm_ms"), Codec.ADPCM_MS, "adpcm_ms"),
                new Source("Microsoft ADPCM stereo, 512-byte blocks", ffmpegWav(22050, 2, "-c:a", "adpcm_ms",
                        "-block_size", "512"), Codec.ADPCM_MS, "adpcm_ms"),
                new Source("GSM 06.10 libgsm sample", sample("gsm.wav"), Codec.GSM_MS, "gsm_ms"),
                new Source("G.722", ffmpegWav(16000, 1, "-c:a", "g722"), Codec.ADPCM_G722, "adpcm_g722"),
                new Source("G.726 16 kbit/s", ffmpegWav(8000, 1, "-c:a", "g726", "-b:a", "16k"), Codec.ADPCM_G726,
                        "adpcm_g726"),
                new Source("G.726 24 kbit/s", ffmpegWav(8000, 1, "-c:a", "g726", "-b:a", "24k"), Codec.ADPCM_G726,
                        "adpcm_g726"),
                new Source("G.726 32 kbit/s", ffmpegWav(8000, 1, "-c:a", "g726", "-b:a", "32k"), Codec.ADPCM_G726,
                        "adpcm_g726"),
                new Source("G.726 40 kbit/s", ffmpegWav(8000, 1, "-c:a", "g726", "-b:a", "40k"), Codec.ADPCM_G726,
                        "adpcm_g726"));
    }

    static Stream<Arguments> cases() {
        return sources().flatMap(source -> Stream.of(Target.values()).map(target -> Arguments.of(source, target)));
    }

    static Read wav(byte[] file) {
        try (var demuxer = new WavDemuxer(MediaSource.of(file))) {
            var track = (AudioTrack) demuxer.tracks().getFirst();
            return new Read(track, AudioPipeline.packets(demuxer, track.id()));
        }
    }

    static Read demux(Target target, byte[] file) {
        try (var demuxer = target.demuxer.apply(MediaSource.of(file))) {
            var track = (AudioTrack) demuxer.tracks().getFirst();
            return new Read(track, AudioPipeline.packets(demuxer, track.id()));
        }
    }

    static byte[] mux(Target target, AudioTrack track, List<Packet> packets) {
        return MuxSupport.toMemory(List.of(track), packets, target.muxer);
    }

    static byte[] concatenate(List<Packet> packets) {
        var out = new ByteArrayOutputStream();
        packets.forEach(packet -> out.writeBytes(packet.data()));
        return out.toByteArray();
    }

    /** Decodes a file with ffmpeg to interleaved signed 16-bit little-endian samples. */
    static byte[] ffmpegDecode(String name, byte[] file) {
        var path = MuxSupport.save(name, file);
        return Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of("-v", "error", "-i", path.toString(), "-f",
                "s16le", "-"));
    }

    /** Returns the {@code fmt } chunk body of a WAVE file. */
    static byte[] format(byte[] wav) {
        var fields = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        var offset = 12;
        while (offset + 8 <= wav.length) {
            var size = fields.getInt(offset + 4);
            if (new String(wav, offset, 4).equals("fmt ")) {
                return Arrays.copyOfRange(wav, offset + 8, offset + 8 + size);
            }
            offset += 8 + size + (size & 1);
        }
        throw new AssertionError("no fmt chunk");
    }

    static AudioTrack track(Codec codec, int rate, int channels, int bits) {
        return AudioTrack.builder().id(1).codec(codec).timeBase(Rational.timeBaseOf(rate)).sampleRate(rate)
                .layout(ChannelLayout.defaultFor(channels)).bitsPerSample(bits).build();
    }

    static Packet packet(byte[] data, long pts, long duration) {
        return new Packet(1, data, pts, pts, duration, true);
    }

    /** Returns IMA ADPCM blocks with valid headers (sample, step index 0 to 88, zero byte) and random codes. */
    static byte[] imaBlock(long seed, int bytes, int channels) {
        var block = new byte[bytes];
        new Random(seed).nextBytes(block);
        for (var channel = 0; channel < channels; channel++) {
            block[channel * 4 + 2] = (byte) Math.floorMod(block[channel * 4 + 2], 89);
            block[channel * 4 + 3] = 0;
        }
        return block;
    }

    /** Returns IMA ADPCM blocks of one size, timed one after the other. */
    static List<Packet> imaBlocks(int count, int bytes, int channels) {
        var samples = 1 + (bytes - 4L * channels) * 2 / channels;
        var packets = new ArrayList<Packet>();
        for (var index = 0; index < count; index++) {
            packets.add(packet(imaBlock(index, bytes, channels), index * samples, samples));
        }
        return packets;
    }

    @Nested
    @DisplayName("files written by ffmpeg")
    class FfmpegFiles {
        @ParameterizedTest(name = "{0} in {1}")
        @MethodSource("com.github.auties00.smalt.container.AdpcmInterleavedMuxTest#cases")
        @DisplayName("muxes the blocks or codes, reads them back and decodes in ffmpeg to the source's samples")
        void roundTrip(Source source, Target target) {
            var original = source.file().get();
            var input = wav(original);
            var track = input.track();
            assertEquals(source.codec(), track.codec());
            var file = mux(target, track, input.packets());

            var output = demux(target, file);
            var read = output.track();
            assertEquals(track.codec(), read.codec());
            assertEquals(track.sampleRate(), read.sampleRate());
            assertEquals(track.layout().orElseThrow().channels(), read.layout().orElseThrow().channels());
            assertEquals(track.bitsPerSample(), read.bitsPerSample(), "bits per sample");
            assertArrayEquals(track.codecConfig().orElse(null), read.codecConfig().orElse(null), "configuration");
            assertEquals(input.packets().size(), output.packets().size(), "one packet per block or packet");
            var rate = Rational.timeBaseOf(track.sampleRate());
            for (var index = 0; index < input.packets().size(); index++) {
                var expected = input.packets().get(index);
                var actual = output.packets().get(index);
                assertArrayEquals(expected.data(), actual.data(), "packet " + index);
                var expectedPts = track.timeBase().rescale(expected.pts(), rate);
                var actualPts = read.timeBase().rescale(actual.pts(), rate);
                // Matroska stores milliseconds
                var tolerance = target == Target.MATROSKA ? track.sampleRate() / 1000 : 0;
                assertTrue(Math.abs(expectedPts - actualPts) <= tolerance, "pts of packet " + index + ": "
                        + expectedPts + " and " + actualPts);
            }

            var name = "adpcm-" + target.extension + "-" + source.name().replaceAll("[^A-Za-z0-9]+", "-");
            // the stream format (the strf chunk, the CodecPrivate) is the source's fmt chunk, but for the average
            // byte rate, which ffmpeg does not write as the Microsoft definitions give it
            var sourceFormat = format(original);
            var at = indexOf(file, Arrays.copyOf(sourceFormat, 8));
            assertArrayEquals(Arrays.copyOfRange(sourceFormat, 12, sourceFormat.length),
                    Arrays.copyOfRange(file, at + 12, at + sourceFormat.length),
                    "block alignment, bits per sample, cbSize and the extra bytes of the source");
            var path = MuxSupport.save(name + "." + target.extension, file);
            var probe = ContainerProbe.probe(path).streams().getFirst();
            var sourceProbe = ContainerProbe.probe(MuxSupport.save(name + "-source.wav", original)).streams().getFirst();
            assertEquals(source.ffmpegName(), probe.codecName());
            for (var key : List.of("codec_name", "sample_rate", "channels", "bits_per_sample")) {
                assertEquals(sourceProbe.get(key), probe.get(key), key);
            }

            var reference = ffmpegDecode(name + "-original.wav", original);
            var decoded = ffmpegDecode(name + "." + target.extension, file);
            var samples = input.packets().stream().mapToLong(Packet::duration).sum();
            var bytes = (int) (samples * 2 * track.layout().orElseThrow().channels());
            assertTrue(reference.length >= bytes && decoded.length >= bytes, "ffmpeg decodes every sample: "
                    + reference.length + " and " + decoded.length + " bytes of " + bytes);
            assertArrayEquals(Arrays.copyOf(reference, bytes), Arrays.copyOf(decoded, bytes),
                    "ffmpeg's decode of the written file");
            MuxSupport.assertFfmpegClean(name + "-clean." + target.extension, file);
        }
    }

    @Nested
    @DisplayName("writing")
    class Writing {
        @Test
        @DisplayName("supports the ADPCM and telephony codecs in AVI and Matroska, not in WebM")
        void supports() {
            for (var codec : List.of(Codec.ADPCM_IMA_WAV, Codec.ADPCM_MS, Codec.GSM_MS, Codec.ADPCM_G722,
                    Codec.ADPCM_G726)) {
                assertTrue(AviMuxer.supports(codec), codec.name());
                assertTrue(Containers.isMuxable(ContainerFormat.AVI, codec), codec.name());
                assertTrue(Containers.isMuxable(ContainerFormat.MATROSKA, codec), codec.name());
                assertTrue(!MatroskaMuxer.supports(com.github.auties00.smalt.container.matroska.MatroskaDocType.WEBM,
                        codec), codec.name());
            }
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Target.class)
        @DisplayName("declares the first packet's block size, for packets that arrive after another track's")
        void blockSizeOfFirstPacket(Target target) {
            var ima = track(Codec.ADPCM_IMA_WAV, 8000, 2, 4);
            var gsm = AudioTrack.builder().id(2).codec(Codec.GSM_MS).timeBase(Rational.timeBaseOf(8000))
                    .sampleRate(8000).layout(ChannelLayout.MONO).build();
            var packets = new ArrayList<Packet>();
            // the GSM track's packets come first, so the header is written before the IMA track's first block
            for (var index = 0; index < 4; index++) {
                var data = new byte[65];
                new Random(index).nextBytes(data);
                packets.add(new Packet(2, data, index * 320L, index * 320L, 320, true));
            }
            packets.addAll(imaBlocks(6, 1032, 2));
            var file = MuxSupport.toMemory(List.of(ima, gsm), packets, target.muxer);
            try (var demuxer = target.demuxer.apply(MediaSource.of(file))) {
                var tracks = demuxer.tracks();
                var read = (AudioTrack) tracks.stream().filter(each -> each.codec() == Codec.ADPCM_IMA_WAV)
                        .findFirst().orElseThrow();
                var blocks = AudioPipeline.packets(demuxer, read.id());
                assertEquals(6, blocks.size());
                for (var index = 0; index < 6; index++) {
                    assertArrayEquals(packets.get(4 + index).data(), blocks.get(index).data());
                    assertEquals(index * 1025L, read.timeBase().rescale(blocks.get(index).pts(),
                            Rational.timeBaseOf(8000)), 8, "pts of block " + index);
                }
            }
            assertEquals(1032, blockAlign(file, 0x0011), "the block alignment of the first block");
            MuxSupport.assertFfmpegClean("adpcm-first-block-clean." + target.extension, file);
        }

        @Test
        @DisplayName("writes dwScale, dwRate and dwSampleSize from the blocks, for the exact duration")
        void aviHeaders() {
            var file = mux(Target.AVI, track(Codec.ADPCM_MS, 22050, 2, 4), msBlocks(9, 512, 2));
            var fields = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
            var strh = indexOf(file, "strh") + 8;
            assertEquals(2 + (512 - 14), fields.getInt(strh + 20), "dwScale, the samples of a block");
            assertEquals(22050, fields.getInt(strh + 24), "dwRate, the sampling rate");
            assertEquals(9, fields.getInt(strh + 32), "dwLength, in blocks");
            assertEquals(512, fields.getInt(strh + 44), "dwSampleSize, the block alignment");
            var strf = indexOf(file, "strf") + 8;
            assertEquals(0x0002, fields.getShort(strf));
            assertEquals(512, fields.getShort(strf + 12), "nBlockAlign");
            assertEquals(4, fields.getShort(strf + 14), "wBitsPerSample");
            assertEquals(32, fields.getShort(strf + 16), "cbSize");
            assertEquals(2 + (512 - 14), fields.getShort(strf + 18), "wSamplesPerBlock");
            assertEquals(7, fields.getShort(strf + 20), "wNumCoef");
            assertEquals(22050L * 512 / 500, fields.getInt(strf + 8), "nAvgBytesPerSec");
            var read = demux(Target.AVI, file);
            assertEquals(9L * 500, read.track().timeBase().rescale(read.track().duration(), Rational.timeBaseOf(22050)));
        }

        @Test
        @DisplayName("declares 256 bytes per channel in a live Matroska header written before the first block")
        void liveMatroska() {
            var live = MatroskaMuxerOptions.builder().live(true).build();
            var sink = MediaSink.memory();
            var track = track(Codec.ADPCM_IMA_WAV, 8000, 1, 4);
            try (var muxer = new MatroskaMuxer(sink, live)) {
                muxer.addTrack(track);
                var blocks = imaBlocks(3, 256, 1);
                for (var block : blocks) {
                    muxer.writePacket(block);
                }
                assertThrows(IllegalArgumentException.class, () -> muxer.writePacket(packet(imaBlock(9, 516, 1),
                        3 * 505L, 1025)), "a block larger than the declared one");
                muxer.finish();
            }
            var read = demux(Target.MATROSKA, sink.toByteArray());
            assertEquals(3, read.packets().size());
            assertEquals(256, blockAlign(sink.toByteArray(), 0x0011));
            MuxSupport.assertFfmpegClean("adpcm-live.mka", sink.toByteArray());
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Target.class)
        @DisplayName("writes the coefficient table of a Microsoft ADPCM configuration")
        void msCoefficients(Target target) {
            // wSamplesPerBlock (wrong on purpose: the muxer writes the one of its blocks), wNumCoef, eight pairs
            var config = ByteBuffer.allocate(4 + 32).order(ByteOrder.LITTLE_ENDIAN).putShort((short) 1)
                    .putShort((short) 8);
            for (var value : new int[]{256, 0, 512, -256, 0, 0, 192, 64, 240, 0, 460, -208, 392, -232, 300, -50}) {
                config.putShort((short) value);
            }
            var bytes = config.array();
            var track = track(Codec.ADPCM_MS, 8000, 1, 4).toBuilder().codecConfig(bytes).build();
            var read = demux(target, mux(target, track, msBlocks(3, 256, 1))).track().codecConfig().orElseThrow();
            var expected = bytes.clone();
            expected[0] = (byte) (2 + (256 - 7) * 2);
            expected[1] = (byte) ((2 + (256 - 7) * 2) >> 8);
            assertArrayEquals(expected, read);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Target.class)
        @DisplayName("rejects tracks the formats cannot describe")
        void rejectsTracks(Target target) {
            assertThrows(IllegalArgumentException.class, () -> target.muxer.apply(MediaSink.memory())
                    .addTrack(track(Codec.ADPCM_G722, 16000, 2, 0)), "G.722 is mono");
            assertThrows(IllegalArgumentException.class, () -> target.muxer.apply(MediaSink.memory())
                    .addTrack(track(Codec.GSM_MS, 8000, 2, 0)), "GSM 06.10 is mono");
            for (var bits : new int[]{0, 1, 6}) {
                assertThrows(IllegalArgumentException.class, () -> target.muxer.apply(MediaSink.memory())
                        .addTrack(track(Codec.ADPCM_G726, 8000, 1, bits)), "G.726 of " + bits + " bits");
            }
            for (var codec : List.of(Codec.ADPCM_IMA_WAV, Codec.ADPCM_MS)) {
                assertThrows(IllegalArgumentException.class, () -> target.muxer.apply(MediaSink.memory())
                        .addTrack(track(codec, 8000, 1, 8)), codec + " of 8 bits");
            }
            var shortTable = track(Codec.ADPCM_MS, 8000, 1, 4).toBuilder().codecConfig(new byte[]{0, 1, 2, 0, 1, 2})
                    .build();
            assertThrows(IllegalArgumentException.class, () -> target.muxer.apply(MediaSink.memory())
                    .addTrack(shortTable), "a coefficient table shorter than declared");
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Target.class)
        @DisplayName("rejects packets that are not blocks or whole codes, leaving the stream as it was")
        void rejectsPackets(Target target) {
            assertRejected(target, track(Codec.ADPCM_IMA_WAV, 8000, 2, 4), List.of(imaBlock(1, 72, 2)),
                    imaBlock(2, 70, 2));
            assertRejected(target, track(Codec.ADPCM_IMA_WAV, 8000, 1, 4), List.of(imaBlock(1, 36, 1)),
                    imaBlock(2, 68, 1));
            assertRejected(target, track(Codec.ADPCM_IMA_WAV, 8000, 1, 4), List.of(imaBlock(1, 68, 1),
                    imaBlock(2, 36, 1)), imaBlock(3, 36, 1));
            assertRejected(target, track(Codec.ADPCM_IMA_WAV, 8000, 1, 4), List.of(), new byte[0]);
            assertRejected(target, track(Codec.ADPCM_MS, 8000, 1, 4), List.of(), new byte[6]);
            assertRejected(target, track(Codec.GSM_MS, 8000, 1, 0), List.of(new byte[65]), new byte[64]);
            assertRejected(target, track(Codec.ADPCM_G726, 8000, 1, 3), List.of(new byte[4]), new byte[3]);
        }

        @Test
        @DisplayName("splits a Matroska frame of several IMA ADPCM blocks into one packet per block")
        void matroskaFramesOfSeveralBlocks() {
            var blocks = imaBlocks(7, 256, 1);
            // frames of two blocks, then the last block alone, under a format that declares 256-byte blocks
            var frames = new ArrayList<Packet>();
            for (var index = 0; index < blocks.size(); index += 2) {
                var group = blocks.subList(index, Math.min(index + 2, blocks.size()));
                var first = group.getFirst();
                frames.add(packet(concatenate(group), first.pts(), group.size() * 505L));
            }
            var file = mux(Target.MATROSKA, track(Codec.ADPCM_IMA_WAV, 8000, 1, 4), frames);
            var prefix = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putShort((short) 0x0011)
                    .putShort((short) 1).putInt(8000).array();
            var fields = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
            var at = indexOf(file, prefix);
            assertEquals(512, fields.getShort(at + 12), "the muxer declares the first frame as the block");
            fields.putShort(at + 12, (short) 256);
            fields.putShort(at + 18, (short) 505);
            var read = demux(Target.MATROSKA, file);
            assertEquals(blocks.size(), read.packets().size(), "one packet per block");
            var rate = Rational.timeBaseOf(8000);
            for (var index = 0; index < blocks.size(); index++) {
                var actual = read.packets().get(index);
                assertArrayEquals(blocks.get(index).data(), actual.data(), "block " + index);
                if (index < 6) {
                    // a block split from a frame lasts its samples; the last frame, a single block, is not split
                    assertEquals(505, read.track().timeBase().rescale(actual.duration(), rate), "duration of " + index);
                }
                // the frames start on Matroska's millisecond timestamps, the blocks after them a block later
                assertEquals(index * 505L, read.track().timeBase().rescale(actual.pts(), rate), 8, "pts of " + index);
                if (index % 2 == 1) {
                    assertEquals(read.packets().get(index - 1).pts() + rate.rescale(505, read.track().timeBase()),
                            actual.pts(), "pts of " + index + " after the frame's first block");
                }
            }
        }

        private void assertRejected(Target target, AudioTrack track, List<byte[]> accepted, byte[] rejected) {
            var packets = new ArrayList<Packet>();
            var pts = 0L;
            for (var data : accepted) {
                packets.add(packet(data, pts, 100));
                pts += 100;
            }
            var sink = MediaSink.memory();
            try (var muxer = target.muxer.apply(sink)) {
                muxer.addTrack(track);
                packets.forEach(muxer::writePacket);
                var last = pts;
                assertThrows(IllegalArgumentException.class, () -> muxer.writePacket(packet(rejected, last, 100)),
                        track.codec() + " packet of " + rejected.length);
                muxer.finish();
            }
            var read = demux(target, sink.toByteArray());
            assertArrayEquals(concatenate(packets), concatenate(read.packets()), "a rejected packet is not written");
        }
    }

    /** Returns Microsoft ADPCM blocks with valid predictor indexes and random codes, timed one after the other. */
    static List<Packet> msBlocks(int count, int bytes, int channels) {
        var samples = 2 + (bytes - 7L * channels) * 2 / channels;
        var packets = new ArrayList<Packet>();
        for (var index = 0; index < count; index++) {
            var block = new byte[bytes];
            new Random(100 + index).nextBytes(block);
            for (var channel = 0; channel < channels; channel++) {
                block[channel] = (byte) Math.floorMod(block[channel], 7);
            }
            packets.add(packet(block, index * samples, samples));
        }
        return packets;
    }

    /** Returns the offset of the first occurrence of a four-character code. */
    static int indexOf(byte[] file, String fourCc) {
        return indexOf(file, fourCc.getBytes());
    }

    /** Returns the offset of the first occurrence of a byte pattern. */
    static int indexOf(byte[] file, byte[] pattern) {
        for (var offset = 0; offset + pattern.length <= file.length; offset++) {
            if (Arrays.equals(file, offset, offset + pattern.length, pattern, 0, pattern.length)) {
                return offset;
            }
        }
        throw new AssertionError("no " + Arrays.toString(pattern));
    }

    /** Returns the block alignment of the first mono or stereo 8 kHz {@code WAVEFORMATEX} of a format tag. */
    static int blockAlign(byte[] file, int tag) {
        for (var channels = 1; channels <= 2; channels++) {
            var prefix = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putShort((short) tag)
                    .putShort((short) channels).putInt(8000).array();
            try {
                var at = indexOf(file, prefix);
                return ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN).getShort(at + 12) & 0xFFFF;
            } catch (AssertionError missing) {
                // try the other channel count
            }
        }
        throw new AssertionError("no format of tag " + tag);
    }
}
