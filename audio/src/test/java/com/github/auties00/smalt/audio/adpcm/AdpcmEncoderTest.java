package com.github.auties00.smalt.audio.adpcm;

import com.github.auties00.smalt.audio.AudioDecoders;
import com.github.auties00.smalt.audio.AudioEncoderOptions;
import com.github.auties00.smalt.audio.AudioEncoders;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.telephony.TelephonyStreams;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.track.Codec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round trips of the IMA and Microsoft ADPCM encoder: every layout, channel count and block size decodes back near
 * the input, the blocks have the documented sizes and timestamps (a final block as short as the layout allows), the
 * WAVE layouts survive WAV and {@code ima4} survives AIFF-C and QuickTime, and the options validate the block size
 * against the codec. Against Windows' Audio Compression Manager (files in
 * {@code src/test/resources/com/github/auties00/smalt/audio/adpcm}, see its README), the IMA ADPCM blocks are
 * byte-identical to {@code imaadp32.acm}'s and the Microsoft ADPCM coding is more accurate than
 * {@code msadp32.acm}'s.
 */
@DisplayName("IMA and Microsoft ADPCM encoder")
class AdpcmEncoderTest {
    static Stream<Arguments> configurations() {
        var arguments = new ArrayList<Arguments>();
        for (var codec : new Codec[]{Codec.ADPCM_IMA_WAV, Codec.ADPCM_IMA_QT, Codec.ADPCM_MS}) {
            for (var channels = 1; channels <= 3; channels++) {
                arguments.add(Arguments.of(codec, channels, 8000));
                arguments.add(Arguments.of(codec, channels, 44100));
            }
        }
        return arguments.stream();
    }

    private static byte[] resource(String name) {
        try (var in = AdpcmEncoderTest.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + name);
            }
            return in.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /**
     * Returns the speech input of the ACM files: the mono signal, and for two channels that signal paired with
     * itself advanced by 37 samples, wrapping around.
     */
    private static int[][] speech(int channels) {
        var bytes = resource("/com/github/auties00/smalt/audio/gsm/speech8k.raw");
        var samples = new short[bytes.length / 2];
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples);
        var planes = new int[channels][samples.length];
        for (var c = 0; c < channels; c++) {
            for (var i = 0; i < samples.length; i++) {
                planes[c][i] = samples[(i + 37 * c) % samples.length];
            }
        }
        return planes;
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"ima-acm-mono.wav, 1, 256", "ima-acm-stereo.wav, 2, 512"})
    @DisplayName("IMA ADPCM blocks are byte-identical to those of Windows' imaadp32.acm")
    void imaMatchesAcm(String file, int channels, int blockBytes) {
        var reference = TelephonyStreams.demux(resource(file));
        assertEquals(Codec.ADPCM_IMA_WAV, reference.track().codec());
        var encoder = new AdpcmEncoder(AdpcmEncoderOptions.builder().codec(Codec.ADPCM_IMA_WAV)
                .format(8000, ChannelLayout.defaultFor(channels)).blockBytes(blockBytes).build());
        var packets = TelephonyStreams.encode(encoder, speech(channels));
        var expected = reference.packets();
        // the ACM codec writes only whole blocks, 31 of them, where the encoder also flushes the rest of the input
        assertEquals(31, expected.size());
        assertEquals(32, packets.size());
        for (var i = 0; i < expected.size(); i++) {
            assertArrayEquals(expected.get(i).data(), packets.get(i).data(), file + " block " + i);
        }
    }

    @Test
    @DisplayName("Microsoft ADPCM codes speech more accurately than Windows' msadp32.acm")
    void msBeatsAcm() {
        var reference = TelephonyStreams.demux(resource("ms-acm-mono.wav"));
        assertEquals(Codec.ADPCM_MS, reference.track().codec());
        // the decoder is bit-exact with msadp32.acm's (AdpcmDecoderTest), so this is the ACM codec's reconstruction
        var acm = TelephonyStreams.decodeShorts(new AdpcmDecoder(reference.track()), reference.packets());
        var input = speech(1);
        var encoder = new AdpcmEncoder(AdpcmEncoderOptions.builder().codec(Codec.ADPCM_MS)
                .format(8000, ChannelLayout.MONO).blockBytes(256).build());
        var ours = TelephonyStreams.decodeShorts(new AdpcmDecoder(TelephonyStreams.track(encoder)),
                TelephonyStreams.encode(encoder, input));
        assertEquals(input[0].length, acm.length);
        var original = TelephonyStreams.interleave(input);
        var acmSnr = TelephonyStreams.snr(original, acm);
        var ourSnr = TelephonyStreams.snr(original, Arrays.copyOf(ours, original.length));
        // measured: 19.1 dB for the ACM codec, 29.5 dB for Smalt's per-block predictor and delta search
        assertTrue(acmSnr > 15, "ACM SNR " + acmSnr);
        assertTrue(ourSnr >= acmSnr + 6, "SNR " + ourSnr + " against the ACM codec's " + acmSnr);
    }

    @ParameterizedTest(name = "{0}, {1} channels at {2} Hz")
    @MethodSource("configurations")
    @DisplayName("decodes back near the input in blocks of the documented size")
    void roundTrip(Codec codec, int channels, int rate) {
        var frames = rate / 2 + 123;
        var input = TelephonyStreams.signal(channels, rate, frames, 0.6);
        var options = AdpcmEncoderOptions.of(codec, rate, ChannelLayout.defaultFor(channels));
        var encoder = new AdpcmEncoder(options);
        var packets = TelephonyStreams.encode(encoder, input);
        var block = codec == Codec.ADPCM_IMA_QT ? 34 * channels : options.resolvedBlockBytes();
        var expectedBlock = codec == Codec.ADPCM_IMA_QT ? 34 * channels
                : 256 * channels * Math.max(1, rate / 11025);
        assertEquals(expectedBlock, block);
        var time = 0L;
        for (var index = 0; index < packets.size(); index++) {
            var packet = packets.get(index);
            assertEquals(time, packet.pts());
            if (index < packets.size() - 1) {
                assertEquals(block, packet.size(), "block " + index);
                assertEquals(encoder.frameSize(), packet.duration());
            }
            time += packet.duration();
        }
        assertEquals(frames, time);
        var decoder = AudioDecoders.create(TelephonyStreams.track(encoder));
        assertInstanceOf(AdpcmDecoder.class, decoder);
        var decoded = TelephonyStreams.decodeShorts(decoder, packets);
        assertTrue(decoded.length >= frames * channels);
        var original = TelephonyStreams.interleave(input);
        var snr = TelephonyStreams.snr(original, java.util.Arrays.copyOf(decoded, original.length));
        assertTrue(snr >= 15, codec + " SNR " + snr);
    }

    @ParameterizedTest(name = "{0} input samples")
    @CsvSource({"1, 1", "2, 9", "9, 9", "10, 17", "1017, 1017"})
    @DisplayName("an IMA ADPCM final block holds the fewest groups of eight samples that cover the input")
    void imaTail(int samples, int coded) {
        var encoder = new AdpcmEncoder(AdpcmEncoderOptions.builder().codec(Codec.ADPCM_IMA_WAV)
                .format(8000, ChannelLayout.MONO).blockBytes(512).build());
        var packets = TelephonyStreams.encode(encoder, TelephonyStreams.signal(1, 8000, samples, 0.5));
        var last = packets.getLast();
        assertEquals(4 * (1 + (coded - 1) / 8), last.size());
        assertEquals(samples == 1017 ? 1017 : samples, last.duration());
        assertEquals(coded, TelephonyStreams.decodeShorts(new AdpcmDecoder(TelephonyStreams.track(encoder)),
                java.util.List.of(last)).length);
    }

    @ParameterizedTest(name = "{0} channels, {1} input samples")
    @CsvSource({"1, 1, 2", "1, 5, 6", "1, 6, 6", "3, 3, 4", "3, 4, 4", "2, 7, 7"})
    @DisplayName("a Microsoft ADPCM final block holds whole bytes of codes")
    void msTail(int channels, int samples, int coded) {
        var encoder = new AdpcmEncoder(AdpcmEncoderOptions.of(Codec.ADPCM_MS, 8000,
                ChannelLayout.defaultFor(channels)));
        var packets = TelephonyStreams.encode(encoder, TelephonyStreams.signal(channels, 8000, samples, 0.5));
        var last = packets.getLast();
        assertEquals(7 * channels + (coded - 2) * channels / 2, last.size());
        assertEquals(samples, last.duration());
    }

    @Test
    @DisplayName("the Microsoft ADPCM configuration states the block's samples and the standard coefficients")
    void msConfiguration() {
        var encoder = new AdpcmEncoder(AdpcmEncoderOptions.of(Codec.ADPCM_MS, 22050, ChannelLayout.STEREO));
        var config = encoder.codecConfig().orElseThrow();
        assertEquals(32, config.length);
        assertEquals(encoder.frameSize(), (config[0] & 0xFF) | (config[1] & 0xFF) << 8);
        assertEquals(1012, encoder.frameSize());
        assertEquals(7, config[2]);
        assertEquals(256, (config[4] & 0xFF) | config[5] << 8);
        assertEquals(-232, (short) ((config[30] & 0xFF) | (config[31] & 0xFF) << 8));
        assertTrue(new AdpcmEncoder(AdpcmEncoderOptions.of(Codec.ADPCM_IMA_WAV, 8000, ChannelLayout.MONO))
                .codecConfig().isEmpty());
    }

    @ParameterizedTest(name = "{0} in {1}")
    @CsvSource({"ADPCM_IMA_WAV, WAV", "ADPCM_MS, WAV", "ADPCM_IMA_QT, AIFF", "ADPCM_IMA_QT, MP4"})
    @DisplayName("a round trip through a container decodes to the same samples")
    void containers(Codec codec, ContainerFormat format) {
        var encoder = new AdpcmEncoder(AdpcmEncoderOptions.of(codec, 8000, ChannelLayout.STEREO));
        var packets = TelephonyStreams.encode(encoder, TelephonyStreams.signal(2, 8000, 6000, 0.5));
        var track = TelephonyStreams.track(encoder);
        var back = TelephonyStreams.demux(TelephonyStreams.mux(format, track, packets));
        assertEquals(codec, back.track().codec());
        var expected = TelephonyStreams.decodeShorts(new AdpcmDecoder(track), packets);
        var actual = TelephonyStreams.decodeShorts(new AdpcmDecoder(back.track()), back.packets());
        assertArrayEquals(java.util.Arrays.copyOf(expected, actual.length), actual);
        assertTrue(actual.length >= 6000 * 2);
    }

    @Test
    @DisplayName("the codec-independent options select the layout and format")
    void provider() {
        var encoder = AudioEncoders.create(Codec.ADPCM_MS, AudioEncoderOptions.of(11025, ChannelLayout.MONO));
        assertInstanceOf(AdpcmEncoder.class, encoder);
        assertEquals(Codec.ADPCM_MS, encoder.codec());
        assertEquals(4, encoder.bitsPerSample());
        assertEquals(2 + 2 * (256 - 7), encoder.frameSize());
    }

    @Nested
    @DisplayName("options")
    class Options {
        @Test
        @DisplayName("round trip through their builder")
        void builder() {
            var options = AdpcmEncoderOptions.builder().codec(Codec.ADPCM_MS).format(16000, ChannelLayout.STEREO)
                    .blockBytes(14 + 2 * 100).build();
            assertEquals(options, options.toBuilder().build());
            assertEquals(214, options.blockBytes().orElseThrow());
            assertTrue(options.toBuilder().automaticBlockBytes().build().blockBytes().isEmpty());
            assertEquals(Codec.ADPCM_IMA_WAV, AdpcmEncoderOptions.defaults().codec());
        }

        @Test
        @DisplayName("reject a block size the codec cannot use")
        void blockSize() {
            assertThrows(IllegalArgumentException.class, () -> AdpcmEncoderOptions.builder()
                    .codec(Codec.ADPCM_IMA_WAV).layout(ChannelLayout.STEREO).blockBytes(36).build());
            assertThrows(IllegalArgumentException.class, () -> AdpcmEncoderOptions.builder()
                    .codec(Codec.ADPCM_MS).layout(ChannelLayout.defaultFor(3)).blockBytes(22).build());
            assertThrows(IllegalArgumentException.class, () -> AdpcmEncoderOptions.builder().blockBytes(0));
            assertThrows(IllegalArgumentException.class, () -> AdpcmEncoderOptions.builder().codec(Codec.GSM));
            assertThrows(IllegalArgumentException.class, () -> AdpcmEncoderOptions.builder()
                    .layout(ChannelLayout.defaultFor(9)));
        }

        @Test
        @DisplayName("reject a block of more samples than wSamplesPerBlock can state")
        void blockSamples() {
            // mono IMA ADPCM: 32764 bytes code 1 + 8190 * 8 = 65521 samples, 32768 bytes 65529, 32772 bytes 65537
            AdpcmEncoderOptions.builder().codec(Codec.ADPCM_IMA_WAV).layout(ChannelLayout.MONO).blockBytes(32768)
                    .build();
            assertThrows(IllegalArgumentException.class, () -> AdpcmEncoderOptions.builder()
                    .codec(Codec.ADPCM_IMA_WAV).layout(ChannelLayout.MONO).blockBytes(32772).build());
            // mono Microsoft ADPCM: 32773 bytes code 2 + 2 * 32766 = 65534 samples, 32774 bytes 65536
            AdpcmEncoderOptions.builder().codec(Codec.ADPCM_MS).layout(ChannelLayout.MONO).blockBytes(32773).build();
            assertThrows(IllegalArgumentException.class, () -> AdpcmEncoderOptions.builder()
                    .codec(Codec.ADPCM_MS).layout(ChannelLayout.MONO).blockBytes(32774).build());
            assertThrows(IllegalArgumentException.class, () -> AdpcmEncoderOptions.builder()
                    .codec(Codec.ADPCM_MS).layout(ChannelLayout.MONO).blockBytes(65535).build());
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({"ADPCM_IMA_WAV", "ADPCM_MS"})
        @DisplayName("keep the automatic block of a very high rate within wSamplesPerBlock")
        void automaticBlockSamples(Codec codec) {
            var encoder = new AdpcmEncoder(AdpcmEncoderOptions.of(codec, 2_822_400, ChannelLayout.MONO));
            assertTrue(encoder.frameSize() <= 0xFFFF, "frame size " + encoder.frameSize());
            assertTrue(encoder.frameSize() > 0xFFFF - 1024, "frame size " + encoder.frameSize());
        }
    }
}
