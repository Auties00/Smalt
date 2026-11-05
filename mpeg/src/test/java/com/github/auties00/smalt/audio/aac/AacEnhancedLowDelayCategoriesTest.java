package com.github.auties00.smalt.audio.aac;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.aac.internal.AacBitReader;
import com.github.auties00.smalt.audio.aac.internal.AacKernels;
import com.github.auties00.smalt.audio.aac.internal.AudioSpecificConfig;
import com.github.auties00.smalt.audio.aac.internal.ChannelStream;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.track.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes AAC-ELD streams stored with {@code epConfig} 1, built from the ISO/IEC 14496-26 {@code er_eld} streams
 * ({@code epConfig} 0) by splitting every block into the error sensitivity category instances of ISO/IEC 14496-3
 * Table 4.148: per channel pair the common side information (category 0), then per channel the side information up
 * to {@code tns_data_present} (category 1), the TNS data (category 3) and the spectral data (category 4), the
 * extension payloads last. With low delay SBR ({@code er_eld_sbr}, one channel element) the SBR payload becomes the
 * instances of Table 4.149: category 9 up to and including {@code sbr_dtdf}, category 10 from {@code sbr_invf} on;
 * the test finds that boundary with a parser of its own. The suite has no AAC-ELD stream with {@code epConfig} 1; the
 * split streams must decode bit for bit like their sources.
 */
class AacEnhancedLowDelayCategoriesTest {
    /** Splits one AAC-ELD block of a configuration without RVLC and codeword reordering into its instances. */
    private static List<byte[]> split(byte[] frame, AudioSpecificConfig config, ChannelStream[] streams) {
        var bits = frame.length * 8L;
        var instances = new ArrayList<byte[]>();
        var cursor = 0L;
        var stream = 0;
        for (var element : elements(config.channelConfiguration())) {
            var commonWindow = element == 2;
            if (commonWindow) {
                var left = streams[stream];
                var right = streams[stream + 1];
                var reader = reader(frame, cursor);
                left.readInfo(reader, right);
                right.copyInfo(left);
                var mode = reader.read(2);
                if (mode == 1) {
                    reader.skip(left.maxBands());
                }
                instances.add(slice(frame, cursor, reader.bitPosition()));
                cursor = reader.bitPosition();
            }
            for (var channel = 0; channel < (commonWindow ? 2 : 1); channel++) {
                var current = streams[stream++];
                var intensity = commonWindow && channel == 1;
                var main = reader(frame, cursor);
                parse(current, main, empty(), empty(), commonWindow, intensity);
                var tnsStart = main.bitPosition();
                var tns = reader(frame, tnsStart);
                parse(current, reader(frame, cursor), tns, empty(), commonWindow, intensity);
                var spectralStart = tns.bitPosition();
                var spectral = reader(frame, spectralStart);
                current.parse(reader(frame, cursor), reader(frame, cursor), reader(frame, tnsStart), spectral,
                        commonWindow, intensity);
                instances.add(slice(frame, cursor, tnsStart));
                instances.add(slice(frame, tnsStart, spectralStart));
                instances.add(slice(frame, spectralStart, spectral.bitPosition()));
                cursor = spectral.bitPosition();
            }
        }
        assertTrue(cursor <= bits);
        var sbr = config.lowDelaySbr();
        if (sbr != null) {
            // one low delay SBR element: category 9 up to its sbr_dtdf, category 10 from sbr_invf to the end
            var control = controlEnd(frame, cursor, sbr.crc(), config.channelConfiguration() == 2,
                    config.frameLength() == 480 ? 15 : 16);
            assertTrue(control <= bits);
            instances.add(slice(frame, cursor, control));
            cursor = control;
        }
        instances.add(slice(frame, cursor, bits));
        return instances;
    }

    /**
     * Returns where the category 9 part of a {@code low_delay_sbr_data} ends (ISO/IEC 14496-3 Tables 4.63, 4.70,
     * 4.81, 4.149 and 4.178): the CRC, the header, the reserved bits, the coupling flag, the grids and the
     * {@code sbr_dtdf} of every channel.
     */
    private static long controlEnd(byte[] frame, long start, boolean crc, boolean pair, int timeSlots) {
        var reader = reader(frame, start);
        if (crc) {
            reader.read(10);
        }
        if (reader.readFlag()) {
            reader.read(14);
            var extra1 = reader.readFlag();
            var extra2 = reader.readFlag();
            if (extra1) {
                reader.read(5);
            }
            if (extra2) {
                reader.read(6);
            }
        }
        if (!pair) {
            if (reader.readFlag()) {
                reader.read(4);
            }
            dtdf(reader, grid(reader, timeSlots));
        } else {
            if (reader.readFlag()) {
                reader.read(8);
            }
            if (reader.readFlag()) {
                var envelopes = grid(reader, timeSlots);
                dtdf(reader, envelopes);
                dtdf(reader, envelopes);
            } else {
                var left = grid(reader, timeSlots);
                var right = grid(reader, timeSlots);
                dtdf(reader, left);
                dtdf(reader, right);
            }
        }
        return reader.bitPosition();
    }

    /** Reads {@code sbr_ld_grid} and returns the number of envelopes (Table 4.179 for {@code LD_TRAN}). */
    private static int grid(AacBitReader reader, int timeSlots) {
        if (reader.read(1) == 0) {
            var envelopes = 1 << reader.read(2);
            if (envelopes == 1) {
                reader.read(1);
            }
            reader.read(1);
            return envelopes;
        }
        var position = reader.read(4);
        var envelopes = position < 2 ? 2 : position < 10 ? 3 : position == 10 ? (timeSlots == 16 ? 3 : 2) : 2;
        reader.read(envelopes);
        return envelopes;
    }

    /** Skips {@code sbr_dtdf}. */
    private static void dtdf(AacBitReader reader, int envelopes) {
        reader.read(envelopes);
        reader.read(envelopes > 1 ? 2 : 1);
    }

    /** Runs a parse whose later sources are placeholders, keeping the positions the real sources reached. */
    private static void parse(ChannelStream stream, AacBitReader main, AacBitReader tns, AacBitReader spectral,
                              boolean commonWindow, boolean intensity) {
        try {
            stream.parse(main, main, tns, spectral, commonWindow, intensity);
        } catch (MediaException _) {
            // the placeholder sources hold no valid data
        }
    }

    private static int[] elements(int configuration) {
        return switch (configuration) {
            case 1 -> new int[]{1};
            case 2 -> new int[]{2};
            case 3 -> new int[]{1, 2};
            default -> throw new IllegalArgumentException("configuration " + configuration);
        };
    }

    private static AacBitReader reader(byte[] frame, long position) {
        var reader = new AacBitReader(frame);
        reader.seek(position);
        return reader;
    }

    private static AacBitReader empty() {
        return new AacBitReader(new byte[8192]);
    }

    private static byte[] slice(byte[] frame, long from, long to) {
        var out = new byte[(int) ((to - from + 7) / 8)];
        for (var i = 0L; i < to - from; i++) {
            var bit = from + i;
            if ((frame[(int) (bit >>> 3)] & (0x80 >>> (bit & 7))) != 0) {
                out[(int) (i >>> 3)] |= (byte) (0x80 >>> (i & 7));
            }
        }
        return out;
    }

    /** Returns the configuration with {@code epConfig} set to 1, found as the last 2-bit field that sets it. */
    private static byte[] separated(byte[] config) {
        var original = AudioSpecificConfig.parse(config);
        for (var position = config.length * 8 - 2; position >= 0; position--) {
            var copy = config.clone();
            copy[(position + 1) >>> 3] |= (byte) (0x80 >>> ((position + 1) & 7));
            if ((copy[position >>> 3] & (0x80 >>> (position & 7))) != 0 || Arrays.equals(copy, config)) {
                continue;
            }
            try {
                var parsed = AudioSpecificConfig.parse(copy);
                if (parsed.epConfig() == 1 && parsed.channelConfiguration() == original.channelConfiguration()
                        && parsed.frameLength() == original.frameLength()
                        && parsed.resilience() == original.resilience()) {
                    return copy;
                }
            } catch (MediaException _) {
                // not the epConfig field
            }
        }
        throw new IllegalArgumentException("no epConfig field");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"er_eld1000np_48_ep0", "er_eld1000np_22_ep0", "er_eld1010np_48_ep0", "er_eld1001np_48_ep0",
            "er_eld1100np_48_ep0", "er_eld2000np_48_ep0", "er_eld2100np_48_ep0", "er_eld1110np_48_ep0"})
    @DisplayName("decodes category instances split from epConfig 0 blocks bit for bit like the blocks")
    void matchesSingleAccessUnits(String name) {
        check(name, false);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"er_eld_sbr_e_1_32", "er_eld_sbr_e_2_48", "er_eld_sbr_gh_1_44", "er_eld_sbr_gh_2_32",
            "er_eld_sbr_i_1_48", "er_eld_sbr_i_2_44", "er_eld_sbr_s_1_48", "er_eld_sbr_s_2_32", "er_eld_sbr_twi_1_24",
            "er_eld_sbr_twi_1_48"})
    @DisplayName("decodes low delay SBR split into categories 9 and 10 bit for bit like the blocks")
    void matchesLowDelaySbr(String name) {
        check(name, true);
    }

    private static void check(String name, boolean lowDelaySbr) {
        var source = AacTestSupport.parseMp4(Fixtures.read(ErrorResilientVectors.fetch(name + ".mp4")));
        var config = AudioSpecificConfig.parse(source.config());
        assertEquals(AudioSpecificConfig.AOT_ER_ELD, config.objectType());
        var tools = config.resilience() & (AudioSpecificConfig.RVLC_SCALEFACTORS | AudioSpecificConfig.CODEWORD_REORDERING);
        assertEquals(0, tools, name + " uses RVLC or codeword reordering, which the splitter does not parse");
        assertEquals(lowDelaySbr, config.lowDelaySbr() != null, name + " low delay SBR");
        var expected = AacTestSupport.decodeAll(source).planes();
        var streams = new ChannelStream[3];
        for (var i = 0; i < streams.length; i++) {
            streams[i] = new ChannelStream(config.frameLength(), config.tableIndex(), config.objectType(),
                    config.resilience(), AacKernels.INSTANCE);
        }
        var separate = separated(source.config());
        var collected = new ArrayList<float[][]>();
        var track = new AacTestSupport.Stream(separate, source.frames(), source.sampleRate(), source.channels()).track();
        try (var decoder = new AacDecoder(track)) {
            assertTrue(decoder.separateCategoryInstances());
            for (var frame : source.frames()) {
                var units = new ArrayList<Packet>();
                for (var instance : split(frame, config, streams)) {
                    units.add(new Packet(1, instance, Packet.NO_TIMESTAMP, Packet.NO_TIMESTAMP, 0, true));
                }
                decoder.decode(units, buffer -> collected.add(AacTestSupport.copy(buffer)));
            }
        }
        for (var channel = 0; channel < expected.length; channel++) {
            var actual = new float[expected[channel].length];
            var position = 0;
            for (var block : collected) {
                System.arraycopy(block[channel], 0, actual, position, Math.min(block[channel].length,
                        actual.length - position));
                position += block[channel].length;
            }
            assertEquals(expected[channel].length, position, name + " length");
            Tolerance.exact().assertPcm(name + " channel " + channel, expected[channel], actual);
        }
    }
}
