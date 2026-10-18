package com.github.auties00.smalt.audio.opus;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.opus.internal.OpusDefines;
import com.github.auties00.smalt.audio.opus.internal.OpusStreamEncoder;
import com.github.auties00.smalt.track.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks that the encoder is bit-exact with the libopus 1.5.2 reference: for each configuration
 * the SHA-256 of the packets and final ranges (in {@code opus_demo} bitstream form) must equal the
 * hash produced by the reference C library (float build without SIMD, {@code opus_encode_float}
 * with the same controls and the same silence padding) on the same input.
 *
 * <p>The inputs are cut from the decoded RFC 8251 test vectors with integer operations only, so
 * they are identical on every machine: speech from {@code testvector03}, music from
 * {@code testvector07} and {@code testvector11}, decimated by sample picking to the lower rates,
 * plus concatenated and alternating speech/music signals that force mode transitions.
 */
class OpusEncoderGoldenTest {
    /** Name, input, rate, channels, application, bitrate, mode, complexity, frame us, signal, max bandwidth, SHA-256. */
    private static final String[] GOLDENS = {
            "voicenote speech16k 16000 1 voip 16000 vbr 10 20000 auto auto ad0ab7f016d5e0ebdf0b215a85d111c0ac0072814cb13af9c555644be0cb913a",
            "music128 music48k 48000 2 audio 128000 vbr 10 20000 auto auto 560a5a21d60a6adf6ac0b189ca1177126e48ff272c691b6fe4c144234ac92da1",
            "music96 music48k 48000 2 audio 96000 vbr 10 20000 auto auto 2167cd8bfd7a9bdfe14f13ec1af04be534deee8b4c7224549aca488009f6afc5",
            "lowdelay32 music48k_mono 48000 1 lowdelay 32000 vbr 10 20000 auto auto ed8876ec1c3683b579532ff5e4f8fb4a685800b0864b3973b669404f457516a8",
            "hybrid32 speech48k 48000 1 voip 32000 vbr 10 20000 auto auto 87c50bdb4361cf7d7e39a564912d0cdb02ea0a10de4ca6602e841ed569c5696a",
            "hybrid_stereo48 music48k 48000 2 audio 48000 vbr 10 20000 auto auto dd2a7aef96340485a84569018796ffa3a9a9eb359e6b173aa60c897634ae1896",
            "cbr24 speech16k 16000 1 voip 24000 cbr 5 20000 auto auto aa234ba1bcb9b545487e7ac6e93bfa24cb9a0e2f960ec6793fab57d58bf8cb44",
            "cvbr64 music48k 48000 2 audio 64000 cvbr 8 10000 auto auto f0d8c9b04f8b0fbfab9f91492c47e6857180bd8369dcba5f29379dd1e0558b06",
            "silk60 speech16k 16000 1 voip 12000 vbr 10 60000 auto auto 2ecadc5a0875aa5154dafbd11cf800da9caebf8276c48b0b30c77d61f1ca863c",
            "celt40 music48k 48000 2 audio 96000 vbr 10 40000 auto auto 83c9f8fdd9dfc13c9bbe46fb42e43e49f28c83a1103cac44e081407802e86eaa",
            "celt2_5 music48k 48000 2 audio 128000 vbr 10 2500 auto auto a9e53360030af23fabab34ea6e0a22e8ecf7be1aa393294dce99abc1457f2d2f",
            "lowdelay5 speech48k 48000 1 lowdelay 64000 cbr 10 5000 auto auto 415a8927e6dd1f95723930e60ad90f69d50a85253d4ed3c7071c877f78ea8806",
            "c0 speech24k 24000 1 voip 20000 vbr 0 20000 auto auto f44e4b2132174cbc6c39d6fec46bccd881b6bfbc4d69947a74cb2288879f9807",
            "c3 speech24k 24000 1 voip 20000 vbr 3 20000 auto auto 231acca0b4178a46e0615c9e9dfedb3df4965699d55430413e77599eefb24b10",
            "c6 speech24k 24000 1 voip 20000 vbr 6 20000 auto auto d7359de4aa5af3529d96cae4c4d89acebf752209c40752206e057290f828f19f",
            "nb8k speech8k 8000 1 voip 8000 vbr 10 20000 auto auto 3543d58f2bbff06a3daaef1ba1bd7078037964242ff25baca1583ef22564fd45",
            "mb12k speech12k 12000 1 voip 12000 vbr 10 20000 auto auto a58bb19126b82b0fedc13e07f296c954148a0ddbb787b40460f1bfaf153a9d24",
            "silk120 speech16k 16000 1 voip 16000 vbr 10 120000 auto auto 9e5f4bf185fdb4dd3de67c353895554b5fa52977928fceb214eaa565fbc7b5f1",
            "silk100 speech16k 16000 1 voip 16000 vbr 10 100000 auto auto 636676b89390d8c20eda6c0d6283faf1604b9b7fe27b2e1fab80f21016845836",
            "stereo16k speech16k_stereo 16000 2 voip 24000 vbr 10 20000 auto auto 9f94ba866000a99b99a9630bd0441b73aa217761214bc73760f8623577b4ba8a",
            "music_wb music48k 48000 2 audio 64000 vbr 10 20000 music wb 06404829d9fc7631a95e2e18b67167f522fc52d71c71f01166563bfe0dbe913b",
            "voice_hint music48k_mono 48000 1 audio 24000 vbr 9 20000 voice auto e9f6ca3b1e2978f30a5680da0b4e0e22fb01b7668cf5546e44c228cbef8d9efa",
            "mixed_audio32 mixed48k 48000 1 audio 32000 vbr 10 20000 auto auto f3f2dc63e53c03e70e89479aad431962a0cd4cdb65544bfe5f5ec0b3ad6266fe",
            "mixed_voip24 mixed48k 48000 1 voip 24000 vbr 10 20000 auto auto e5390babde9bcbe3bc7aa2cb9f32b4da93d106aa4d1b8a28ff648763461b074c",
            "mixed_stereo40 mixed48k_stereo 48000 2 audio 40000 vbr 10 20000 auto auto cb336fda4c0e05eb027119f15993742a55ae5234ed5610d69d9833a3090baef8",
            "mixed_audio20_10ms mixed48k 48000 1 audio 20000 vbr 10 10000 auto auto b15ea79b1b5d27b5a2f793ebd1e8690f0eaf8d231966861178a9b2f5695dd5b5",
            "alt_audio32 alt48k 48000 1 audio 32000 vbr 10 20000 auto auto a52fd705add220ed866d8a4160f7a032a210fff10f57be5bc3d04736f7ce59e7",
            "alt_voip24_10ms alt48k 48000 1 voip 24000 vbr 10 10000 auto auto 8c78c5f8682f945d846864fc1efd44c4c99038365291c3811866381488fa13d8",
            "alt_voicenote alt16k 16000 1 voip 16000 vbr 10 20000 auto auto 6e5055130b0b69a1d641273614fd530cccbee848e5072ed3d961639907e5f8d9",
            "alt_stereo48 alt48k_stereo 48000 2 audio 48000 vbr 10 20000 auto auto 4594ab0dfde09acded446ae321ae7aff313cb13f49230601a1ad72b12fa02a89",
            "alt_cbr40 alt48k 48000 1 audio 40000 cbr 10 20000 auto auto 5dd9ba768531120ddf5127f295f980024089e9036a10ef6558777b9eb8f8be29",
            "alt_audio32_60ms alt48k 48000 1 audio 32000 vbr 10 60000 auto auto ae319226276f173bba28bec25173f59a2d13b6edaed3f36e1d7290d86503ec00",
    };

    private static final Map<String, short[]> INPUTS = new ConcurrentHashMap<>();

    static Stream<String> goldens() {
        return Stream.of(GOLDENS);
    }

    /** Parses a golden line into an encoder configuration. */
    static OpusEncoderOptions config(String[] f) {
        var application = switch (f[4]) {
            case "voip" -> OpusApplication.VOIP;
            case "audio" -> OpusApplication.AUDIO;
            default -> OpusApplication.RESTRICTED_LOWDELAY;
        };
        var mode = switch (f[6]) {
            case "vbr" -> OpusBitrateMode.VBR;
            case "cvbr" -> OpusBitrateMode.CONSTRAINED_VBR;
            default -> OpusBitrateMode.CBR;
        };
        var signal = switch (f[9]) {
            case "voice" -> OpusSignal.VOICE;
            case "music" -> OpusSignal.MUSIC;
            default -> OpusSignal.AUTO;
        };
        var bandwidth = switch (f[10]) {
            case "nb" -> OpusBandwidth.NARROWBAND;
            case "mb" -> OpusBandwidth.MEDIUMBAND;
            case "wb" -> OpusBandwidth.WIDEBAND;
            case "swb" -> OpusBandwidth.SUPERWIDEBAND;
            // "fb" and no restriction configure the encoder identically
            default -> OpusBandwidth.FULLBAND;
        };
        return OpusEncoderOptions.builder()
                .format(Integer.parseInt(f[2]), Integer.parseInt(f[3]))
                .application(application)
                .bitrate(Integer.parseInt(f[5]))
                .bitrateMode(mode)
                .complexity(Integer.parseInt(f[7]))
                .frameDurationMicros(Integer.parseInt(f[8]))
                .signal(signal)
                .maxBandwidth(bandwidth)
                .build();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("goldens")
    @DisplayName("produces the same packets and final ranges as the libopus reference")
    void bitExact(String line) throws Exception {
        var f = line.split(" ");
        var config = config(f);
        var input = input(f[1]);
        var encoder = new OpusStreamEncoder(config.sampleRate(), config.channels(), switch (config.application()) {
            case VOIP -> OpusDefines.OPUS_APPLICATION_VOIP;
            case AUDIO -> OpusDefines.OPUS_APPLICATION_AUDIO;
            case RESTRICTED_LOWDELAY -> OpusDefines.OPUS_APPLICATION_RESTRICTED_LOWDELAY;
        });
        configure(encoder, config);
        var channels = config.channels();
        var frame = config.frameSize();
        var total = input.length / channels;
        var target = (long) total + encoder.lookahead();
        var buffer = new float[frame * channels];
        var packet = new byte[1275 * 6 + 7];
        var out = new ByteArrayOutputStream();
        var publicPackets = encodePublic(config, input);
        var index = 0;
        long coded = 0;
        long position = 0;
        while (coded < target && total > 0) {
            for (var i = 0; i < frame * channels; i++) {
                var sample = position * channels + i;
                buffer[i] = sample < (long) total * channels ? (1.0f / 32768) * input[(int) sample] : 0;
            }
            position += frame;
            var length = encoder.encodeFloat(buffer, 0, frame, packet, 0, packet.length);
            assertTrue(length > 0, "encode failed at packet " + index + ": " + length);
            writeInt(out, length);
            writeInt(out, encoder.finalRange());
            out.write(packet, 0, length);
            assertArrayEquals(java.util.Arrays.copyOf(packet, length), publicPackets.get(index).data(),
                    "public encoder packet " + index);
            coded += frame;
            index++;
        }
        assertEquals(index, publicPackets.size(), "packet count of the public encoder");
        var digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(out.toByteArray()));
        assertEquals(f[11], digest, "bitstream of " + f[0]);
    }

    /** Encodes through the public API, feeding random chunk sizes to exercise the FIFO. */
    static List<Packet> encodePublic(OpusEncoderOptions config, short[] input) {
        var encoder = new OpusEncoder(config);
        var channels = config.channels();
        var total = input.length / channels;
        var packets = new ArrayList<Packet>();
        var random = new Random(total);
        var position = 0;
        while (position < total) {
            var count = Math.min(total - position, 1 + random.nextInt(3000));
            var planes = new float[channels][count];
            for (var c = 0; c < channels; c++) {
                for (var i = 0; i < count; i++) {
                    planes[c][i] = (1.0f / 32768) * input[(position + i) * channels + c];
                }
            }
            encoder.encode(new AudioBuffer(encoder.layout(), config.sampleRate(), planes, count), packets::add);
            position += count;
        }
        encoder.flush(packets::add);
        return packets;
    }

    static void configure(OpusStreamEncoder encoder, OpusEncoderOptions config) {
        encoder.setBitrate(config.bitrate().getAsInt());
        encoder.setVbr(config.bitrateMode() != OpusBitrateMode.CBR);
        encoder.setVbrConstraint(config.bitrateMode() == OpusBitrateMode.CONSTRAINED_VBR);
        encoder.setComplexity(config.complexity());
        encoder.setSignal(switch (config.signal()) {
            case AUTO -> OpusDefines.OPUS_AUTO;
            case VOICE -> OpusDefines.OPUS_SIGNAL_VOICE;
            case MUSIC -> OpusDefines.OPUS_SIGNAL_MUSIC;
        });
        if (config.maxBandwidth() != OpusBandwidth.FULLBAND) {
            encoder.setMaxBandwidth(OpusDefines.OPUS_BANDWIDTH_NARROWBAND + config.maxBandwidth().ordinal());
        }
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write(value >>> 24);
        out.write(value >>> 16);
        out.write(value >>> 8);
        out.write(value);
    }

    /** Returns one of the deterministic inputs, as interleaved 16-bit samples. */
    static short[] input(String name) {
        return INPUTS.computeIfAbsent(name, OpusEncoderGoldenTest::build);
    }

    private static short[] vector(int n) {
        return OpusVectors.readPcm16(OpusVectors.directory().resolve(String.format("testvector%02d.dec", n)));
    }

    private static short[] take(short[] a, int div, int channels, int seconds) {
        var frames = a.length / 2;
        var n = Math.min(frames / div, seconds * 48000 / div);
        var out = new short[n * channels];
        for (var i = 0; i < n; i++) {
            var j = i * div;
            if (channels == 1) {
                out[i] = a[2 * j];
            } else {
                out[2 * i] = a[2 * j];
                out[2 * i + 1] = a[2 * j + 1];
            }
        }
        return out;
    }

    private static short[] concat(short[] a, short[] b) {
        var out = java.util.Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static short[] build(String name) {
        return switch (name) {
            case "speech16k" -> take(vector(3), 3, 1, 10);
            case "speech8k" -> take(vector(3), 6, 1, 10);
            case "speech12k" -> take(vector(3), 4, 1, 10);
            case "speech24k" -> take(vector(3), 2, 1, 10);
            case "speech48k" -> take(vector(3), 1, 1, 10);
            case "speech16k_stereo" -> take(vector(3), 3, 2, 10);
            case "music48k" -> take(vector(7), 1, 2, 10);
            case "music48k_mono" -> take(vector(7), 1, 1, 10);
            case "mixed48k" -> concat(input("speech48k"), input("music48k_mono"));
            case "mixed48k_stereo" -> {
                var speech = input("speech48k");
                var doubled = new short[speech.length * 2];
                for (var i = 0; i < speech.length; i++) {
                    doubled[2 * i] = speech[i];
                    doubled[2 * i + 1] = speech[i];
                }
                yield concat(doubled, input("music48k"));
            }
            case "alt48k" -> {
                var speech = vector(3);
                var music = vector(11);
                var chunk = 48000 * 2;
                var out = new short[5 * chunk];
                for (var k = 0; k < 5; k++) {
                    var source = k % 2 == 0 ? speech : music;
                    for (var i = 0; i < chunk; i++) {
                        var j = (k * chunk + i) % (source.length / 2);
                        out[k * chunk + i] = source[2 * j];
                    }
                }
                yield out;
            }
            case "alt16k" -> {
                var alt = input("alt48k");
                var out = new short[(alt.length + 2) / 3];
                for (var i = 0; i < out.length; i++) {
                    out[i] = alt[3 * i];
                }
                yield out;
            }
            case "alt48k_stereo" -> {
                var alt = input("alt48k");
                var out = new short[alt.length * 2];
                for (var i = 0; i < alt.length; i++) {
                    out[2 * i] = alt[i];
                    out[2 * i + 1] = (short) Math.floorDiv(-alt[i], 2);
                }
                yield out;
            }
            default -> throw new IllegalArgumentException(name);
        };
    }
}
