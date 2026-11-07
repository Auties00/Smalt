package com.github.auties00.smalt.audio.telephony;

import com.github.auties00.smalt.audio.AudioEncoder;
import com.github.auties00.smalt.audio.AudioDecoders;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.adpcm.AdpcmEncoder;
import com.github.auties00.smalt.audio.adpcm.AdpcmEncoderOptions;
import com.github.auties00.smalt.audio.g722.G722Encoder;
import com.github.auties00.smalt.audio.g726.G726Encoder;
import com.github.auties00.smalt.audio.g726.G726EncoderOptions;
import com.github.auties00.smalt.audio.gsm.GsmEncoder;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.track.Codec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compares the ADPCM and telephony decoders with ffmpeg's, as a black box, in both directions: files ffmpeg encodes
 * (IMA ADPCM in WAV, AIFF-C, QuickTime, AVI and Matroska, Microsoft ADPCM in WAV, QuickTime, AVI and Matroska,
 * G.722 and G.726 in WAV) and files Smalt encodes (every codec, GSM 06.10 included, which the oracle cannot encode)
 * decode to ffmpeg's samples within the tolerance each codec declares.
 *
 * <p>The tolerances: {@code ima4} and GSM 06.10 decode exactly as ffmpeg's decoders, and so do the Microsoft ADPCM
 * files ffmpeg encodes; ffmpeg's Microsoft ADPCM decoder truncates the prediction toward zero where Microsoft's codec,
 * and Smalt's, shift it down, which the predictors Smalt's encoder picks reach, so Smalt's files agree within an SNR;
 * ffmpeg's
 * WAVE IMA ADPCM decoder expands codes with a multiplication where the IMA recommendation, and Microsoft's codec,
 * shift and add, so it agrees within an SNR; ffmpeg's G.722 receive filter keeps one bit more than the
 * Recommendation's 15-bit output, so it agrees within one unit; ffmpeg's G.726 coder is not the Recommendation's
 * (its codes differ from the Appendix II sequences), so only its 16, 24 and 32 kbit/s decodes are compared, within
 * an SNR; its 40 kbit/s code assignment is incompatible.
 */
@DisplayName("ADPCM and telephony decoders against ffmpeg")
class TelephonyDifferentialTest {
    private static final Tolerance EXACT = Tolerance.exact();

    /** ffmpeg's multiplying IMA ADPCM expansion. */
    private static final Tolerance IMA_WAV = Tolerance.snr(40);

    /** ffmpeg's 16-bit G.722 receive filter output. */
    private static final Tolerance G722 = Tolerance.offByOne();

    /** ffmpeg's G.726 coder, which is not the Recommendation's. */
    private static final Tolerance G726 = Tolerance.snr(18);

    /** ffmpeg's Microsoft ADPCM prediction, rounded toward zero where Microsoft's codec rounds down. */
    private static final Tolerance MS_ADPCM = Tolerance.snr(35);

    private static String source(int channels, int rate) {
        var exprs = new ArrayList<String>();
        for (var c = 0; c < channels; c++) {
            exprs.add("0.45*sin(2*PI*(" + (140 + 50 * c) + "+60*sin(2*PI*0.8*t))*t)*(0.6+0.4*sin(2*PI*3*t))"
                    + "+0.1*sin(2*PI*" + (900 + 300 * c) + "*t)+0.02*(random(" + c + ")-0.5)");
        }
        return "aevalsrc=exprs=" + String.join("|", exprs) + ":s=" + rate + ":d=1.3";
    }

    /**
     * Compares two decodes as 16-bit samples, over their common length; the lengths may differ by the padding of a
     * final partial block, which a decoder may or may not output.
     */
    private static void compare(String name, Tolerance tolerance, float[] reference, float[] ours) {
        var length = Math.min(ours.length, reference.length);
        assertTrue(Math.abs(ours.length - reference.length) <= 4096, name + ": " + ours.length + " samples against "
                + reference.length);
        assertTrue(length > 0, name + ": no samples");
        tolerance.assertPcm(name, shorts(reference, length), shorts(ours, length));
    }

    private static short[] shorts(float[] samples, int length) {
        var out = new short[length];
        for (var i = 0; i < length; i++) {
            out[i] = (short) Math.max(-32768, Math.min(32767, Math.round(samples[i] * 32768.0)));
        }
        return out;
    }

    static Stream<Arguments> ffmpegFiles() {
        var cases = new ArrayList<Arguments>();
        for (var channels = 1; channels <= 2; channels++) {
            cases.add(Arguments.of("IMA ADPCM WAV " + channels + "ch", "wav", channels, 8000, List.of("-c:a",
                    "adpcm_ima_wav"), Codec.ADPCM_IMA_WAV, IMA_WAV));
            cases.add(Arguments.of("IMA ADPCM WAV 22 kHz " + channels + "ch", "wav", channels, 22050,
                    List.of("-c:a", "adpcm_ima_wav", "-block_size", "512"), Codec.ADPCM_IMA_WAV, IMA_WAV));
            cases.add(Arguments.of("MS ADPCM WAV " + channels + "ch", "wav", channels, 8000, List.of("-c:a",
                    "adpcm_ms"), Codec.ADPCM_MS, EXACT));
            cases.add(Arguments.of("ima4 AIFF-C " + channels + "ch", "aiff", channels, 8000, List.of("-c:a",
                    "adpcm_ima_qt"), Codec.ADPCM_IMA_QT, EXACT));
            cases.add(Arguments.of("ima4 MOV " + channels + "ch", "mov", channels, 8000, List.of("-c:a",
                    "adpcm_ima_qt"), Codec.ADPCM_IMA_QT, EXACT));
            cases.add(Arguments.of("IMA ADPCM MOV " + channels + "ch", "mov", channels, 8000, List.of("-c:a",
                    "adpcm_ima_wav"), Codec.ADPCM_IMA_WAV, IMA_WAV));
            cases.add(Arguments.of("MS ADPCM MOV " + channels + "ch", "mov", channels, 8000, List.of("-c:a",
                    "adpcm_ms"), Codec.ADPCM_MS, EXACT));
            cases.add(Arguments.of("IMA ADPCM AVI " + channels + "ch", "avi", channels, 8000, List.of("-c:a",
                    "adpcm_ima_wav"), Codec.ADPCM_IMA_WAV, IMA_WAV));
            cases.add(Arguments.of("MS ADPCM AVI " + channels + "ch", "avi", channels, 8000, List.of("-c:a",
                    "adpcm_ms"), Codec.ADPCM_MS, EXACT));
            cases.add(Arguments.of("IMA ADPCM MKV " + channels + "ch", "mkv", channels, 8000, List.of("-c:a",
                    "adpcm_ima_wav"), Codec.ADPCM_IMA_WAV, IMA_WAV));
            cases.add(Arguments.of("MS ADPCM MKV " + channels + "ch", "mkv", channels, 8000, List.of("-c:a",
                    "adpcm_ms"), Codec.ADPCM_MS, EXACT));
        }
        cases.add(Arguments.of("G.722 WAV", "wav", 1, 16000, List.of("-c:a", "g722"), Codec.ADPCM_G722, G722));
        for (var bitrate : new int[]{16000, 24000, 32000}) {
            cases.add(Arguments.of("G.726 WAV " + bitrate, "wav", 1, 8000, List.of("-c:a", "g726", "-b:a",
                    String.valueOf(bitrate)), Codec.ADPCM_G726, G726));
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ffmpegFiles")
    @DisplayName("files ffmpeg encodes decode to ffmpeg's samples")
    void ffmpegEncoded(String name, String extension, int channels, int rate, List<String> codec, Codec expected,
                       Tolerance tolerance) {
        var file = Fixtures.ffmpeg(extension, List.of("-f", "lavfi", "-i", source(channels, rate)), codec);
        var stream = TelephonyStreams.demux(file);
        assertEquals(expected, stream.track().codec(), name);
        var ours = TelephonyStreams.decodeFloats(AudioDecoders.create(stream.track()), stream.packets());
        var reference = Oracle.decodeAudio(file);
        compare(name, tolerance, reference, ours);
    }

    record Encoded(String name, ContainerFormat format, Supplier<AudioEncoder> encoder, Tolerance tolerance) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Encoded> smaltFiles() {
        return Stream.of(
                new Encoded("IMA ADPCM WAV", ContainerFormat.WAV, () -> new AdpcmEncoder(AdpcmEncoderOptions.of(
                        Codec.ADPCM_IMA_WAV, 8000, ChannelLayout.STEREO)), IMA_WAV),
                new Encoded("MS ADPCM WAV", ContainerFormat.WAV, () -> new AdpcmEncoder(AdpcmEncoderOptions.of(
                        Codec.ADPCM_MS, 11025, ChannelLayout.STEREO)), MS_ADPCM),
                new Encoded("ima4 AIFF-C", ContainerFormat.AIFF, () -> new AdpcmEncoder(AdpcmEncoderOptions.of(
                        Codec.ADPCM_IMA_QT, 8000, ChannelLayout.STEREO)), EXACT),
                new Encoded("ima4 MOV", ContainerFormat.MP4, () -> new AdpcmEncoder(AdpcmEncoderOptions.of(
                        Codec.ADPCM_IMA_QT, 8000, ChannelLayout.MONO)), EXACT),
                new Encoded("G.722 WAV", ContainerFormat.WAV, G722Encoder::new, G722),
                new Encoded("G.726 16 kbit/s WAV", ContainerFormat.WAV, () -> new G726Encoder(
                        G726EncoderOptions.ofCodeSize(2)), G726),
                new Encoded("G.726 32 kbit/s WAV", ContainerFormat.WAV, () -> new G726Encoder(
                        G726EncoderOptions.ofCodeSize(4)), G726),
                new Encoded("GSM 06.10 WAV", ContainerFormat.WAV, () -> new GsmEncoder(Codec.GSM_MS), EXACT),
                new Encoded("GSM 06.10 AIFF-C", ContainerFormat.AIFF, () -> new GsmEncoder(Codec.GSM), EXACT),
                new Encoded("GSM 06.10 MOV", ContainerFormat.MP4, () -> new GsmEncoder(Codec.GSM), EXACT));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("smaltFiles")
    @DisplayName("files Smalt encodes decode to ffmpeg's samples")
    void smaltEncoded(Encoded encoded) {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var encoder = encoded.encoder().get();
        var input = TelephonyStreams.signal(encoder.layout().channels(), encoder.sampleRate(),
                encoder.sampleRate() * 6 / 5, 0.6);
        var packets = TelephonyStreams.encode(encoder, input);
        var track = TelephonyStreams.track(encoder);
        var bytes = TelephonyStreams.mux(encoded.format(), track, packets);
        var extension = switch (encoded.format()) {
            case WAV -> "wav";
            case AIFF -> "aiff";
            default -> "mov";
        };
        try {
            var file = Files.createTempFile(Scratch.directory("telephony"), "smalt-", "." + extension);
            Files.write(file, bytes);
            var back = TelephonyStreams.demux(bytes);
            var ours = TelephonyStreams.decodeFloats(AudioDecoders.create(back.track()), back.packets());
            var reference = Oracle.decodeAudio(file);
            compare(encoded.name(), encoded.tolerance(), reference, ours);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
