package com.github.auties00.smalt.audio.aac.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.internal.bits.MsbBitWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@code AudioSpecificConfig} and {@code program_config_element} parsing, the sampling
 * tables, and the mapping of coded channels to WAVE order.
 */
class ConfigurationTest {
    static byte[] bits(int... fields) {
        var writer = new MsbBitWriter();
        for (var i = 0; i < fields.length; i += 2) {
            writer.write(fields[i], fields[i + 1]);
        }
        return writer.toByteArray();
    }

    /** A 5.1 program configuration: front SCE 0 and CPE 0, back CPE 1, LFE 0. */
    static byte[] surroundProgramConfig() {
        return bits(2, 5, 3, 4, 0, 4, 0, 3,
                0, 4, 1, 2, 3, 4, 2, 4, 0, 4, 1, 4, 1, 2, 0, 3, 0, 4, 0, 1, 0, 1, 0, 1,
                0, 1, 0, 4, 1, 1, 0, 4, 1, 1, 1, 4, 0, 4,
                0, 3, 0, 8);
    }

    @Nested
    @DisplayName("AudioSpecificConfig")
    class Parsing {
        @ParameterizedTest(name = "{0}")
        @CsvSource({
                "1190, 48000, 3, 2, 1024, false, false, 0",
                "1194, 48000, 3, 2, 960, false, false, 0",
                "1208, 44100, 4, 1, 1024, false, false, 0",
                "2b118800, 24000, 6, 2, 1024, true, false, 48000",
                "131056e598, 24000, 6, 2, 1024, true, false, 48000",
                "1388, 22050, 7, 1, 1024, false, false, 0",
                "1588, 8000, 11, 1, 1024, false, false, 0"
        })
        @DisplayName("parses Low Complexity and HE-AAC configurations to their core")
        void parse(String hex, int rate, int tableIndex, int configuration, int frameLength, boolean sbr, boolean ps,
                   int extensionRate) {
            var config = AudioSpecificConfig.parse(HexFormat.of().parseHex(hex));
            assertEquals(AudioSpecificConfig.AOT_LC, config.objectType());
            assertEquals(rate, config.sampleRate());
            assertEquals(tableIndex, config.tableIndex());
            assertEquals(configuration, config.channelConfiguration());
            assertEquals(frameLength, config.frameLength());
            assertEquals(sbr, config.sbr());
            assertEquals(ps, config.ps());
            assertEquals(extensionRate, config.extensionSampleRate());
        }

        @Test
        @DisplayName("parses explicit HE-AAC v2 (object type 29) and the PS sync extension")
        void parametricStereo() {
            var explicit = AudioSpecificConfig.parse(bits(29, 5, 6, 4, 1, 4, 3, 4, 2, 5, 0, 3));
            assertTrue(explicit.sbr());
            assertTrue(explicit.ps());
            assertEquals(24000, explicit.sampleRate());
            assertEquals(48000, explicit.extensionSampleRate());
            var compatible = AudioSpecificConfig.parse(bits(2, 5, 6, 4, 1, 4, 0, 3, 0x2B7, 11, 5, 5, 1, 1, 3, 4, 0x548, 11, 1, 1));
            assertTrue(compatible.sbr());
            assertTrue(compatible.ps());
            assertEquals(1, compatible.channelConfiguration());
            assertTrue(explicit.sbrSignaled() && explicit.psSignaled());
            assertTrue(compatible.sbrSignaled() && compatible.psSignaled());
        }

        @Test
        @DisplayName("tells a signaled absence of SBR or PS from implicit signaling")
        void signaledAbsence() {
            var implicit = AudioSpecificConfig.parse(bits(2, 5, 6, 4, 2, 4, 0, 3));
            assertFalse(implicit.sbr() || implicit.sbrSignaled() || implicit.psSignaled());
            var noSbr = AudioSpecificConfig.parse(bits(2, 5, 6, 4, 2, 4, 0, 3, 0x2B7, 11, 5, 5, 0, 1, 0, 3));
            assertFalse(noSbr.sbr());
            assertTrue(noSbr.sbrSignaled());
            assertFalse(noSbr.psSignaled());
            var noPs = AudioSpecificConfig.parse(bits(2, 5, 6, 4, 1, 4, 0, 3, 0x2B7, 11, 5, 5, 1, 1, 3, 4, 0x548, 11, 0, 1, 0, 3));
            assertTrue(noPs.sbr());
            assertFalse(noPs.ps());
            assertTrue(noPs.psSignaled());
            var explicitSbr = AudioSpecificConfig.parse(bits(5, 5, 6, 4, 1, 4, 3, 4, 2, 5, 0, 3));
            assertTrue(explicitSbr.sbrSignaled());
            assertFalse(explicitSbr.psSignaled(), "object type 5 leaves parametric stereo to the bitstream");
        }

        @Test
        @DisplayName("parses an explicit sampling frequency and maps it to the nearest tables")
        void explicitFrequency() {
            var standard = AudioSpecificConfig.parse(bits(2, 5, 15, 4, 44100, 24, 2, 4, 0, 3));
            assertEquals(44100, standard.sampleRate());
            assertEquals(4, standard.tableIndex());
            var odd = AudioSpecificConfig.parse(bits(2, 5, 15, 4, 50000, 24, 2, 4, 0, 3));
            assertEquals(50000, odd.sampleRate());
            assertEquals(3, odd.tableIndex());
        }

        @Test
        @DisplayName("parses a program configuration element")
        void programConfiguration() {
            var config = AudioSpecificConfig.parse(surroundProgramConfig());
            assertEquals(0, config.channelConfiguration());
            var program = config.programConfig();
            assertNotNull(program);
            assertEquals(6, program.channels());
            assertEquals(List.of(new ProgramConfig.Element(false, 0), new ProgramConfig.Element(true, 0)), program.front());
            assertEquals(List.of(new ProgramConfig.Element(true, 1)), program.back());
            assertEquals(List.of(0), program.lfe());
            var mapping = ChannelMapping.ofProgram(program, MediaLimits.DEFAULT);
            assertEquals(ChannelLayout.SURROUND_5_1, mapping.layout());
        }

        @ParameterizedTest(name = "object type {0}")
        @CsvSource({"1, 2", "3, 1", "4, 2"})
        @DisplayName("parses the AAC Main, SSR and LTP object types")
        void coreObjectTypes(int objectType, int channels) {
            var config = AudioSpecificConfig.parse(bits(objectType, 5, 3, 4, channels, 4, 0, 3));
            assertEquals(objectType, config.objectType());
            assertEquals(48000, config.sampleRate());
            assertEquals(channels, config.channelConfiguration());
            assertEquals(1024, config.frameLength());
            assertEquals(objectType == AudioSpecificConfig.AOT_LTP, config.longTermPrediction());
            assertFalse(config.errorResilient());
        }

        @Test
        @DisplayName("parses ER AAC LTP with its resilience flags and epConfig")
        void errorResilientLtp() {
            var config = AudioSpecificConfig.parse(HexFormat.of().parseHex("998900"));
            assertEquals(AudioSpecificConfig.AOT_ER_LTP, config.objectType());
            assertEquals(1, config.channelConfiguration());
            assertTrue(config.errorResilient());
            assertTrue(config.longTermPrediction());
            assertFalse(config.sbr());
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({
                "898900, 17, 48000, 1, 1024, 0, 0",
                "8991a0, 17, 48000, 2, 1024, 5, 0",
                "899144, 17, 48000, 2, 1024, 2, 1",
                "998904, 19, 48000, 1, 1024, 0, 1",
                "9989c0, 19, 48000, 1, 1024, 6, 0",
                "b98900, 23, 48000, 1, 512, 0, 0",
                "b98d20, 23, 48000, 1, 480, 1, 0",
                "b995c0, 23, 48000, 2, 480, 6, 0",
                "f8e62000, 39, 48000, 1, 512, 0, 0",
                "f8e63000, 39, 48000, 1, 480, 0, 0",
                "f8e65a00, 39, 48000, 2, 480, 5, 0"
        })
        @DisplayName("parses ER AAC LC, ER AAC LTP, AAC-LD and AAC-ELD with their resilience flags and epConfig")
        void errorResilientTypes(String hex, int objectType, int rate, int channels, int frameLength, int resilience,
                                 int epConfig) {
            // the conformance configurations of er_al10, er_al26, er_al23 (ep1), er_ad1000, er_ad1111, er_ad2160,
            // er_eld1000np, er_eld1100np and er_eld2150np, plus two ER AAC LTP variants
            var config = AudioSpecificConfig.parse(HexFormat.of().parseHex(hex));
            assertEquals(objectType, config.objectType());
            assertEquals(rate, config.sampleRate());
            assertEquals(channels, config.channelConfiguration());
            assertEquals(frameLength, config.frameLength());
            assertEquals(resilience, config.resilience());
            assertEquals(epConfig, config.epConfig());
            assertTrue(config.errorResilient());
            assertEquals(objectType == AudioSpecificConfig.AOT_ER_LD || objectType == AudioSpecificConfig.AOT_ER_ELD,
                    config.lowDelay());
            assertEquals(objectType != AudioSpecificConfig.AOT_ER_LC && objectType != AudioSpecificConfig.AOT_ER_ELD,
                    config.longTermPrediction());
            assertFalse(config.sbr());
            assertNull(config.lowDelaySbr());
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({"f8ec21b9209c00, 24000, 48000, true", "f8ec2126808200, 24000, 24000, false",
                "f8ec41af0000, 24000, 48000, true", "f8ee41af0000, 22050, 44100, true"})
        @DisplayName("parses the low delay SBR of AAC-ELD at dual rate and downsampled")
        void lowDelaySbr(String hex, int rate, int extensionRate, boolean dualRate) {
            // er_eld_sbr_e_1_48, er_eld_sbr_twi_1_24, er_eld_sbr_e_2_48 and er_eld_sbr_s_2_44
            var config = AudioSpecificConfig.parse(HexFormat.of().parseHex(hex));
            assertEquals(AudioSpecificConfig.AOT_ER_ELD, config.objectType());
            assertEquals(rate, config.sampleRate());
            assertEquals(512, config.frameLength());
            assertTrue(config.sbr());
            assertEquals(extensionRate, config.extensionSampleRate());
            assertNotNull(config.lowDelaySbr());
            assertEquals(dualRate, config.lowDelaySbr().dualRate());
            assertFalse(config.lowDelaySbr().crc());
            assertEquals(1, config.lowDelaySbr().headerOffsets().length);
        }

        @ParameterizedTest(name = "object type {0}")
        @ValueSource(ints = {17, 19, 23, 39})
        @DisplayName("parses SBR signaled around an error resilient core")
        void sbrAroundErrorResilientCore(int objectType) {
            // object type 5 at 48 kHz, mono, a 96 kHz extension, then the core's specific configuration and
            // epConfig 0: GASpecificConfig with the extension flag, or an ELDSpecificConfig without low delay SBR
            var core = objectType == AudioSpecificConfig.AOT_ER_ELD
                    ? new int[]{31, 5, 7, 6, 0, 1, 0, 3, 0, 1, 0, 4}
                    : new int[]{objectType, 5, 0, 1, 0, 1, 1, 1, 0, 3, 0, 1};
            var fields = IntStream.concat(IntStream.concat(IntStream.of(5, 5, 3, 4, 1, 4, 0, 4), Arrays.stream(core)),
                    IntStream.of(0, 2)).toArray();
            var config = AudioSpecificConfig.parse(bits(fields));
            assertEquals(objectType, config.objectType());
            assertEquals(48000, config.sampleRate());
            assertTrue(config.errorResilient());
            assertTrue(config.sbr());
            assertTrue(config.sbrSignaled());
            assertEquals(96000, config.extensionSampleRate());
            assertEquals(0, config.epConfig());
            assertNull(config.lowDelaySbr());
        }

        /** Builds an AAC-ELD configuration with low delay SBR headers, one extension and the given epConfig. */
        static byte[] eldWithHeaders(int channels, int headers, int epConfig) {
            var fields = new java.util.ArrayList<Integer>(java.util.List.of(31, 5, 7, 6, 6, 4, channels, 4, 0, 1, 0, 3,
                    1, 1, 1, 1, 0, 1));
            for (var i = 0; i < headers; i++) {
                // sbr_header: amp res, start, stop, crossover, reserved, then both extra header flags on
                fields.addAll(java.util.List.of(i, 14, 1, 1, 1, 1, i, 5, 2 * i, 6));
            }
            // one extension of type 1 with two bytes, the terminator, epConfig
            fields.addAll(java.util.List.of(1, 4, 2, 4, 0xABCD, 16, 0, 4, epConfig, 2));
            return bits(fields.stream().mapToInt(Integer::intValue).toArray());
        }

        @ParameterizedTest(name = "channel configuration {0}")
        @CsvSource({"1, 1", "2, 1", "3, 2", "4, 3", "5, 3", "6, 3", "7, 4"})
        @DisplayName("reads one low delay SBR header per SBR element, then the extensions and epConfig")
        void lowDelaySbrHeaders(int channels, int headers) {
            var config = AudioSpecificConfig.parse(eldWithHeaders(channels, headers, 0));
            assertEquals(channels, config.channelConfiguration());
            assertEquals(headers, config.lowDelaySbr().headerOffsets().length);
            for (var i = 0; i < headers; i++) {
                assertEquals(26 + 27 * i, config.lowDelaySbr().headerOffsets()[i]);
            }
            assertEquals(0, config.epConfig());
            // epConfig lands after the headers and the extension list, so a misread header shifts it
            var exception = assertThrows(MediaException.Unsupported.class,
                    () -> AudioSpecificConfig.parse(eldWithHeaders(channels, headers, 1)));
            assertEquals("aac eld epconfig 1", exception.format());
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({"99890c, aac er epconfig 3", "899148, aac er epconfig 2", "8b09ec0624480500715400101a012238001c1a, aac er epconfig 3",
                "f8e62004, aac eld epconfig 1"})
        @DisplayName("rejects the error protection tool and AAC-ELD with separate category instances as unsupported")
        void errorProtection(String hex, String format) {
            // the third configuration is that of er_al18_24_ep3_crc02, with its ErrorProtectionSpecificConfig
            var exception = assertThrows(MediaException.Unsupported.class,
                    () -> AudioSpecificConfig.parse(HexFormat.of().parseHex(hex)));
            assertEquals(format, exception.format());
        }

        @Test
        @DisplayName("rejects truncated AAC-ELD extensions and reserved low delay channel configurations as malformed")
        void malformedLowDelay() {
            assertThrows(MediaException.Malformed.class, () -> AudioSpecificConfig.parse(bits(31, 5, 7, 6, 3, 4, 1, 4, 0, 1,
                    0, 3, 0, 1, 1, 4, 15, 4, 255, 8, 0xFFFF, 16)));
            assertThrows(MediaException.Malformed.class, () -> AudioSpecificConfig.parse(bits(31, 5, 7, 6, 3, 4, 0, 4, 0, 1,
                    0, 3, 0, 1, 0, 4, 0, 2)));
            assertThrows(MediaException.Malformed.class, () -> AudioSpecificConfig.parse(bits(23, 5, 3, 4, 11, 4, 0, 1, 0, 1,
                    1, 1, 0, 3, 0, 1, 0, 2)));
        }

        @Test
        @DisplayName("rejects 960-sample SSR frames and the reserved channel configurations of ER AAC LTP as malformed")
        void invalidCombinations() {
            assertThrows(MediaException.Malformed.class, () -> AudioSpecificConfig.parse(bits(3, 5, 3, 4, 1, 4, 4, 3)));
            // the 5.1 program configuration of surroundProgramConfig behind an ER AAC LTP header
            var program = bits(19, 5, 3, 4, 0, 4, 1, 3,
                    0, 4, 1, 2, 3, 4, 2, 4, 0, 4, 1, 4, 1, 2, 0, 3, 0, 4, 0, 1, 0, 1, 0, 1,
                    0, 1, 0, 4, 1, 1, 0, 4, 1, 1, 1, 4, 0, 4,
                    0, 3, 0, 8, 0, 3, 0, 1, 0, 2);
            var exception = assertThrows(MediaException.Malformed.class, () -> AudioSpecificConfig.parse(program));
            assertTrue(exception.getMessage().contains("channel configuration 0"), exception.getMessage());
            // channel configurations 11, 12 and 14 have no error resilient element list
            for (var channels : new int[]{11, 12, 14}) {
                var config = bits(19, 5, 3, 4, channels, 4, 1, 3, 0, 3, 0, 1, 0, 2);
                var reserved = assertThrows(MediaException.Malformed.class, () -> AudioSpecificConfig.parse(config));
                assertTrue(reserved.getMessage().contains("channel configuration " + channels), reserved.getMessage());
            }
        }

        @ParameterizedTest(name = "object type {0}")
        @CsvSource({"6, aac scalable", "20, aac scalable", "22, aac er bsac", "42, usac",
                "8, mpeg-4 audio object type 8"})
        @DisplayName("rejects other object types as unsupported, naming them")
        void unsupported(int objectType, String format) {
            var config = objectType < 31 ? bits(objectType, 5, 3, 4, 2, 4, 0, 3) : bits(31, 5, objectType - 32, 6, 3, 4, 2, 4, 0, 3);
            var exception = assertThrows(MediaException.Unsupported.class, () -> AudioSpecificConfig.parse(config));
            assertEquals(format, exception.format());
        }

        @Test
        @DisplayName("rejects reserved fields and truncation as malformed")
        void invalid() {
            assertThrows(MediaException.Malformed.class, () -> AudioSpecificConfig.parse(new byte[0]));
            assertThrows(MediaException.Malformed.class, () -> AudioSpecificConfig.parse(bits(0, 5, 3, 4, 2, 4, 0, 3)));
            assertThrows(MediaException.Malformed.class, () -> AudioSpecificConfig.parse(bits(2, 5, 13, 4, 2, 4, 0, 3)));
            assertThrows(MediaException.Malformed.class, () -> AudioSpecificConfig.parse(bits(2, 5, 15, 4, 0, 24, 2, 4, 0, 3)));
            assertThrows(MediaException.Malformed.class, () -> AudioSpecificConfig.parse(bits(2, 5, 3, 4, 9, 4, 0, 3)));
            assertThrows(MediaException.Malformed.class, () -> AudioSpecificConfig.parse(bits(5, 5, 6, 4, 2, 4, 3, 4, 5, 5)));
            var truncated = surroundProgramConfig();
            assertThrows(MediaException.Malformed.class,
                    () -> AudioSpecificConfig.parse(java.util.Arrays.copyOf(truncated, truncated.length - 3)));
        }

        @Test
        @DisplayName("maps the 22.2 configuration onto 24 channels in the order of the 22.2 layout")
        void twentyTwoTwo() {
            var config = AudioSpecificConfig.parse(bits(2, 5, 3, 4, 13, 4, 0, 3));
            assertEquals(13, config.channelConfiguration());
            var layout = ChannelMapping.ofConfiguration(13, MediaLimits.DEFAULT).layout();
            assertEquals(List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.FRONT_CENTER,
                    Speaker.LOW_FREQUENCY, Speaker.BACK_LEFT, Speaker.BACK_RIGHT, Speaker.FRONT_LEFT_OF_CENTER,
                    Speaker.FRONT_RIGHT_OF_CENTER, Speaker.BACK_CENTER, Speaker.SIDE_LEFT, Speaker.SIDE_RIGHT,
                    Speaker.TOP_CENTER, Speaker.TOP_FRONT_LEFT, Speaker.TOP_FRONT_CENTER, Speaker.TOP_FRONT_RIGHT,
                    Speaker.TOP_BACK_LEFT, Speaker.TOP_BACK_CENTER, Speaker.TOP_BACK_RIGHT, Speaker.LOW_FREQUENCY_2,
                    Speaker.TOP_SIDE_LEFT, Speaker.TOP_SIDE_RIGHT, Speaker.BOTTOM_FRONT_CENTER,
                    Speaker.BOTTOM_FRONT_LEFT, Speaker.BOTTOM_FRONT_RIGHT), layout.speakers());
        }

        @Test
        @DisplayName("accepts a configuration 0 without a program configuration, leaving the layout to the bitstream")
        void deferredLayout() {
            var config = AudioSpecificConfig.parse(bits(2, 5, 3, 4, 0, 4, 0, 3));
            assertEquals(0, config.channelConfiguration());
            assertNull(config.programConfig());
        }

        @ParameterizedTest(name = "{0} Hz")
        @ValueSource(ints = {96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350})
        @DisplayName("writes two-byte Low Complexity configurations that parse back")
        void write(int rate) {
            var bytes = AudioSpecificConfig.lowComplexity(rate, 2);
            assertEquals(2, bytes.length);
            var config = AudioSpecificConfig.parse(bytes);
            assertEquals(rate, config.sampleRate());
            assertEquals(2, config.channelConfiguration());
        }
    }

    @Nested
    @DisplayName("sampling tables")
    class Tables {
        @Test
        @DisplayName("have the specified band counts for 1024- and 960-sample frames")
        void bandCounts() {
            int[] long1024 = {41, 41, 47, 49, 49, 51, 47, 47, 43, 43, 43, 40, 40};
            int[] long960 = {40, 40, 46, 49, 49, 49, 46, 46, 42, 42, 42, 40, 40};
            int[] short128 = {12, 12, 12, 14, 14, 14, 15, 15, 15, 15, 15, 15, 15};
            for (var index = 0; index < long1024.length; index++) {
                assertEquals(long1024[index], SamplingTables.longOffsets(index, 1024).length - 1, "long " + index);
                assertEquals(long960[index], SamplingTables.longOffsets(index, 960).length - 1, "960 " + index);
                assertEquals(short128[index], SamplingTables.shortOffsets(index, 1024).length - 1, "short " + index);
                var offsets = SamplingTables.longOffsets(index, 1024);
                for (var band = 0; band + 1 < offsets.length; band++) {
                    assertEquals(0, (offsets[band + 1] - offsets[band]) % 4);
                }
            }
        }

        @Test
        @DisplayName("map explicit frequencies to the nearest standard tables")
        void nearest() {
            assertEquals(0, SamplingTables.tableIndexFor(192000));
            assertEquals(3, SamplingTables.tableIndexFor(46009));
            assertEquals(4, SamplingTables.tableIndexFor(46008));
            assertEquals(11, SamplingTables.tableIndexFor(1));
        }
    }

    @Nested
    @DisplayName("channel mapping")
    class Mapping {
        @ParameterizedTest(name = "configuration {0}")
        @CsvSource({
                "1, FRONT_CENTER",
                "2, FRONT_LEFT FRONT_RIGHT",
                "3, FRONT_LEFT FRONT_RIGHT FRONT_CENTER",
                "4, FRONT_LEFT FRONT_RIGHT FRONT_CENTER BACK_CENTER",
                "5, FRONT_LEFT FRONT_RIGHT FRONT_CENTER BACK_LEFT BACK_RIGHT",
                "6, FRONT_LEFT FRONT_RIGHT FRONT_CENTER LOW_FREQUENCY BACK_LEFT BACK_RIGHT",
                "7, FRONT_LEFT FRONT_RIGHT FRONT_CENTER LOW_FREQUENCY BACK_LEFT BACK_RIGHT FRONT_LEFT_OF_CENTER FRONT_RIGHT_OF_CENTER",
                "11, FRONT_LEFT FRONT_RIGHT FRONT_CENTER LOW_FREQUENCY BACK_LEFT BACK_RIGHT BACK_CENTER",
                "12, FRONT_LEFT FRONT_RIGHT FRONT_CENTER LOW_FREQUENCY BACK_LEFT BACK_RIGHT SIDE_LEFT SIDE_RIGHT",
                "14, FRONT_LEFT FRONT_RIGHT FRONT_CENTER LOW_FREQUENCY BACK_LEFT BACK_RIGHT TOP_FRONT_LEFT TOP_FRONT_RIGHT"
        })
        @DisplayName("orders every channel configuration in WAVE order")
        void configurations(int configuration, String speakers) {
            var mapping = ChannelMapping.ofConfiguration(configuration, MediaLimits.DEFAULT);
            var expected = java.util.Arrays.stream(speakers.split(" ")).map(Speaker::valueOf).toList();
            assertEquals(expected, mapping.layout().speakers());
        }

        @Test
        @DisplayName("routes the coded 5.1 channels (C, L, R, Ls, Rs, LFE) to WAVE positions")
        void surroundRouting() {
            var mapping = ChannelMapping.ofConfiguration(6, MediaLimits.DEFAULT);
            assertArrayEquals(new int[]{2, 0, 1, 4, 5, 3}, new int[]{mapping.outputIndex(0), mapping.outputIndex(1),
                    mapping.outputIndex(2), mapping.outputIndex(3), mapping.outputIndex(4), mapping.outputIndex(5)});
            assertEquals(0, mapping.channelOf(ElementId.SCE, 7, 0));
            assertEquals(1, mapping.channelOf(ElementId.CPE, 9, 0));
            assertEquals(3, mapping.channelOf(ElementId.CPE, 0, 1));
            assertEquals(5, mapping.channelOf(ElementId.LFE, 0, 0));
            assertEquals(-1, mapping.channelOf(ElementId.CPE, 0, 2));
        }

        @Test
        @DisplayName("positions program channels by list and count")
        void programs() {
            var dualMono = new ProgramConfig(0, 1, 3, List.of(new ProgramConfig.Element(false, 0), new ProgramConfig.Element(false, 1)),
                    List.of(), List.of(), List.of(), List.of(), 2);
            assertEquals(ChannelLayout.STEREO, ChannelMapping.ofProgram(dualMono, MediaLimits.DEFAULT).layout());
            var wide = new ProgramConfig(0, 1, 3, List.of(new ProgramConfig.Element(false, 0), new ProgramConfig.Element(true, 0),
                    new ProgramConfig.Element(true, 1)), List.of(new ProgramConfig.Element(true, 2)),
                    List.of(new ProgramConfig.Element(true, 3), new ProgramConfig.Element(false, 1)), List.of(0), List.of(), 11);
            assertEquals(List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.FRONT_CENTER, Speaker.LOW_FREQUENCY,
                    Speaker.BACK_LEFT, Speaker.BACK_RIGHT, Speaker.FRONT_LEFT_OF_CENTER, Speaker.FRONT_RIGHT_OF_CENTER,
                    Speaker.BACK_CENTER, Speaker.SIDE_LEFT, Speaker.SIDE_RIGHT),
                    ChannelMapping.ofProgram(wide, MediaLimits.DEFAULT).layout().speakers());
            assertThrows(MediaException.LimitExceeded.class,
                    () -> ChannelMapping.ofProgram(wide, MediaLimits.builder().maxDimension(1).maxPixels(1).maxFrames(1)
                            .maxChannels(8).maxBytes(1).build()));
        }

        @Test
        @DisplayName("infers layouts from element sequences and checks sequences against a mapping")
        void inference() {
            assertEquals(ChannelLayout.MONO, ChannelMapping.infer(new int[]{ElementId.SCE}, MediaLimits.DEFAULT).layout());
            assertEquals(ChannelLayout.SURROUND_7_1, ChannelMapping.infer(new int[]{ElementId.SCE, ElementId.CPE,
                    ElementId.CPE, ElementId.CPE, ElementId.LFE}, MediaLimits.DEFAULT).layout());
            var odd = ChannelMapping.infer(new int[]{ElementId.CPE, ElementId.CPE}, MediaLimits.DEFAULT);
            assertEquals(List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.UNKNOWN, Speaker.UNKNOWN), odd.layout().speakers());
            var stereo = ChannelMapping.ofConfiguration(2, MediaLimits.DEFAULT);
            assertTrue(stereo.matches(new int[]{ElementId.CPE}, new int[]{5}, 1));
            assertFalse(stereo.matches(new int[]{ElementId.SCE}, new int[]{0}, 1));
            assertFalse(stereo.matches(new int[]{ElementId.CPE, ElementId.CPE}, new int[]{0, 1}, 2));
        }
    }
}
