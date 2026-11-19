package com.github.auties00.smalt.audio.aac.internal.drc;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.aac.internal.AacBitReader;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Fixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plays the in-band items of the DRC conformance smoke set under playback requests their own interface payloads do
 * not make: every target configuration request, loudness normalization at several targets and with every parameter of
 * its interface, the three kinds of DRC feature request, the compression and boost scales, and the loudness EQ
 * extension. Every request must decode to finite samples, a lower target loudness must not make the output louder,
 * and a reserved request must be refused.
 */
@DisplayName("MPEG-D DRC playback requests")
class DrcRequestMatrixTest {
    private static final int FRAMES = 24;

    /**
     * Writes a {@code uniDrcInterface()} payload, most significant bit first.
     */
    static final class Interface {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private int buffer;
        private int count;

        Interface put(int value, int width) {
            for (var bit = width - 1; bit >= 0; bit--) {
                buffer = buffer << 1 | (value >>> bit & 1);
                if (++count == 8) {
                    out.write(buffer);
                    buffer = 0;
                    count = 0;
                }
            }
            return this;
        }

        byte[] bytes() {
            var copy = new ByteArrayOutputStream();
            copy.writeBytes(out.toByteArray());
            if (count > 0) {
                copy.write(buffer << (8 - count));
            }
            copy.writeBytes(new byte[4]);
            return copy.toByteArray();
        }
    }

    /**
     * Writes a payload whose parts are each present or absent.
     *
     * @param system the system interface after its presence bit, or {@code null}
     * @param control the loudness normalization control after its presence bit, or {@code null}
     * @param parameters the loudness normalization parameters after their presence bit, or {@code null}
     * @param drc the dynamic range control interface after its presence bit, or {@code null}
     * @param scales the compression and boost parameters after their presence bit, or {@code null}
     * @param extension the extension after its presence bit, or {@code null}
     */
    static byte[] request(Consumer<Interface> system, Consumer<Interface> control, Consumer<Interface> parameters,
                          Consumer<Interface> drc, Consumer<Interface> scales, Consumer<Interface> extension) {
        var payload = new Interface().put(1, 1).put(3, 8).put(1, 8).put(0xAB, 8).put(0xCD, 8);
        for (var part : new Consumer[]{system, control, parameters, drc, scales, extension}) {
            if (part == null) {
                payload.put(0, 1);
            } else {
                payload.put(1, 1);
                @SuppressWarnings("unchecked")
                var writer = (Consumer<Interface>) part;
                writer.accept(payload);
            }
        }
        return payload.bytes();
    }

    static byte[] loudness(int target) {
        return request(null, i -> i.put(1, 1).put(-target * 32, 12), null, null, null, null);
    }

    static Map<String, byte[]> requests() {
        var requests = new LinkedHashMap<String, byte[]>();
        requests.put("default", null);
        requests.put("downmix 0", request(i -> i.put(0, 2).put(0, 4), null, null, null, null, null));
        requests.put("downmix ids", request(i -> i.put(0, 2).put(3, 4).put(1, 7).put(2, 7).put(0x7F, 7), null, null,
                null, null, null));
        requests.put("layout", request(i -> i.put(1, 2).put(1, 8), null, null, null, null, null));
        requests.put("stereo", request(i -> i.put(2, 2).put(1, 7), null, null, null, null, null));
        requests.put("mono", request(i -> i.put(2, 2).put(0, 7), null, null, null, null, null));
        requests.put("loudness -16", loudness(-16));
        requests.put("loudness -31", loudness(-31));
        requests.put("loudness off", request(null, i -> i.put(0, 1), null, null, null, null));
        requests.put("loudness parameters", request(null, i -> i.put(1, 1).put(24 * 32, 12),
                i -> i.put(1, 1).put(1, 1).put(1, 1).put(10, 6).put(1, 1).put(2, 3).put(1, 1).put(1, 4).put(1, 1)
                        .put(1, 2).put(1, 1).put(10, 6).put(1, 1).put(12, 6).put(1, 1).put(600, 10).put(1, 1).put(4, 6),
                null, null, null));
        requests.put("loudness parameters, defaults", request(null, i -> i.put(1, 1).put(31 * 32, 12),
                i -> i.put(0, 1).put(0, 1).put(0, 5).put(1, 1).put(63, 6).put(0, 2), null, null, null));
        requests.put("drc off", request(null, null, null, i -> i.put(0, 1), null, null));
        requests.put("drc effects", request(null, null, null, i -> i.put(1, 1).put(1, 3).put(0, 2).put(3, 4).put(1, 4)
                .put(1, 4).put(2, 4).put(6, 4), null, null));
        requests.put("drc effects, general", request(null, null, null, i -> i.put(1, 1).put(1, 3).put(0, 2).put(1, 4)
                .put(1, 4).put(6, 4), null, null));
        requests.put("drc dynamic range", request(null, null, null, i -> i.put(1, 1).put(2, 3).put(1, 2).put(1, 2)
                .put(0, 1).put(40, 8).put(1, 2).put(2, 2).put(1, 1).put(150, 8).put(220, 8), null, null));
        requests.put("drc characteristic", request(null, null, null, i -> i.put(1, 1).put(1, 3).put(2, 2).put(3, 7),
                null, null));
        requests.put("scales", request(null, null, null, null, i -> i.put(1, 1).put(1, 1).put(64, 8).put(255, 8)
                .put(1, 1).put(5, 8), null));
        requests.put("compression only", request(null, null, null, null, i -> i.put(1, 1).put(0, 1).put(255, 8)
                .put(0, 1), null));
        requests.put("loudness eq", request(null, i -> i.put(1, 1).put(20 * 32, 12), null, null, null,
                i -> i.put(1, 4).put(0, 4).put(15, 4).put(1, 1).put(1, 1).put(2, 2).put(1, 1).put(60, 7).put(1, 1)
                        .put(6, 7).put(1, 1).put(0x0FFF, 16).put(0, 4)));
        requests.put("loudness eq, purpose only", request(null, null, null, null, null,
                i -> i.put(1, 4).put(0, 4).put(15, 4).put(0, 1).put(1, 1).put(0x0001, 16).put(0, 4)));
        requests.put("unknown extension", request(null, null, null, null, null,
                i -> i.put(2, 4).put(3, 4).put(7, 7).put(0xAB, 8).put(0, 4)));
        return requests;
    }

    private record Item(String name, MpegDrcConformanceTest.Wav input, byte[] bitstream) {
    }

    private static ArrayList<Item> items() {
        var archive = Conformance.archive("iso-23003-4-drc-smoke");
        var members = new HashMap<String, String>();
        for (var member : archive.members()) {
            members.put(member.substring(member.lastIndexOf('/') + 1), member);
        }
        var items = new ArrayList<Item>();
        var seen = new java.util.HashSet<String>();
        for (var line : MpegDrcConformanceTest.COMMANDS.lines().map(String::trim).toList()) {
            var bitstream = MpegDrcConformanceTest.argument(line, "-ib");
            var input = MpegDrcConformanceTest.argument(line, "-if");
            if (bitstream == null || !members.containsKey(bitstream) || !members.containsKey(input)
                    || MpegDrcConformanceTest.argument(line, "-af") != null || !seen.add(bitstream)) {
                continue;
            }
            items.add(new Item(bitstream, MpegDrcConformanceTest.readWav(Fixtures.read(archive.fetch(members.get(input)))),
                    Fixtures.read(archive.fetch(members.get(bitstream)))));
        }
        return items;
    }

    /**
     * Decodes the first frames of an item under a request.
     *
     * @return the mean square of the output
     */
    private static double play(Item item, byte[] request) {
        var channels = item.input().planes().length;
        var decoder = MpegDrcDecoder.inBand(item.input().rate(), channels, 1024, 0, request);
        var reader = new AacBitReader(item.bitstream());
        var frame = new float[channels][1024];
        var energy = 0.0;
        var samples = 0L;
        for (var f = 0; f < FRAMES; f++) {
            var start = f * 1024;
            if (start >= item.input().planes()[0].length) {
                break;
            }
            for (var c = 0; c < channels; c++) {
                var count = Math.min(1024, item.input().planes()[c].length - start);
                System.arraycopy(item.input().planes()[c], start, frame[c], 0, count);
            }
            try {
                decoder.processInBand(frame, reader.bitsLeft() >= 8 ? reader : null);
            } catch (MediaException.Malformed exception) {
                if (!String.valueOf(exception.getMessage()).contains("truncated")) {
                    throw exception;
                }
                break;
            }
            for (var c = 0; c < decoder.outputChannels(); c++) {
                for (var value : frame[c]) {
                    assertTrue(Float.isFinite(value), item.name() + ": a finite sample");
                    energy += (double) value * value;
                    samples++;
                }
            }
        }
        return samples == 0 ? 0 : energy / samples;
    }

    @TestFactory
    @DisplayName("every in-band item plays under every request")
    Stream<DynamicTest> matrix() {
        var items = items();
        var requests = requests();
        var tests = new ArrayList<DynamicTest>();
        for (var item : items) {
            for (var request : requests.entrySet()) {
                tests.add(DynamicTest.dynamicTest(item.name() + " / " + request.getKey(), () -> play(item,
                        request.getValue())));
            }
            tests.add(DynamicTest.dynamicTest(item.name() + " / a lower target is not louder", () -> {
                var loud = play(item, loudness(-16));
                var quiet = play(item, loudness(-31));
                assertTrue(quiet <= loud * 1.0001 + 1e-12, "-31 LKFS " + quiet + " against -16 LKFS " + loud);
            }));
        }
        return tests.stream();
    }

    /**
     * Writes a loudness normalization request with every measurement preference.
     */
    static byte[] measured(int album, int method, int system, int preprocessing, int cutoff) {
        return request(null, i -> i.put(1, 1).put(23 * 32, 12), i -> i.put(album, 1).put(1, 1).put(1, 1).put(12, 6)
                .put(1, 1).put(method, 3).put(1, 1).put(system, 4).put(1, 1).put(preprocessing, 2).put(1, 1)
                .put(cutoff, 6).put(0, 3), i -> i.put(1, 1).put(1, 3).put(0, 2).put(1, 4).put(1, 4).put(1, 4), null,
                null);
    }

    @TestFactory
    @DisplayName("every in-band item plays under every loudness measurement preference")
    Stream<DynamicTest> measurements() {
        var tests = new ArrayList<DynamicTest>();
        for (var item : items()) {
            for (var album = 0; album < 2; album++) {
                for (var method = 0; method < 3; method++) {
                    for (var system = 0; system < 9; system++) {
                        for (var preprocessing = 0; preprocessing < 3; preprocessing++) {
                            var request = measured(album, method, system, preprocessing, 1 + system * 6);
                            tests.add(DynamicTest.dynamicTest(item.name() + " / album " + album + ", method " + method
                                    + ", system " + system + ", preprocessing " + preprocessing,
                                    () -> play(item, request)));
                        }
                    }
                }
            }
        }
        return tests.stream();
    }

    @Test
    @DisplayName("refuses reserved requests, too many extensions, and a cut payload")
    void reserved() {
        var reserved = request(i -> i.put(3, 2), null, null, null, null, null);
        var feature = request(null, null, null, i -> i.put(1, 1).put(1, 3).put(3, 2), null, null);
        var extensions = request(null, null, null, null, null, i -> i.put(2, 4).put(0, 4).put(0, 4).put(0, 1)
                .put(2, 4).put(0, 4).put(0, 4).put(0, 1).put(0, 4));
        for (var payload : new byte[][]{reserved, feature, extensions, new byte[]{(byte) 0x80}}) {
            assertThrows(MediaException.Malformed.class, () -> MpegDrcDecoder.inBand(48000, 2, 1024, 0, payload));
        }
    }
}
