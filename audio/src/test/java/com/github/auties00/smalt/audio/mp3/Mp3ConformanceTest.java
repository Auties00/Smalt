package com.github.auties00.smalt.audio.mp3;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.container.mpeg.MpegAudioDemuxer;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Decodes the ISO/IEC 11172-4 (MPEG-1 Layers I, II, III) and ISO/IEC 13818-4 (MPEG-2 Layer III)
 * compliance bitstreams, plus the "illegal stream" and robustness vectors that minimp3 ships,
 * and compares them with their reference PCM, rounded to 16 bits like the reference, by the full
 * accuracy limits of ISO/IEC 11172-4 (an RMS difference below 2^-15/sqrt(12) of full scale, no
 * sample off by more than 2^-14) and by minimp3's criterion, a PSNR of at least 96 dB. Layer III
 * reference files are allowed to lack one or two trailing frames (the reference
 * decoder drops a truncated final frame) and the leading frames whose bit reservoir precedes the
 * stream (which this decoder conceals as silence); Layer I and II references are zero-padded.
 */
class Mp3ConformanceTest {
    /** The ISO/IEC 11172-4 full accuracy class a compliant decoder meets on these streams. */
    private static final Tolerance COMPLIANCE = Tolerance.iso11172(Tolerance.Iso11172Accuracy.FULL);

    /** minimp3's acceptance criterion over the same streams. */
    private static final Tolerance MINIMP3 = Tolerance.psnr(96);

    private static void assertCompliant(String name, boolean layer3, boolean strictLength) {
        // the free-format vector has its bitrate index rewritten after coding, so every CRC word fails; minimp3's
        // references of the ILL vectors are the stereo base of their multichannel extension
        var options = Mp3DecoderOptions.builder().crcCheck(!name.equals("l2-nonstandard-free_format"))
                .multichannel(!name.startsWith("ILL")).build();
        var decoded = Mp3Vectors.decode(Mp3Vectors.read(Mp3Vectors.vector(name + ".bit")), options);
        var ours = Mp3Vectors.toS16(decoded.interleaved());
        var reference = Mp3Vectors.readPcm(Mp3Vectors.read(Mp3Vectors.vector(name + ".pcm")));
        var frame = decoded.channels() * 1152;
        var bestPsnr = -1.0;
        var bestSkip = 0;
        for (var skip = 0; skip <= 2 && skip * frame <= ours.length; skip++) {
            var psnr = Mp3Vectors.psnr(reference, Arrays.copyOfRange(ours, skip * frame, ours.length));
            if (psnr > bestPsnr) {
                bestPsnr = psnr;
                bestSkip = skip;
            }
        }
        for (var i = 0; i < bestSkip * frame; i++) {
            assertEquals(0, ours[i], "a skipped leading frame is not concealment silence");
        }
        var start = bestSkip * frame;
        var common = Math.min(reference.length, ours.length - start);
        var expected = Arrays.copyOf(reference, common);
        var actual = Arrays.copyOfRange(ours, start, start + common);
        System.out.println("[mp3-compliance] " + name + ": " + Tolerance.Difference.ofPcm(expected, actual));
        MINIMP3.assertPcm(name, expected, actual);
        COMPLIANCE.assertPcm(name, expected, actual);
        var extra = ours.length - bestSkip * frame - reference.length;
        if (strictLength) {
            assertEquals(0, extra, name + ": sample count differs from the reference");
        } else if (layer3) {
            assertTrue(extra == 0 || extra == 1152 || extra == 2304, name + ": " + extra + " extra samples");
        } else {
            assertTrue(extra <= 0, name + ": longer than the zero-padded reference");
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"l1-fl1", "l1-fl2", "l1-fl3", "l1-fl4", "l1-fl5", "l1-fl6", "l1-fl7", "l1-fl8"})
    @DisplayName("Layer I compliance streams meet ISO/IEC 11172-4 full accuracy and 96 dB PSNR")
    void layer1(String name) {
        assertCompliant(name, false, false);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"l2-fl10", "l2-fl11", "l2-fl12", "l2-fl13", "l2-fl14", "l2-fl15", "l2-fl16", "l2-test32"})
    @DisplayName("Layer II compliance streams meet ISO/IEC 11172-4 full accuracy and 96 dB PSNR")
    void layer2(String name) {
        assertCompliant(name, false, false);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"l3-compl", "l3-he_32khz", "l3-he_44khz", "l3-he_48khz", "l3-he_free", "l3-he_mode",
            "l3-hecommon", "l3-si", "l3-si_block", "l3-si_huff", "l3-sin1k0db", "l3-test45", "l3-test46"})
    @DisplayName("MPEG-1 Layer III compliance streams meet ISO/IEC 11172-4 full accuracy and 96 dB PSNR")
    void layer3(String name) {
        assertCompliant(name, true, false);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"M2L3_bitrate_16_all", "M2L3_bitrate_22_all", "M2L3_bitrate_24_all", "M2L3_compl24", "M2L3_noise"})
    @DisplayName("MPEG-2 Layer III compliance streams meet ISO/IEC 11172-4 full accuracy and 96 dB PSNR")
    void mpeg2(String name) {
        assertCompliant(name, true, false);
    }

    static Stream<String> illegalStreamNames() {
        return Stream.of("ILL2_center2", "ILL2_dual", "ILL2_dynx22", "ILL2_dynx31", "ILL2_dynx32", "ILL2_ext_switching",
                "ILL2_layer1", "ILL2_layer3", "ILL2_mono", "ILL2_multilingual", "ILL2_overalloc1", "ILL2_prediction",
                "ILL2_samples", "ILL2_scf63", "ILL2_tca21", "ILL2_tca30", "ILL2_tca30_PC", "ILL2_tca31_PC",
                "ILL2_tca31_mtx0", "ILL2_tca31_mtx2", "ILL2_tca32_PC", "ILL2_wrongcrc", "ILL4_ext_id1", "ILL4_sync",
                "ILL4_wrong_length1", "ILL4_wrong_length2");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("illegalStreamNames")
    @DisplayName("MPEG-2 extension and illegal-stream vectors decode their stereo base to the reference exactly in length")
    void illegalStreams(String name) {
        assertCompliant(name, false, true);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("illegalStreamNames")
    @DisplayName("with the multichannel extension on, every frame decodes either exactly as its stereo base or to "
            + "more channels of the same length")
    void illegalStreamsMultichannel(String name) {
        var data = Mp3Vectors.read(Mp3Vectors.vector(name + ".bit"));
        var base = buffers(data, Mp3DecoderOptions.builder().multichannel(false).build());
        var full = buffers(data, Mp3DecoderOptions.defaults());
        assertEquals(base.size(), full.size(), name + ": buffer count");
        for (var i = 0; i < base.size(); i++) {
            var stereo = base.get(i);
            var multichannel = full.get(i);
            assertEquals(stereo[0].length, multichannel[0].length, name + ": frame " + i + " length");
            if (multichannel.length == stereo.length) {
                for (var c = 0; c < stereo.length; c++) {
                    assertArrayEquals(stereo[c], multichannel[c], name + ": frame " + i + " channel " + c);
                }
            } else {
                assertTrue(multichannel.length > stereo.length, name + ": frame " + i + " has "
                        + multichannel.length + " channels");
            }
        }
    }

    /** Decodes a stream to a copy of the planes of every buffer, untrimmed. */
    private static List<float[][]> buffers(byte[] data, Mp3DecoderOptions options) {
        var out = new ArrayList<float[][]>();
        try (var demuxer = new MpegAudioDemuxer(MediaSource.of(data));
             var decoder = new Mp3Decoder((AudioTrack) demuxer.tracks().getFirst(), options)) {
            Consumer<AudioBuffer> sink = buffer -> {
                var planes = new float[buffer.channels()][];
                for (var c = 0; c < planes.length; c++) {
                    planes[c] = Arrays.copyOf(buffer.plane(c), buffer.frames());
                }
                out.add(planes);
            };
            Packet packet;
            while ((packet = demuxer.read()) != null) {
                decoder.decode(packet, sink);
            }
            decoder.flush(sink);
        }
        return out;
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"l2-nonstandard-free_format", "l3-nonstandard-apetag", "l3-nonstandard-id3v1",
            "l3-nonstandard-id3v1-apetag", "l3-nonstandard-id3v2"})
    @DisplayName("tagged and free-format streams decode to their reference exactly in length")
    void taggedStreams(String name) {
        assertCompliant(name, false, true);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"ILL2_overalloc2", "l3-nonstandard-id3v2-only", "l3-nonstandard-sideinfo-size",
            "l3-nonstandard-small", "l3-nonstandard-vbrtag-corrupted", "l3-nonstandard-vbrtag-empty",
            "l3-nonstandard-vbrtag-noframes", "l3-nonstandard-vbrtag-only", "l3-nonstandard-vbrtag-oob-read"})
    @DisplayName("streams without audio frames yield no samples or a Malformed failure")
    void silentStreams(String name) {
        try {
            var decoded = Mp3Vectors.decode(Mp3Vectors.read(Mp3Vectors.vector(name + ".bit")));
            assertEquals(0, decoded.interleaved().length, name + " produced samples");
        } catch (MediaException.Malformed expected) {
            // the stream holds no frame at all
        }
    }

    @org.junit.jupiter.api.Test
    @DisplayName("the reference decode of the out-of-bounds VBR tag stream is silence, as ours")
    void outOfBoundsVbrTagReference() {
        // minimp3 outputs one frame of zeros for the lone tag frame, which holds no audio; ours outputs nothing
        var reference = Mp3Vectors.readPcm(Mp3Vectors.read(Mp3Vectors.vector("l3-nonstandard-vbrtag-oob-read.pcm")));
        assertTrue(reference.length > 0);
        for (var sample : reference) {
            assertEquals(0, sample, "the reference holds audio");
        }
    }

    /** Returns the packets of a stream, as the demuxer frames them. */
    private static List<byte[]> packets(byte[] data) {
        var out = new ArrayList<byte[]>();
        try (var demuxer = new MpegAudioDemuxer(MediaSource.of(data))) {
            Packet packet;
            while ((packet = demuxer.read()) != null) {
                out.add(packet.data());
            }
        }
        return out;
    }

    /**
     * Checks a damaged copy of a compliance stream against minimp3's decode of it: every frame before the first
     * frame whose bytes differ from the intact stream decodes as minimp3 decodes it, by the full accuracy and
     * minimp3 criteria, after the leading frames whose bit reservoir precedes the stream; then, for Layer II, whose
     * frames are independent, every later reference frame is decoded too, at most one frame later (where this
     * decoder conceals a damaged frame that minimp3 drops). A Layer III frame after the damage may draw on damaged
     * reservoir bits, which each decoder recovers from in its own way.
     */
    private static void assertDamaged(String name, String intact, boolean layer3) {
        var damaged = packets(Mp3Vectors.read(Mp3Vectors.vector(name + ".bit")));
        var clean = packets(Mp3Vectors.read(Mp3Vectors.vector(intact + ".bit")));
        var onset = 0;
        while (onset < Math.min(damaged.size(), clean.size()) && Arrays.equals(damaged.get(onset), clean.get(onset))) {
            onset++;
        }
        assertTrue(onset > 0 && onset < damaged.size(), name + ": damage at frame " + onset);
        var decoded = Mp3Vectors.decode(Mp3Vectors.read(Mp3Vectors.vector(name + ".bit")));
        var ours = Mp3Vectors.toS16(decoded.interleaved());
        var reference = Mp3Vectors.readPcm(Mp3Vectors.read(Mp3Vectors.vector(name + ".pcm")));
        var frame = decoded.channels() * 1152;
        var best = -1.0;
        var skip = 0;
        for (var s = 0; s <= 2 && s < onset; s++) {
            var length = (onset - s) * frame;
            var psnr = Mp3Vectors.psnr(Arrays.copyOf(reference, length),
                    Arrays.copyOfRange(ours, s * frame, s * frame + length));
            if (psnr > best) {
                best = psnr;
                skip = s;
            }
        }
        for (var i = 0; i < skip * frame; i++) {
            assertEquals(0, ours[i], "a skipped leading frame is not concealment silence");
        }
        var prefix = (onset - skip) * frame;
        var expected = Arrays.copyOf(reference, prefix);
        var actual = Arrays.copyOfRange(ours, skip * frame, skip * frame + prefix);
        System.out.println("[mp3-compliance] " + name + " before the damage at frame " + onset + ": "
                + Tolerance.Difference.ofPcm(expected, actual));
        MINIMP3.assertPcm(name + " before the damage", expected, actual);
        COMPLIANCE.assertPcm(name + " before the damage", expected, actual);
        if (!layer3) {
            for (var k = onset + 1 - skip; k < reference.length / frame; k++) {
                var want = Arrays.copyOfRange(reference, k * frame, (k + 1) * frame);
                var matched = false;
                for (var shift = 0; shift <= 1 && !matched; shift++) {
                    var at = (k + skip + shift) * frame;
                    if (at + frame <= ours.length) {
                        var got = Arrays.copyOfRange(ours, at, at + frame);
                        matched = COMPLIANCE.violations(Tolerance.Difference.ofPcm(want, got)).isEmpty();
                    }
                }
                assertTrue(matched, name + ": reference frame " + k + " after the damage is not decoded");
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.CsvSource({"l2-nonstandard-test32-size, l2-test32, false",
            "l3-nonstandard-compl-sideinfo-bigvalues, l3-compl, true",
            "l3-nonstandard-compl-sideinfo-blocktype, l3-compl, true",
            "l3-nonstandard-compl-sideinfo-size, l3-compl, true"})
    @DisplayName("damaged copies of compliance streams decode as minimp3 decodes them up to the damage, Layer II past it")
    void damagedStreams(String name, String intact, boolean layer3) {
        assertDamaged(name, intact, layer3);
    }

    @org.junit.jupiter.api.Test
    @DisplayName("a free-format Layer I stream with rewritten headers decodes as minimp3 does, CRC unchecked")
    void freeFormatLayer1() {
        // l2-nonstandard-fl1_fl2_ff: Layer I frames whose bitrate index was rewritten to 0 (free format) after
        // coding, with alternating padding, so their CRC words fail as in l2-nonstandard-free_format; minimp3, which
        // checks no CRC, decodes the four whole frames and drops the short fifth
        var options = Mp3DecoderOptions.builder().crcCheck(false).build();
        var ours = Mp3Vectors.toS16(Mp3Vectors.decode(Mp3Vectors.read(Mp3Vectors.vector(
                "l2-nonstandard-fl1_fl2_ff.bit")), options).interleaved());
        var reference = Mp3Vectors.readPcm(Mp3Vectors.read(Mp3Vectors.vector("l2-nonstandard-fl1_fl2_ff.pcm")));
        var frame = 2 * 384;
        assertEquals(4 * frame, reference.length);
        assertEquals(5 * frame, ours.length, "every frame decoded");
        var actual = Arrays.copyOf(ours, reference.length);
        MINIMP3.assertPcm("l2-nonstandard-fl1_fl2_ff", reference, actual);
        COMPLIANCE.assertPcm("l2-nonstandard-fl1_fl2_ff", reference, actual);
    }

    @org.junit.jupiter.api.Test
    @DisplayName("a stream that switches from 44.1 to 48 kHz decodes its 44.1 kHz part as minimp3 does, which stops there")
    void rateSwitch() {
        var decoded = Mp3Vectors.decode(Mp3Vectors.read(Mp3Vectors.vector("l3-nonstandard-he_44_48khz.bit")));
        var ours = Mp3Vectors.toS16(decoded.interleaved());
        var reference = Mp3Vectors.readPcm(Mp3Vectors.read(Mp3Vectors.vector("l3-nonstandard-he_44_48khz.pcm")));
        assertTrue(ours.length > reference.length, "the 48 kHz part is decoded too");
        var actual = Arrays.copyOf(ours, reference.length);
        MINIMP3.assertPcm("44.1 kHz part", reference, actual);
        COMPLIANCE.assertPcm("44.1 kHz part", reference, actual);
    }

    @org.junit.jupiter.api.Test
    @DisplayName("a LAME-tagged stream trimmed by its encoder delay and padding equals minimp3's gapless decode")
    void lameTag() {
        // minimp3's mp3dec_load drops the LAME tag's delay (plus the decoder's 529) and padding, as the track's
        // skip samples and duration do here
        var ours = Mp3Vectors.toS16(Mp3Vectors.decode(Mp3Vectors.read(Mp3Vectors.vector(
                "l3-nonstandard-sin1k0db_lame_vbrtag.bit"))).trimmed());
        var reference = Mp3Vectors.readPcm(Mp3Vectors.read(Mp3Vectors.vector(
                "l3-nonstandard-sin1k0db_lame_vbrtag.pcm")));
        assertEquals(reference.length, ours.length, "gapless length");
        MINIMP3.assertPcm("l3-nonstandard-sin1k0db_lame_vbrtag", reference, ours);
        COMPLIANCE.assertPcm("l3-nonstandard-sin1k0db_lame_vbrtag", reference, ours);
    }

    @org.junit.jupiter.api.Test
    @DisplayName("a stream entered 633 bytes in, at its second frame, decodes as minimp3 does after that byte seek")
    void byteSeek() {
        // minimp3 -s 633 -b: byte 633 is the second frame's header; the first frame minimp3 outputs depends on the
        // bit reservoir it lacks, so the comparison starts at the next, after the frames this decoder conceals
        var data = Mp3Vectors.read(Mp3Vectors.vector("l3-sin1k0db.bit"));
        var decoded = Mp3Vectors.decode(Arrays.copyOfRange(data, 633, data.length));
        var ours = Mp3Vectors.toS16(decoded.interleaved());
        var reference = Mp3Vectors.readPcm(Mp3Vectors.read(Mp3Vectors.vector("l3-sin1k0db_ofs633.pcm")));
        var frame = decoded.channels() * 1152;
        var best = -1.0;
        var skip = 0;
        for (var s = 0; s <= 2; s++) {
            var psnr = Mp3Vectors.psnr(Arrays.copyOfRange(reference, frame, reference.length),
                    Arrays.copyOfRange(ours, (s + 1) * frame, ours.length));
            if (psnr > best) {
                best = psnr;
                skip = s;
            }
        }
        for (var i = 0; i < skip * frame; i++) {
            assertEquals(0, ours[i], "a skipped leading frame is not concealment silence");
        }
        var common = Math.min(reference.length - frame, ours.length - (skip + 1) * frame);
        var expected = Arrays.copyOfRange(reference, frame, frame + common);
        var actual = Arrays.copyOfRange(ours, (skip + 1) * frame, (skip + 1) * frame + common);
        var extra = ours.length - skip * frame - reference.length;
        assertTrue(extra == 0 || extra == frame || extra == 2 * frame, extra + " extra samples");
        System.out.println("[mp3-compliance] byte seek 633: " + Tolerance.Difference.ofPcm(expected, actual));
        MINIMP3.assertPcm("l3-sin1k0db from byte 633", expected, actual);
        COMPLIANCE.assertPcm("l3-sin1k0db from byte 633", expected, actual);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"opengoldberg-05-variatio4.mp3", "commons-mpeg25-8000-vbr-mono.mp3",
            "commons-mpeg25-11025-cbr-mono.mp3", "mpeg25-12000-stereo.mp3"})
    @DisplayName("real LAME VBR music and MPEG-2.5 streams match ffmpeg's float decoder within ISO/IEC 11172-4 full "
            + "accuracy")
    void realStreams(String name) {
        // a LAME VBR (Xing and LAME tag) piano recording, MPEG-2.5 at 8 kHz VBR and 11.025 kHz CBR mono, and a
        // generated MPEG-2.5 12 kHz joint stereo stream (tools/testdata/README.md, Generated vectors); no reference
        // decode ships with them, so ffmpeg's mp3float, an independent float decoder of the same normative
        // synthesis, is the oracle
        Mp3DecoderTest.assertMatchesFfmpeg(com.github.auties00.smalt.testkit.Conformance.vector(name));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"l2-nonstandard-fl1_fl2_ff", "l2-nonstandard-test32-size", "l3-nonstandard-big-iscf",
            "l3-nonstandard-compl-sideinfo-bigvalues", "l3-nonstandard-compl-sideinfo-blocktype",
            "l3-nonstandard-compl-sideinfo-size", "l3-nonstandard-he_44_48khz"})
    @DisplayName("corrupt and format-switching streams decode without leaking unchecked exceptions")
    void corruptStreams(String name) {
        try {
            var decoded = Mp3Vectors.decode(Mp3Vectors.read(Mp3Vectors.vector(name + ".bit")));
            for (var sample : decoded.interleaved()) {
                if (!Float.isFinite(sample)) {
                    fail(name + " produced a non-finite sample");
                }
            }
        } catch (MediaException expected) {
            // rejecting a corrupt stream is acceptable
        }
    }
}
