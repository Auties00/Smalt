package com.github.auties00.smalt.audio.aac.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.bits.MsbBitWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parses hand-built {@code individual_channel_stream}s of the object types beyond Low Complexity:
 * AAC Main prediction data, the long term prediction data of AAC LTP and ER AAC LTP in both the
 * logical and the error resilient field order, and SSR gain control data. Every case checks the
 * parsed values and that the parse consumes exactly the bits written.
 */
class ObjectTypeSyntaxTest {
    /** The table index of 48 kHz. */
    private static final int RATE_48000 = 3;

    static ChannelStream stream(int objectType) {
        return new ChannelStream(1024, RATE_48000, objectType, AacKernels.scalar());
    }

    static AacBitReader reader(Consumer<MsbBitWriter> body) {
        var writer = new MsbBitWriter();
        body.accept(writer);
        // a marker after the payload, so a parse that reads too far or too little is visible
        writer.write(0x5A, 8);
        return new AacBitReader(writer.toByteArray());
    }

    static void assertConsumed(AacBitReader reader) {
        assertEquals(0x5A, reader.read(8), "the parse ends exactly where the payload ends");
    }

    /** Writes a long-window {@code ics_info} up to and excluding {@code predictor_data_present}. */
    static void longInfo(MsbBitWriter writer, int maxBands) {
        writer.write(0, 1);
        writer.write(ChannelStream.ONLY_LONG_SEQUENCE, 2);
        writer.write(0, 1);
        writer.write(maxBands, 6);
    }

    /** Writes one section of {@code length} zero-codebook bands. */
    static void zeroSection(MsbBitWriter writer, int length) {
        writer.write(Codebooks.ZERO_HCB, 4);
        writer.write(length, 5);
    }

    /** Writes the flags after the scalefactors: no pulses, the given TNS flag, no gain control. */
    static void flags(MsbBitWriter writer, boolean tns) {
        writer.write(0, 1);
        writer.write(tns ? 1 : 0, 1);
        writer.write(0, 1);
    }

    /** Writes the TNS data of a long window: one first-order filter over 20 bands. */
    static void tns(MsbBitWriter writer) {
        writer.write(1, 2);
        writer.write(1, 1);
        writer.write(20, 6);
        writer.write(1, 5);
        writer.write(0, 1);
        writer.write(0, 1);
        writer.write(3, 4);
    }

    /** Writes {@code ltp_data_present} set and an {@code ltp_data} for {@code bands} bands. */
    static void ltp(MsbBitWriter writer, int lag, int coefficient, int bands, int usedMask) {
        writer.write(1, 1);
        writer.write(lag, 11);
        writer.write(coefficient, 3);
        for (var band = 0; band < bands; band++) {
            writer.write(usedMask >>> band & 1, 1);
        }
    }

    @Nested
    @DisplayName("AAC Main prediction data")
    class Main {
        @Test
        @DisplayName("reads the reset group and the prediction_used flags up to max_sfb")
        void predictionData() {
            var stream = stream(AudioSpecificConfig.AOT_MAIN);
            var reader = reader(writer -> {
                writer.write(100, 8);
                longInfo(writer, 3);
                writer.write(1, 1);
                writer.write(1, 1);
                writer.write(7, 5);
                writer.write(0b101, 3);
                zeroSection(writer, 3);
                flags(writer, false);
            });
            stream.parse(reader, false, false);
            assertConsumed(reader);
            assertTrue(stream.predictorPresent());
            assertEquals(7, stream.predictorResetGroup());
            assertTrue(stream.predictionUsed(0));
            assertFalse(stream.predictionUsed(1));
            assertTrue(stream.predictionUsed(2));
            assertFalse(stream.predictionUsed(3));
        }

        @Test
        @DisplayName("shares the prediction data of a common window with the second channel")
        void commonWindow() {
            var left = stream(AudioSpecificConfig.AOT_MAIN);
            var right = stream(AudioSpecificConfig.AOT_MAIN);
            var reader = reader(writer -> {
                longInfo(writer, 2);
                writer.write(1, 1);
                writer.write(0, 1);
                writer.write(0b10, 2);
            });
            left.readInfo(reader, right);
            right.copyInfo(left);
            assertConsumed(reader);
            assertTrue(right.predictorPresent());
            assertEquals(0, right.predictorResetGroup());
            assertTrue(right.predictionUsed(0));
            assertFalse(right.predictionUsed(1));
        }

        @ParameterizedTest(name = "group {0}")
        @ValueSource(ints = {0, 31})
        @DisplayName("rejects the reserved reset groups")
        void reservedGroups(int group) {
            var reader = reader(writer -> {
                longInfo(writer, 0);
                writer.write(1, 1);
                writer.write(1, 1);
                writer.write(group, 5);
            });
            assertThrows(MediaException.Malformed.class, () -> stream(AudioSpecificConfig.AOT_MAIN).readInfo(reader, null));
        }

        @ParameterizedTest(name = "object type {0}")
        @ValueSource(ints = {AudioSpecificConfig.AOT_LC, AudioSpecificConfig.AOT_SSR})
        @DisplayName("is rejected in object types without prediction")
        void otherTypes(int objectType) {
            var reader = reader(writer -> {
                longInfo(writer, 0);
                writer.write(1, 1);
            });
            assertThrows(MediaException.Malformed.class, () -> stream(objectType).readInfo(reader, null));
        }
    }

    @Nested
    @DisplayName("long term prediction data")
    class LongTermPrediction {
        @Test
        @DisplayName("of a common window precedes both channels in AAC LTP")
        void commonWindow() {
            var left = stream(AudioSpecificConfig.AOT_LTP);
            var right = stream(AudioSpecificConfig.AOT_LTP);
            var reader = reader(writer -> {
                longInfo(writer, 2);
                writer.write(1, 1);
                ltp(writer, 1234, 5, 2, 0b01);
                ltp(writer, 77, 2, 2, 0b10);
                for (var channel = 0; channel < 2; channel++) {
                    writer.write(90, 8);
                    zeroSection(writer, 2);
                    flags(writer, false);
                }
            });
            left.readInfo(reader, right);
            right.copyInfo(left);
            left.parse(reader, true, false);
            right.parse(reader, true, true);
            assertConsumed(reader);
            assertLtp(left, 1234, 1.067894f, true, false);
            assertLtp(right, 77, 0.813004f, false, true);
        }

        @Test
        @DisplayName("of a common window opens each channel's data in ER AAC LTP")
        void errorResilientCommonWindow() {
            var left = stream(AudioSpecificConfig.AOT_ER_LTP);
            var right = stream(AudioSpecificConfig.AOT_ER_LTP);
            var reader = reader(writer -> {
                longInfo(writer, 2);
                writer.write(1, 1);
                ltp(writer, 2047, 7, 2, 0b11);
                writer.write(90, 8);
                zeroSection(writer, 2);
                flags(writer, false);
                writer.write(0, 1);
                writer.write(91, 8);
                zeroSection(writer, 2);
                flags(writer, false);
            });
            left.readInfo(reader, right);
            right.copyInfo(left);
            left.parse(reader, true, false);
            right.parse(reader, true, true);
            assertConsumed(reader);
            assertLtp(left, 2047, 1.369533f, true, true);
            assertFalse(right.ltpPresent());
        }

        @Test
        @DisplayName("of a single channel stays in ics_info in ER AAC LTP")
        void errorResilientSingle() {
            var stream = stream(AudioSpecificConfig.AOT_ER_LTP);
            var reader = reader(writer -> {
                writer.write(90, 8);
                longInfo(writer, 1);
                writer.write(1, 1);
                ltp(writer, 3, 0, 1, 1);
                zeroSection(writer, 1);
                flags(writer, false);
            });
            stream.parse(reader, false, false);
            assertConsumed(reader);
            assertLtp(stream, 3, 0.570829f, true, false);
        }

        @Test
        @DisplayName("covers at most 40 bands")
        void bandLimit() {
            var stream = stream(AudioSpecificConfig.AOT_LTP);
            var reader = reader(writer -> {
                writer.write(90, 8);
                longInfo(writer, 45);
                writer.write(1, 1);
                writer.write(1, 1);
                writer.write(100, 11);
                writer.write(4, 3);
                for (var band = 0; band < ChannelStream.LTP_MAX_BANDS; band++) {
                    writer.write(1, 1);
                }
                zeroSection(writer, 30);
                zeroSection(writer, 15);
                flags(writer, false);
            });
            stream.parse(reader, false, false);
            assertConsumed(reader);
            assertTrue(stream.ltpUsed(ChannelStream.LTP_MAX_BANDS - 1));
        }

        static void assertLtp(ChannelStream stream, int lag, float coefficient, boolean band0, boolean band1) {
            assertTrue(stream.ltpPresent());
            assertEquals(lag, stream.ltpLag());
            assertEquals(coefficient, stream.ltpCoefficient());
            assertEquals(band0, stream.ltpUsed(0));
            assertEquals(band1, stream.ltpUsed(1));
        }
    }

    @Nested
    @DisplayName("TNS data")
    class Tns {
        @ParameterizedTest(name = "object type {0}")
        @ValueSource(ints = {AudioSpecificConfig.AOT_LC, AudioSpecificConfig.AOT_LTP, AudioSpecificConfig.AOT_ER_LTP})
        @DisplayName("follows its flag, or the gain control flag in ER AAC LTP")
        void order(int objectType) {
            var errorResilient = objectType == AudioSpecificConfig.AOT_ER_LTP;
            var reader = reader(writer -> {
                writer.write(90, 8);
                longInfo(writer, 1);
                writer.write(0, 1);
                zeroSection(writer, 1);
                writer.write(0, 1);
                writer.write(1, 1);
                if (!errorResilient) {
                    tns(writer);
                }
                writer.write(0, 1);
                if (errorResilient) {
                    tns(writer);
                }
            });
            stream(objectType).parse(reader, false, false);
            assertConsumed(reader);
        }

        @Test
        @DisplayName("allows order 20 filters in AAC Main only")
        void mainOrder() {
            Consumer<MsbBitWriter> body = writer -> {
                writer.write(90, 8);
                longInfo(writer, 1);
                writer.write(0, 1);
                zeroSection(writer, 1);
                writer.write(0, 1);
                writer.write(1, 1);
                writer.write(1, 2);
                writer.write(0, 1);
                writer.write(1, 6);
                writer.write(20, 5);
                writer.write(0, 1);
                writer.write(0, 1);
                for (var i = 0; i < 20; i++) {
                    writer.write(1, 3);
                }
                writer.write(0, 1);
            };
            var reader = reader(body);
            stream(AudioSpecificConfig.AOT_MAIN).parse(reader, false, false);
            assertConsumed(reader);
            assertThrows(MediaException.Malformed.class, () -> stream(AudioSpecificConfig.AOT_LTP).parse(reader(body), false, false));
        }

        @ParameterizedTest(name = "direction {0}")
        @ValueSource(ints = {0, 1})
        @DisplayName("runs analysis filters that invert the synthesis filters, as long term prediction needs")
        void analysisInvertsSynthesis(int direction) {
            var reader = reader(writer -> {
                writer.write(90, 8);
                longInfo(writer, 40);
                writer.write(0, 1);
                writer.write(Codebooks.ZERO_HCB, 4);
                writer.write(31, 5);
                writer.write(9, 5);
                writer.write(0, 1);
                writer.write(1, 1);
                writer.write(1, 2);
                writer.write(1, 1);
                writer.write(40, 6);
                writer.write(4, 5);
                writer.write(direction, 1);
                writer.write(0, 1);
                for (var code : new int[]{5, 12, 3, 9}) {
                    writer.write(code, 4);
                }
                writer.write(0, 1);
            });
            var stream = stream(AudioSpecificConfig.AOT_LTP);
            stream.parse(reader, false, false);
            assertConsumed(reader);
            var spectrum = stream.spectrum();
            var random = new Random(direction);
            var original = new float[spectrum.length];
            for (var i = 0; i < original.length; i++) {
                original[i] = (float) random.nextGaussian() * 1000;
            }
            System.arraycopy(original, 0, spectrum, 0, original.length);
            stream.applyTns();
            var changed = 0.0;
            for (var i = 0; i < original.length; i++) {
                changed = Math.max(changed, Math.abs(spectrum[i] - original[i]));
            }
            assertTrue(changed > 100, "the synthesis filter changes the spectrum");
            stream.applyTnsAnalysis(spectrum);
            for (var i = 0; i < original.length; i++) {
                assertEquals(original[i], spectrum[i], 0.05, "line " + i);
            }
        }
    }

    @Nested
    @DisplayName("SSR gain control data")
    class GainControl {
        @Test
        @DisplayName("reads levels and locations with the location widths of a long start window")
        void longStart() {
            var stream = stream(AudioSpecificConfig.AOT_SSR);
            var reader = reader(writer -> {
                writer.write(90, 8);
                writer.write(0, 1);
                writer.write(ChannelStream.LONG_START_SEQUENCE, 2);
                writer.write(0, 1);
                writer.write(0, 6);
                writer.write(0, 1);
                writer.write(0, 1);
                writer.write(0, 1);
                writer.write(1, 1);
                writer.write(2, 2);
                writer.write(2, 3);
                writer.write(9, 4);
                writer.write(3, 4);
                writer.write(0, 4);
                writer.write(13, 4);
                writer.write(1, 3);
                writer.write(15, 4);
                writer.write(3, 2);
                writer.write(0, 3);
                writer.write(0, 3);
            });
            stream.parse(reader, false, false);
            assertConsumed(reader);
            assertEquals(2, stream.gainChanges(1, 0));
            assertEquals(5, stream.gainLevel(1, 0, 0));
            assertEquals(24, stream.gainLocation(1, 0, 0));
            assertEquals(-4, stream.gainLevel(1, 0, 1));
            assertEquals(104, stream.gainLocation(1, 0, 1));
            assertEquals(1, stream.gainChanges(1, 1));
            assertEquals(11, stream.gainLevel(1, 1, 0));
            assertEquals(24, stream.gainLocation(1, 1, 0));
            assertEquals(0, stream.gainChanges(2, 0));
            assertEquals(0, stream.gainChanges(3, 0));
        }

        @ParameterizedTest(name = "case {0}")
        @ValueSource(ints = {0, 1})
        @DisplayName("rejects reserved and non-increasing locations")
        void invalidLocations(int invalid) {
            var reader = reader(writer -> {
                writer.write(90, 8);
                writer.write(0, 1);
                writer.write(invalid == 0 ? ChannelStream.LONG_STOP_SEQUENCE : ChannelStream.ONLY_LONG_SEQUENCE, 2);
                writer.write(0, 1);
                writer.write(0, 6);
                writer.write(0, 1);
                writer.write(0, 1);
                writer.write(0, 1);
                writer.write(1, 1);
                writer.write(1, 2);
                if (invalid == 0) {
                    writer.write(1, 3);
                    writer.write(4, 4);
                    writer.write(14, 4);
                } else {
                    writer.write(2, 3);
                    writer.write(4, 4);
                    writer.write(9, 5);
                    writer.write(4, 4);
                    writer.write(9, 5);
                }
            });
            assertThrows(MediaException.Malformed.class, () -> stream(AudioSpecificConfig.AOT_SSR).parse(reader, false, false));
        }

        @ParameterizedTest(name = "object type {0}")
        @ValueSource(ints = {AudioSpecificConfig.AOT_MAIN, AudioSpecificConfig.AOT_LC, AudioSpecificConfig.AOT_LTP})
        @DisplayName("is rejected outside AAC SSR")
        void otherTypes(int objectType) {
            var reader = reader(writer -> {
                writer.write(90, 8);
                longInfo(writer, 0);
                writer.write(0, 1);
                writer.write(0, 1);
                writer.write(0, 1);
                writer.write(1, 1);
                writer.write(0, 2);
            });
            assertThrows(MediaException.Malformed.class, () -> stream(objectType).parse(reader, false, false));
        }
    }
}
