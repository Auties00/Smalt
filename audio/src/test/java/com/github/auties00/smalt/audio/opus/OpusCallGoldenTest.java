package com.github.auties00.smalt.audio.opus;

import com.github.auties00.smalt.audio.opus.internal.OpusDefines;
import com.github.auties00.smalt.audio.opus.internal.OpusStreamDecoder;
import com.github.auties00.smalt.audio.opus.internal.OpusStreamEncoder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the voice-call configuration against the libopus 1.5.2 reference (float build without
 * SIMD): 16 kHz mono VOIP at 25 kb/s VBR, complexity 9, in-band FEC at several expected loss
 * rates, DTX, voice signal, 16-bit depth, wideband cap and 60 ms frames. The SHA-256 of the
 * packets and final ranges (in {@code opus_demo} bitstream form) must match the reference encoder,
 * and decoding them at 16 kHz while recovering every fifth packet from the next packet's FEC and
 * concealing every seventh must produce the reference decoder's float samples bit for bit.
 *
 * <p>The inputs are the deterministic speech and speech/music signals of
 * {@link OpusEncoderGoldenTest}, plus a gated speech signal whose digital silences exercise DTX.
 */
class OpusCallGoldenTest {
    static short[] input(String name) {
        if (!name.equals("gated16k")) {
            return OpusEncoderGoldenTest.input(name);
        }
        // one second of silence after every second of speech, so DTX engages
        var speech = OpusEncoderGoldenTest.input("speech16k").clone();
        for (var i = 0; i < speech.length; i++) {
            if ((i / 16000) % 2 == 1) {
                speech[i] = 0;
            }
        }
        return speech;
    }

    @ParameterizedTest(name = "{0} loss {1}%")
    @CsvSource({
            "speech16k, 10, 0ef3bcf8f6ada573714235ebb8612d9666d25fc36e3fb627218e512540a406a5, d43c66382501ae42e402e59c07be8038f8a3b880fe3c88156307a77641c3e3dd",
            "speech16k, 0, c73f4b94543392df9484a9f313d923ee0bd0e4309116dbc9cfcc7db683215769, 7a3aad29fba51b56d5ad439525ffec28a3903c376c45616136c7e39a5f3daecc",
            "alt16k, 30, 5f763b98f55199e931deeaa7e0c15f95cf85d293911a39c478e214d8616c513d, abdc3268a380a159c128f1f4f36eb142d8d35daf7d6e5196e8ccbfcfea245594",
            "gated16k, 10, b2d94856d22e86f86bea037c41078f21ca23b239573739d8326d13e6af039f37, 9530ad6d89ffee55a06aaa20839148a4f7649be28d32a720dc800a8ce5449f8d",
    })
    @DisplayName("encodes and decodes with FEC and concealment exactly like the libopus reference")
    void bitExact(String name, int loss, String bitstreamHash, String decodedHash) throws Exception {
        var pcm = input(name);
        var encoder = new OpusStreamEncoder(16000, 1, OpusDefines.OPUS_APPLICATION_VOIP);
        encoder.setBitrate(25000);
        encoder.setVbr(true);
        encoder.setComplexity(9);
        encoder.setInbandFec(1);
        encoder.setPacketLossPerc(loss);
        encoder.setDtx(true);
        encoder.setSignal(OpusDefines.OPUS_SIGNAL_VOICE);
        encoder.setLsbDepth(16);
        encoder.setMaxBandwidth(OpusDefines.OPUS_BANDWIDTH_WIDEBAND);
        var frame = 960;
        var total = pcm.length;
        var target = (long) total + encoder.lookahead();
        var buffer = new float[frame];
        var packet = new byte[1500];
        var bitstream = new ByteArrayOutputStream();
        var packets = new ArrayList<byte[]>();
        long coded = 0;
        long position = 0;
        while (coded < target) {
            for (var i = 0; i < frame; i++) {
                var sample = position + i;
                buffer[i] = sample < total ? (1.0f / 32768) * pcm[(int) sample] : 0;
            }
            position += frame;
            var length = encoder.encodeFloat(buffer, 0, frame, packet, 0, packet.length);
            assertTrue(length > 0, "encode failed: " + length);
            writeInt(bitstream, length);
            writeInt(bitstream, encoder.finalRange());
            bitstream.write(packet, 0, length);
            packets.add(java.util.Arrays.copyOf(packet, length));
            coded += frame;
        }
        assertEquals(bitstreamHash, sha256(bitstream.toByteArray()), "bitstream");

        var decoder = new OpusStreamDecoder(16000, 1);
        var out = new float[2 * frame];
        var decoded = new ByteArrayOutputStream();
        for (var i = 0; i < packets.size(); i++) {
            int samples;
            if (i % 5 == 3 && i + 1 < packets.size()) {
                var next = packets.get(i + 1);
                samples = decoder.decodeNative(next, 0, next.length, out, 0, frame, true, false, null, false);
            } else if (i % 7 == 5) {
                samples = decoder.decodeNative(null, 0, 0, out, 0, frame, false, false, null, false);
            } else {
                var p = packets.get(i);
                samples = decoder.decodeNative(p, 0, p.length, out, 0, frame, false, false, null, false);
            }
            assertTrue(samples > 0, "decode failed at packet " + i + ": " + samples);
            for (var s = 0; s < samples; s++) {
                var bits = Float.floatToRawIntBits(out[s]);
                decoded.write(bits);
                decoded.write(bits >>> 8);
                decoded.write(bits >>> 16);
                decoded.write(bits >>> 24);
            }
        }
        assertEquals(decodedHash, sha256(decoded.toByteArray()), "decoded samples");
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write(value >>> 24);
        out.write(value >>> 16);
        out.write(value >>> 8);
        out.write(value);
    }
}
