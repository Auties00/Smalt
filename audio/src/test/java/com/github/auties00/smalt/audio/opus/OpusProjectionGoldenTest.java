package com.github.auties00.smalt.audio.opus;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.opus.internal.OpusDefines;
import com.github.auties00.smalt.audio.opus.internal.OpusMultistreamEncoder;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks mapping family 3 (RFC 8486 projection) against libopus 1.5.2: the encoder must emit the packets and
 * final ranges of {@code opus_projection_ambisonics_encoder_create} with {@code opus_projection_encode_float}
 * (or {@code opus_projection_encode} for 16-bit input), its identification header must carry the demixing
 * matrix and gain libopus reports, and the decoder must reproduce, sample for sample, the output of
 * {@code opus_projection_decode_float} with the demixing gain set as the output gain. The reference hashes come
 * from {@code tools/testdata/opus-oracle/goldens.py} (its "p" lines); the inputs are the "amb" inputs of
 * {@link OpusMultistreamGoldenTest}.
 */
class OpusProjectionGoldenTest {
    /**
     * The fields of {@link OpusMultistreamGoldenTest#GOLDENS}: name, input, rate, channels, family, application,
     * bitrate, mode, complexity, frame us, signal, max bandwidth, FEC, loss, DTX, 16-bit input, SHA-256 of the
     * bitstream, SHA-256 of the libopus projection decoder's float output.
     */
    static final String[] GOLDENS = {
            "p4_128 amb4_4 48000 4 3 audio 128000 vbr 10 20000 auto auto 0 0 0 0 ffe32244170d1ad60c533f0fd090f1b70dd0581f4212608fab1b247db1d9b8d5 081f54423c9361998b00c4ecac330c81c5d678b6a8c05cdc333fc0790a2506c9",
            "p4_cbr96 amb4_4 48000 4 3 audio 96000 cbr 10 20000 auto auto 0 0 0 0 9cec03ecd2c39eddce5e767ac07cae1bd0456324fdb0fcec34e5afb9b7c91cb5 fe75dc307112d96ab08f6b9a23233c625cf94cd754c1bdc69f6caa965803e65c",
            "p4_s16 amb4_4 48000 4 3 audio 128000 vbr 10 20000 auto auto 0 0 0 1 990b48b2acbc776b1d2e21d5ce4af19c25ebc90bc3d9f93ca776d3de31860afb c20aaebd5f4c30e8a816831a751252f45c9c2ea953a7f2b68d3a3f6ba44622ae",
            "p4_auto amb4_4 48000 4 3 audio auto vbr 10 20000 auto auto 0 0 0 0 cf6bb9c392d645020a5985e4c753ac79dbef00e154821dae06c6d42704a66fd3 96aa4481a99f0ddd15f5bc0be7404ddca2747091b6c56d51d1cb1334a23c48fb",
            "p4_voip_dtx amb4_4 48000 4 3 voip 32000 vbr 10 20000 auto auto 1 20 1 0 27d9a53a492ee13e4b96fbaa8097f12797ce712ab50ba6586cb15456ad1fdf0e 89f19a924c0693319718b34f880298a69ae09d263c50e384e17e6911df271d33",
            "p6_160 amb6_4 48000 6 3 audio 160000 vbr 10 20000 auto auto 0 0 0 0 4bed0ef5a611ba77161ceaded5e24771ea59321716c0a24accc8993389696e56 0540aff3b5f72c2267bcb2192cdfb8a20d4b1c44503450f2b7280e0c1f8f1342",
            "p9_192 amb9_2 48000 9 3 audio 192000 vbr 10 20000 auto auto 0 0 0 0 cb94861a1d0b983bcd52948c3ff6cf97f4d8e3a3aad809e9c1ffc221659c2818 3a1763707f9956dbef749abd084b075ad589d1fa2e0f78667f10aacdeef3dd7f",
            "p9_voip_fec amb9_2 16000 9 3 voip 72000 vbr 10 20000 voice auto 1 10 0 0 ca563e080fc506c15a3f9b6f6cb5e3b2814d465c58fff34ea948b85b5bf16872 3c718af60aea5bb3055f46a5eca17aec537069c2e9a0c3b71f15162a0219801b",
            "p9_s16_cvbr amb9_2 48000 9 3 audio 160000 cvbr 10 20000 auto auto 0 0 0 1 cc81ee31ca780c5b4c54adcb97210328756331e2b7730568e6674ec97db820ad e98f2ef6404ac45924453aac6a97bfc43d24deadad640fd8a77aebc53e51a7ab",
            "p11_10ms amb11_2 48000 11 3 audio 220000 vbr 10 10000 auto auto 0 0 0 0 84bd8645a11066940173312618f27504f5803299f30c3c67a3a5b487bfe4dd4c 195ecd0063f65d8ee624b89b06552f5cfd28bac52ae7b9186782f18c8257f4fc",
            "p16_256 amb16_2 48000 16 3 audio 256000 vbr 10 20000 auto auto 0 0 0 0 d6b650567df112bdf2058bbeda2b17784f7016efd27dcb214c346771a16e2005 ec16c992d9a522c5c8916197fd2a696b9cff7f5aa0ae59a69fd5a8556f4e0f3f",
            "p18_60ms amb18_2 48000 18 3 audio 288000 vbr 10 60000 auto auto 0 0 0 0 0d749b60063ba8d263d3d5c3c7b1b1acdd77bd8d2e05c6227df3d7bc163065ff 6e8e8e2aa3cae4bda7c6de8bf4a6989cac50322e1ff4c4388914fefa711d65c0",
            "p25_c5 amb25_1 48000 25 3 audio 400000 vbr 5 20000 auto auto 0 0 0 0 338bce23ea4d21a67cc5a1872038a76da4cbc19343081fba0b9a9615ffb22914 03c921ff235b941042095582738bff2388a56733c882c296aa27426e01e8a633",
            "p36_c3 amb36_1 48000 36 3 audio 512000 vbr 3 20000 auto auto 0 0 0 0 6fe8a243d45d4608b1956bbe5e8bc6ffa7dc437355e21293be68fe2ad69a1df5 3e0140238a9d8c0338ee0449ee17ae2fa0395406b3d92ac8d2c52d69d4042aa7",
            "p38_cbr24k amb38_1 24000 38 3 audio 400000 cbr 5 20000 auto auto 0 0 0 0 ca0947c6be1e7b71c6042099c88435cd1cfc80a72a8e6aa27441f991d90102af 92a4443b7fa8ce7688b09170710b1af5047217553ab95ce6ee931e515a1c1ff6",
    };

    /**
     * The demixing gain and the SHA-256 of the demixing matrix libopus reports
     * ({@code OPUS_PROJECTION_GET_DEMIXING_MATRIX_GAIN}, {@code OPUS_PROJECTION_GET_DEMIXING_MATRIX}) per channel
     * count.
     */
    private static final Map<Integer, String> DEMIXING = Map.of(
            4, "0 05f3ed8da003073ae3bd66bc9d5d5e8ad6bbaedca71884bf8c8b38fe763fb350",
            6, "0 59377bceaf6285bce5d285a26558b3dfe0d3aebc9f4374578b77d796752fcfde",
            9, "3050 e983595fa270241b4ac42f01f457d225f3e5f9721708aff12fbfbe873fe570a7",
            11, "3050 c31679115ae64473729481a7f1ceea0bf114f64ea80e6f21fcc865fdf3102e74",
            16, "0 5ce1e8e350f477a3bd58816e665b80ae8349369b3ed44b28f879acf8496819a6",
            18, "0 bcda5e2c93d1cd7252eb84fae6795537aa4e24ed5308f84de3b3b6d729baabc6",
            25, "0 a1f892a93f728289b37af3c73994cdfbd53fe3c0d4462693c6384dd294ee5c15",
            36, "0 421b2fe2ea4d61b7508931d729bf77208163fdaa2e2764fa379a12838dae5c81",
            38, "0 9dd428cb3a2f63295f029b7a3b7d49298a7d2fdecfb4cfd768d5b06e183ccbcf");

    static Stream<String> goldens() {
        return Stream.of(GOLDENS);
    }

    static AudioTrack track(byte[] head, int rate) {
        return AudioTrack.builder().codec(Codec.OPUS).timeBase(Rational.timeBaseOf(48000)).codecConfig(head)
                .sampleRate(rate).build();
    }

    /** Encodes a golden line with the internal encoder, frame by frame, and returns the packets. */
    static List<byte[]> encode(String[] f, int capacityPerStream, ByteArrayOutputStream bitstream) {
        var input = OpusMultistreamGoldenTest.input(f[1]);
        var rate = Integer.parseInt(f[2]);
        var channels = Integer.parseInt(f[3]);
        var encoder = new OpusMultistreamEncoder(rate, channels, Integer.parseInt(f[4]), switch (f[5]) {
            case "voip" -> OpusDefines.OPUS_APPLICATION_VOIP;
            case "audio" -> OpusDefines.OPUS_APPLICATION_AUDIO;
            default -> OpusDefines.OPUS_APPLICATION_RESTRICTED_LOWDELAY;
        });
        OpusMultistreamGoldenTest.configure(encoder, f);
        var frame = (int) ((long) rate * Integer.parseInt(f[9]) / 1_000_000);
        var s16 = f[15].equals("1");
        var total = input.length / channels;
        var target = (long) total + encoder.lookahead();
        var floats = new float[frame * channels];
        var shorts = new short[frame * channels];
        var packet = new byte[encoder.streamCount() * capacityPerStream];
        var packets = new ArrayList<byte[]>();
        long coded = 0;
        while (coded < target) {
            for (var i = 0; i < frame * channels; i++) {
                var sample = coded * channels + i;
                var value = sample < (long) total * channels ? input[(int) sample] : 0;
                shorts[i] = value;
                floats[i] = (1.0f / 32768) * value;
            }
            var length = s16 ? encoder.encode(shorts, 0, frame, packet, 0, packet.length)
                    : encoder.encodeFloat(floats, 0, frame, packet, 0, packet.length);
            assertTrue(length > 0, "encode failed at packet " + packets.size() + ": " + length);
            OpusMultistreamGoldenTest.writeInt(bitstream, length);
            OpusMultistreamGoldenTest.writeInt(bitstream, encoder.finalRange());
            bitstream.write(packet, 0, length);
            packets.add(java.util.Arrays.copyOf(packet, length));
            coded += frame;
        }
        return packets;
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("goldens")
    @DisplayName("encodes the reference packets and decodes them to the reference samples")
    void bitExact(String line) {
        var f = line.split(" ");
        var bitstream = new ByteArrayOutputStream();
        var packets = encode(f, 6 * 1275 + 12 + 2, bitstream);
        assertEquals(f[16], sha256(bitstream.toByteArray()), "bitstream of " + f[0]);
        var encoder = new OpusEncoder(OpusMultistreamGoldenTest.options(f));
        var head = OpusHead.parse(encoder.codecConfig().orElseThrow(), MediaLimits.DEFAULT);
        var channels = Integer.parseInt(f[3]);
        assertEquals(3, head.mappingFamily());
        assertEquals((channels + 1) / 2, head.streamCount());
        assertEquals(channels / 2, head.coupledCount());
        var matrix = ByteBuffer.allocate(2 * head.demixing().length).order(ByteOrder.LITTLE_ENDIAN);
        matrix.asShortBuffer().put(head.demixing());
        assertEquals(DEMIXING.get(channels), head.outputGain() + " " + sha256(matrix.array()), "demixing of " + f[0]);
        var rate = Integer.parseInt(f[2]);
        var decoder = new OpusDecoder(track(encoder.codecConfig().orElseThrow(), rate),
                OpusDecoderOptions.builder().sampleRate(rate).build());
        assertEquals(channels, decoder.layout().channels());
        var pcm = new float[5760 * channels];
        var decoded = ByteBuffer.allocate(4 * pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        var digest = sha256Digest();
        for (var p : packets) {
            var samples = decoder.decode(p, 0, p.length, pcm, 0, 5760);
            decoded.clear();
            decoded.asFloatBuffer().put(pcm, 0, samples * channels);
            digest.update(decoded.array(), 0, 4 * samples * channels);
        }
        assertEquals(f[17], HexFormat.of().formatHex(digest.digest()), "libopus projection decode of " + f[0]);
    }

    static Stream<String> variableGoldens() {
        return Stream.of(GOLDENS).filter(line -> !line.split(" ")[7].equals("cbr"));
    }

    // Below one maximal packet per stream the streams are coded one after the other.
    @ParameterizedTest(name = "{0}")
    @MethodSource("variableGoldens")
    @DisplayName("codes the streams one after the other with the same result when the packet space is smaller")
    void sequential(String line) {
        var f = line.split(" ");
        var bitstream = new ByteArrayOutputStream();
        encode(f, 6 * 1275 + 12 - 1, bitstream);
        assertEquals(f[16], sha256(bitstream.toByteArray()), "bitstream of " + f[0]);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("goldens")
    @DisplayName("the public encoder emits the reference packets and the track decoder the reference samples")
    void publicApi(String line) {
        var f = line.split(" ");
        var input = OpusMultistreamGoldenTest.input(f[1]);
        var channels = Integer.parseInt(f[3]);
        var encoder = new OpusEncoder(OpusMultistreamGoldenTest.options(f));
        assertEquals(OpusChannelMapping.AMBISONIC_PROJECTION, encoder.options().channelMapping());
        assertEquals((channels + 1) / 2, encoder.streamCount());
        var total = input.length / channels;
        var out = new ByteArrayOutputStream();
        var packets = new ArrayList<Packet>();
        if (f[15].equals("1")) {
            var frame = encoder.frameSize();
            var interleaved = new short[frame * channels];
            var packet = new byte[encoder.streamCount() * 1500];
            long coded = 0;
            long target = (long) total + encoder.delay();
            while (coded < target) {
                for (var i = 0; i < frame * channels; i++) {
                    var sample = coded * channels + i;
                    interleaved[i] = sample < (long) total * channels ? input[(int) sample] : 0;
                }
                var length = encoder.encode(interleaved, 0, frame, packet, 0, packet.length);
                OpusMultistreamGoldenTest.writeInt(out, length);
                OpusMultistreamGoldenTest.writeInt(out, encoder.finalRange());
                out.write(packet, 0, length);
                packets.add(new Packet(0, java.util.Arrays.copyOf(packet, length), coded, coded, frame, true));
                coded += frame;
            }
        } else {
            var random = new java.util.Random(total);
            var position = 0;
            java.util.function.Consumer<Packet> sink = packet -> {
                packets.add(packet);
                OpusMultistreamGoldenTest.writeInt(out, packet.data().length);
                OpusMultistreamGoldenTest.writeInt(out, encoder.finalRange());
                out.writeBytes(packet.data());
            };
            while (position < total) {
                var count = Math.min(total - position, 1 + random.nextInt(3000));
                var planes = new float[channels][count];
                for (var c = 0; c < channels; c++) {
                    for (var i = 0; i < count; i++) {
                        planes[c][i] = (1.0f / 32768) * input[(position + i) * channels + c];
                    }
                }
                encoder.encode(new AudioBuffer(encoder.layout(), encoder.sampleRate(), planes, count), sink);
                position += count;
            }
            encoder.flush(sink);
        }
        assertEquals(f[16], sha256(out.toByteArray()), "public bitstream of " + f[0]);
        // The track decoder drops the pre-skip and otherwise equals the reference decode.
        var rate = Integer.parseInt(f[2]);
        var head = encoder.codecConfig().orElseThrow();
        var decoder = new OpusDecoder(track(head, rate), OpusDecoderOptions.builder().sampleRate(rate).build());
        var skip = (long) OpusHead.parse(head, MediaLimits.DEFAULT).preSkip() * rate / 48000;
        assertEquals(encoder.delay(), skip);
        var digest = sha256Digest();
        var reference = new OpusDecoder(track(head, rate), OpusDecoderOptions.builder().sampleRate(rate).build());
        var pcm = new float[5760 * channels];
        var bytes = ByteBuffer.allocate(4 * pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        var expected = sha256Digest();
        long produced = 0;
        for (var packet : packets) {
            var samples = reference.decode(packet.data(), 0, packet.data().length, pcm, 0, 5760);
            var from = (int) Math.max(0, Math.min(samples, skip - produced));
            produced += samples;
            bytes.clear();
            bytes.asFloatBuffer().put(pcm, from * channels, (samples - from) * channels);
            expected.update(bytes.array(), 0, 4 * (samples - from) * channels);
            decoder.decode(packet, buffer -> {
                var interleaved = ByteBuffer.allocate(4 * channels * buffer.frames()).order(ByteOrder.LITTLE_ENDIAN);
                for (var i = 0; i < buffer.frames(); i++) {
                    for (var c = 0; c < channels; c++) {
                        interleaved.putFloat(buffer.plane(c)[i]);
                    }
                }
                digest.update(interleaved.array());
            });
        }
        assertTrue(skip > 0);
        assertArrayEquals(expected.digest(), digest.digest(), "track decode of " + f[0]);
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }
}
