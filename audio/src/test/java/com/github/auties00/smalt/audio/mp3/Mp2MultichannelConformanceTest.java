package com.github.auties00.smalt.audio.mp3;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.container.mpeg.MpegAudioDemuxer;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.testkit.audio.AudioPipeline;
import com.github.auties00.smalt.testkit.image.RasterTesting;
import com.github.auties00.smalt.track.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes the ISO/IEC 13818-4 MPEG-2 audio conformance sets (the Layer II multichannel extension in every channel
 * configuration with and without an extension bitstream, dynamic crosstalk, prediction, phantom center, the
 * low-frequency enhancement channel and the dematrixing procedures, and the lower sampling frequency, mono and dual
 * channel sets) and compares every output channel with its reference.
 *
 * <p>The references are 16-bit, except those of sets 34 and 35, which are 24-bit and held to ISO/IEC 13818-4 full
 * accuracy as they stand. The 16-bit ones were written by the originators' decoders, which round to nearest or
 * toward zero, so each channel is rounded both ways and must stay within one unit of the reference everywhere and
 * meet full accuracy in the rounding that matches; sets 02 and 09, whose decoder rounds differently still, meet
 * limited accuracy. Set 22 (a Layer I multichannel stream) and set 23 (Layer III) are not covered; the second stereo
 * programme of sets 06 and 07 and the multilingual channels of sets 10 and 12 are not output.
 */
class Mp2MultichannelConformanceTest {
    /** The rule of every 16-bit reference: within one unit of the reference. */
    private static final Tolerance ONE_UNIT = Tolerance.offByOne();

    /** The ISO/IEC 13818-4 full accuracy class. */
    private static final Tolerance FULL = Tolerance.iso11172(Tolerance.Iso11172Accuracy.FULL);

    /** The ISO/IEC 13818-4 limited accuracy class. */
    private static final Tolerance LIMITED = Tolerance.iso11172(Tolerance.Iso11172Accuracy.LIMITED);

    /** The sets, by number, with the SHA-256 of their archives. */
    private static final Map<String, String> SETS = new LinkedHashMap<>();

    static {
        String[] rows = {
                "01", "13ef1cc7df90e562fb9c1e1f3c7932b80e795d9de68f883fd85b840c17fd64e6",
                "02", "7df96764f5e8dc015dd30084c5c22b0b1001d7dd581ea6119a89925ab7ad2f0f",
                "03", "2c7315fe17cfa984addf35256cd89c84961e3b61de3a0530c16bdcd14c460777",
                "04", "9d9b80e864687f53849e95646deda833912e532af022334b2c49fab04576f6dd",
                "05", "ab0323dc3e1eaeb9ea788e96967d0f8472333b30ea574eb93adaa96ef0cafed1",
                "06", "e707a2601af33d59a803a384de2859bdacbcd2546f1ecd8e977c50120cd3d388",
                "07", "6a879cb462cb5fd080534e8a4f6c028f441176b8b425ca0cfee149b18b6de31a",
                "08", "8dd875bcb1866aab1ab2301abc2619c89db472602d0611d531494bc30a54a76c",
                "09", "dc73966b8fea11ca09659f6f5e4292c8f3700479ab617a4578cd8c78e85380c8",
                "10", "04597282f7d2af252a03bcea05b43ae4e86216e9bebd141dd66e5a4ff8153a4b",
                "11", "5c96fcfc483e8ef58063b4db7ae8c8eb3f1602842c9559bae3567f0f38bdbd19",
                "12", "1dd8ca5a90ad830f2c358dd9ce03336852487358ce4443afcacc6d00b74f4c11",
                "13", "4516550488e20b50f0097b9fc35755e1b7f1c2cac2bb5060853448703df24a71",
                "14", "b3d324057bfcdf0fbf95ba4afe2541728db00c29177725a653b08f2a2b9d6fbe",
                "15", "72624a9ea7f7a68d7c2afb778b6d3f40b74db39a55304adc8637237bb998fcf5",
                "16", "a84714986041639f09746e7bbfb5cf3270d8600130ddebb781fd82473fc1b324",
                "17", "0b3ba41db8df40847866968dc0eae72ed35990c685e18e9b91ae3a532425e0a6",
                "18", "7e9d4516706f5a435481210138568d6dc2e5eb68ecee9b30869d609822df5a68",
                "19", "acd3f3320e16a918374ff6d9a4adbc8796c64b5d4f1f34693bd744f3e4a26b56",
                "20", "19453913ee6484775797f12441a9f58cf05a70841db2671ddcdf9d388019a44a",
                "21", "64b29c3463d5e945c1421b3099a48b769920c177c1d071994b0d21b9bb29a4f8",
                "24", "4694a9785bee5ddb94e34b185a6a535c6bb18d0a0de95603fd825593302c8b74",
                "25", "6d9f855ab57644fabd18f323c1c4f9accbf96459420cc85a47edbc1cb8c84568",
                "26", "b9890fbc53100a425da1f9c0772b850999d3fdb3f095cb45806b66edb00aeea6",
                "27", "3a3b58a5be32dd6f0e1c6d8f8e8c645b2e3893824b109b0a5001205d2aa93b97",
                "28", "bd72446fdef6fe86e060d6349896b6200dfe4e843521f40aa081bc3efed51ea2",
                "29", "f92dfbc1c6937b78ef750426890a28014fb03a7abbfc11298862723df83e57ac",
                "30", "046166e3303b9662776fa4fbc3bb3564aa216595884737997207b291903b7990",
                "31", "21754aa7af53fccc90206df98262a73e79d88e2cc8d59f3b89f9e378ed8082eb",
                "32", "e5b37653baa126efae6845190f2fd7454f977ad5907aee33242cf61138b24d0e",
                "33", "3be5a8217c58005381c1badc9f27251558a8cac3a1b508cdd24e377c85f35a7a",
                "34", "070df2efff3cd55ea030b8aa2cc1b37638a91e527ae01a31e01657e572f2ff94",
                "35", "821b65353f31171da70c421d0d03bce152023fd54abc3a9b9e1838a9419ba6ab"};
        for (var i = 0; i < rows.length; i += 2) {
            SETS.put(rows[i], rows[i + 1]);
        }
    }

    /** The speaker of each reference channel file suffix. */
    private static final Map<String, Speaker> CHANNELS = new LinkedHashMap<>();

    static {
        CHANNELS.put("_l", Speaker.FRONT_LEFT);
        CHANNELS.put("_r", Speaker.FRONT_RIGHT);
        CHANNELS.put("_c", Speaker.FRONT_CENTER);
        CHANNELS.put("_lfe", Speaker.LOW_FREQUENCY);
        CHANNELS.put("_ls", Speaker.BACK_LEFT);
        CHANNELS.put("_rs", Speaker.BACK_RIGHT);
        CHANNELS.put("_s", Speaker.BACK_CENTER);
        CHANNELS.put("_I", Speaker.FRONT_LEFT);
        CHANNELS.put("_II", Speaker.FRONT_RIGHT);
        CHANNELS.put("", Speaker.FRONT_CENTER);
    }

    static Stream<Arguments> sets() {
        return SETS.keySet().stream().map(Arguments::of);
    }

    /** Returns the files of a set, by name without directory. */
    static Map<String, byte[]> files(String set) {
        var archive = Conformance.fetch("iso13818-4-audio-test" + set + ".tar.gz",
                "https://web.archive.org/web/20210427id_/https://standards.iso.org/ittf/PubliclyAvailableStandards/"
                        + "ISO_IEC_13818-4_2004_Conformance_Testing/Audio/test" + set + ".tar.gz", SETS.get(set));
        var files = new HashMap<String, byte[]>();
        for (var entry : RasterTesting.untarGz(archive).entrySet()) {
            var name = entry.getKey();
            files.put(name.substring(name.lastIndexOf('/') + 1), entry.getValue());
        }
        return files;
    }

    /** Returns the frames of an extension bitstream. */
    static List<byte[]> extensionFrames(byte[] stream) {
        var frames = new ArrayList<byte[]>();
        var i = 0;
        while (i + 5 <= stream.length) {
            if ((stream[i] & 0xFF) != 0x7F || (stream[i + 1] & 0xF0) != 0xF0) {
                i++;
                continue;
            }
            var length = ((stream[i + 3] & 0x0F) << 7) | ((stream[i + 4] & 0xFE) >> 1);
            frames.add(Arrays.copyOfRange(stream, i, Math.min(stream.length, i + Math.max(5, length))));
            i += Math.max(5, length);
        }
        return frames;
    }

    /** Joins each base frame with the extension frame of the same index, as the decoder takes them. */
    static List<Packet> packets(byte[] base, byte[] extension) {
        List<Packet> packets;
        try (var demuxer = new MpegAudioDemuxer(MediaSource.of(base))) {
            packets = AudioPipeline.packets(demuxer, AudioPipeline.firstAudio(demuxer).id());
        }
        if (extension == null) {
            return packets;
        }
        var frames = extensionFrames(extension);
        assertEquals(packets.size(), frames.size(), "one extension frame per base frame");
        var joined = new ArrayList<Packet>(packets.size());
        for (var i = 0; i < packets.size(); i++) {
            var packet = packets.get(i);
            var data = Arrays.copyOf(packet.data(), packet.size() + frames.get(i).length);
            System.arraycopy(frames.get(i), 0, data, packet.size(), frames.get(i).length);
            joined.add(packet.withData(data));
        }
        return joined;
    }

    /** Decodes a set into one plane per output speaker, and returns the layouts seen. */
    static Map<Speaker, float[]> decode(Map<String, byte[]> files, String set, List<ChannelLayout> layouts,
                                        Mp3DecoderOptions options) {
        var name = "test" + set;
        var packets = packets(files.get(name + ".mpg"), files.get(name + ".ext"));
        var output = new LinkedHashMap<Speaker, AudioPipeline.FloatList>();
        try (var demuxer = new MpegAudioDemuxer(MediaSource.of(files.get(name + ".mpg")))) {
            var decoder = new Mp3Decoder(AudioPipeline.firstAudio(demuxer), options);
            for (var packet : packets) {
                decoder.decode(packet, buffer -> {
                    if (!layouts.contains(buffer.layout())) {
                        layouts.add(buffer.layout());
                    }
                    for (var c = 0; c < buffer.channels(); c++) {
                        var list = output.computeIfAbsent(buffer.layout().speakers().get(c),
                                ignored -> new AudioPipeline.FloatList());
                        var plane = buffer.plane(c);
                        for (var i = 0; i < buffer.frames(); i++) {
                            list.add(plane[i]);
                        }
                    }
                });
            }
        }
        var result = new LinkedHashMap<Speaker, float[]>();
        output.forEach((speaker, list) -> result.put(speaker, list.toArray()));
        return result;
    }

    /** Reads big-endian 16-bit or 24-bit samples. */
    static int[] samples(byte[] data, int bytesPerSample) {
        var out = new int[data.length / bytesPerSample];
        for (var i = 0; i < out.length; i++) {
            var p = i * bytesPerSample;
            out[i] = bytesPerSample == 3 ? (data[p] << 16) | ((data[p + 1] & 0xFF) << 8) | (data[p + 2] & 0xFF)
                    : (short) (((data[p] & 0xFF) << 8) | (data[p + 1] & 0xFF));
        }
        return out;
    }

    /** Rounds float samples to 16 bits, to nearest or toward zero, saturating. */
    static short[] toPcm16(float[] samples, int count, int stride, int phase, boolean towardZero) {
        var out = new short[count];
        for (var i = 0; i < count; i++) {
            var scaled = samples[i * stride + phase] * 32768.0;
            var value = towardZero ? (double) (long) scaled : Math.rint(scaled);
            out[i] = (short) Math.max(-32768, Math.min(32767, value));
        }
        return out;
    }

    @ParameterizedTest(name = "test{0}")
    @MethodSource("sets")
    @DisplayName("every output channel of the ISO/IEC 13818-4 sets matches its reference")
    void conformance(String set) {
        var files = files(set);
        var layouts = new ArrayList<ChannelLayout>();
        var output = decode(files, set, layouts, Mp3DecoderOptions.defaults());
        assertEquals(1, layouts.size(), "the layout changes within test" + set + ": " + layouts);
        var compared = 0;
        for (var entry : CHANNELS.entrySet()) {
            var reference = files.get("test" + set + entry.getKey() + ".pcm");
            if (reference == null) {
                continue;
            }
            var speaker = entry.getValue();
            var ours = output.get(speaker);
            assertTrue(ours != null, "test" + set + entry.getKey() + " has no " + speaker + " output in " + layouts);
            var lfe = speaker == Speaker.LOW_FREQUENCY;
            var wide = !lfe && reference.length == ours.length * 3;
            var expected = samples(reference, wide ? 3 : 2);
            var what = "test" + set + entry.getKey();
            // the decoder reaches each coded LFE sample at the end of its 96-sample period
            var stride = lfe ? 96 : 1;
            var phase = lfe ? 95 : 0;
            assertEquals(expected.length, ours.length / stride, what + " length");
            if (wide) {
                var reference24 = new float[expected.length];
                for (var i = 0; i < expected.length; i++) {
                    reference24[i] = expected[i] / 8388608.0f;
                }
                System.out.println("[mp2-mc] " + what + ": " + Tolerance.Difference.ofPcm(reference24, ours));
                FULL.assertPcm(what, reference24, ours);
            } else {
                var reference16 = new short[expected.length];
                for (var i = 0; i < expected.length; i++) {
                    reference16[i] = (short) expected[i];
                }
                var nearest = toPcm16(ours, expected.length, stride, phase, false);
                var towardZero = toPcm16(ours, expected.length, stride, phase, true);
                var nearestDifference = Tolerance.Difference.ofPcm(reference16, nearest);
                var zeroDifference = Tolerance.Difference.ofPcm(reference16, towardZero);
                var matched = nearestDifference.rms() <= zeroDifference.rms() ? nearest : towardZero;
                System.out.println("[mp2-mc] " + what + ": nearest " + nearestDifference + "; toward zero "
                        + zeroDifference);
                ONE_UNIT.assertPcm(what, reference16, matched);
                (set.equals("02") || set.equals("09") ? LIMITED : FULL).assertPcm(what, reference16, matched);
            }
            compared++;
        }
        assertTrue(compared > 0, "test" + set + " has no reference channel");
    }

    @Test
    @DisplayName("with the multichannel option off, a multichannel stream decodes to its Lo/Ro base channels")
    void baseOnly() {
        var files = files("02");
        var layouts = new ArrayList<ChannelLayout>();
        var base = decode(files, "02", layouts, Mp3DecoderOptions.builder().multichannel(false).build());
        assertEquals(List.of(ChannelLayout.STEREO), layouts);
        var full = decode(files, "02", new ArrayList<>(), Mp3DecoderOptions.defaults());
        assertEquals(5, full.size());
        // set 02 dematrixes with procedure 0, so Lo mixes the center and left surround into the left channel
        var lo = base.get(Speaker.FRONT_LEFT);
        var left = full.get(Speaker.FRONT_LEFT);
        var center = full.get(Speaker.FRONT_CENTER);
        var surround = full.get(Speaker.BACK_LEFT);
        var a = 1 / (1 + Math.sqrt(2));
        var b = a / Math.sqrt(2);
        var error = 0.0;
        var signal = 0.0;
        for (var i = 2000; i < lo.length; i++) {
            var mix = a * left[i] + b * center[i] + b * surround[i];
            error += (lo[i] - mix) * (lo[i] - mix);
            signal += lo[i] * lo[i];
        }
        assertTrue(signal > 0 && error < signal * 1e-4, "Lo is not the procedure 0 downmix: " + error / signal);
    }
}
