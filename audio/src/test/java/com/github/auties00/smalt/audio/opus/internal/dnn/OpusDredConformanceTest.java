package com.github.auties00.smalt.audio.opus.internal.dnn;

import com.github.auties00.smalt.testkit.Conformance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the DRED decoding vectors of draft-ietf-mlcodec-opus-dred (the {@code dred_testvectors} tarball of
 * opus-codec.org, made by libopus 1.6): each {@code vectorN_dred.bit} holds records of a quantizer level, a chunk
 * count and the entropy-coded initial state and latents of that many 20 ms chunks, which the RDOVAE decoder must turn
 * into the features of {@code vectorN_dred_dec.f32} within the thresholds the draft's {@code dred_vectors.sh} gives
 * {@code dred_compare -features}: a total RMS error of at most 0.5, a largest per-feature RMS error of at most 0.15 and
 * a mean pitch error of at most 0.02 over the voiced frames.
 *
 * <p>The decoder follows libopus 1.5.2, whose DRED (experimental version 10) has the models of that release; the
 * vectors are of the standardized DRED of libopus 1.6, whose RDOVAE decoder also takes the quantizer level as an
 * input, so they need its port.
 */
@DisplayName("DRED decoding conformance vectors")
class OpusDredConformanceTest {
    private static final String TARBALL = "opus-dred_testvectors.tar.gz";

    private static Map<String, byte[]> files;

    private static synchronized Map<String, byte[]> files() {
        if (files != null) {
            return files;
        }
        var map = new HashMap<String, byte[]>();
        try (var input = new GZIPInputStream(Files.newInputStream(Conformance.vector(TARBALL)), 1 << 16)) {
            untar(input, map);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        files = map;
        return map;
    }

    private static void untar(InputStream input, Map<String, byte[]> out) throws IOException {
        var header = new byte[512];
        while (input.readNBytes(header, 0, 512) == 512) {
            var empty = true;
            for (var b : header) {
                if (b != 0) {
                    empty = false;
                    break;
                }
            }
            if (empty) {
                return;
            }
            var name = new String(header, 0, 100, StandardCharsets.US_ASCII).replace("\0", "").trim();
            var sizeField = new String(header, 124, 12, StandardCharsets.US_ASCII).replace("\0", "").trim();
            var size = sizeField.isEmpty() ? 0 : Long.parseLong(sizeField, 8);
            var type = header[156];
            var padded = (size + 511) / 512 * 512;
            if (type == '0' || type == 0) {
                out.put(name.substring(name.lastIndexOf('/') + 1), input.readNBytes((int) size));
                input.skipNBytes(padded - size);
            } else {
                input.skipNBytes(padded);
            }
        }
    }

    private static byte[] file(String name) {
        var data = files().get(name);
        assertTrue(data != null, name + " is not in " + TARBALL);
        return data;
    }

    private static float[] floats(byte[] data) {
        var out = new float[data.length / 4];
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out);
        return out;
    }

    /** Decodes a {@code dred.bit} file as {@code fargan_demo -dred-decoding} does, into its feature frames. */
    static float[] decode(byte[] bits) {
        var buffer = ByteBuffer.wrap(bits).order(ByteOrder.BIG_ENDIAN);
        var decoder = new Dred.RdovaeDecoder();
        var dred = new Dred();
        var out = new java.io.ByteArrayOutputStream();
        var frame = new float[Dred.NUM_FEATURES];
        var bytes = ByteBuffer.allocate(4 * Dred.NUM_FEATURES).order(ByteOrder.LITTLE_ENDIAN);
        while (buffer.remaining() >= 12) {
            var q0 = buffer.getInt();
            var chunks = buffer.getInt();
            var length = buffer.getInt();
            assertTrue(length >= 0 && length <= buffer.remaining(), "record of " + length + " bytes");
            assertEquals(0, chunks % 2, "odd chunk count " + chunks);
            var payload = new byte[length];
            buffer.get(payload);
            var latents = dred.ecDecodeFixed(payload, 0, length, q0, chunks);
            assertEquals(chunks / 2, latents, "latents of a record");
            dred.process(decoder);
            // the latent of chunks i and i - 1, the j-th decoded, gives four frames newest first: frame 2i - 2 + k is
            // its frame 3 - k
            var frames = new float[2 * chunks][];
            for (var j = 0; j < latents; j++) {
                var i = chunks - 1 - 2 * j;
                for (var k = 0; k < 4; k++) {
                    dred.features(4 * j + 3 - k, frame);
                    frames[2 * i - 2 + k] = frame.clone();
                }
            }
            for (var f : frames) {
                bytes.clear();
                bytes.asFloatBuffer().put(f);
                out.writeBytes(bytes.array());
            }
        }
        return floats(out.toByteArray());
    }

    @ParameterizedTest(name = "vector {0}")
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8})
    @DisplayName("decodes the DRED of each vector to its reference features within the draft's thresholds")
    void dredDecoding(int vector) {
        var reference = floats(file("vector" + vector + "_dred_dec.f32"));
        var ours = decode(file("vector" + vector + "_dred.bit"));
        assertEquals(reference.length, ours.length, "feature count");
        var n = Dred.NUM_FEATURES;
        var mse = new double[n];
        var count = 0;
        var pitchCount = 0;
        var pitchError = 0.0;
        for (var at = 0; at + n <= reference.length; at += n) {
            for (var i = 0; i < n; i++) {
                var e = (double) reference[at + i] - ours[at + i];
                mse[i] += e * e;
            }
            if (reference[at + n - 1] > .2) {
                pitchError += Math.abs(reference[at + n - 2] - ours[at + n - 2]);
                pitchCount++;
            }
            count++;
        }
        pitchError /= pitchCount;
        var total = 0.0;
        var max = 0.0;
        for (var i = 0; i < n; i++) {
            mse[i] /= count;
            if (i != n - 2) {
                total += mse[i];
                max = Math.max(max, mse[i]);
            }
        }
        total = Math.sqrt(total);
        max = Math.sqrt(max);
        var summary = String.format(java.util.Locale.ROOT, "vector %d: total %.4f, max %.4f, pitch %.4f", vector, total,
                max, pitchError);
        System.out.println("[dred-compare] " + summary);
        assertTrue(total <= .5 && max <= .15 && pitchError <= .02, summary + " (thresholds 0.5 0.15 0.02)");
    }
}
