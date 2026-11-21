package com.github.auties00.smalt.audio.aac;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.audio.aac.internal.AudioSpecificConfig;
import com.github.auties00.smalt.audio.aac.internal.ChannelMapping;
import com.github.auties00.smalt.audio.aac.internal.ElementId;
import com.github.auties00.smalt.audio.aac.internal.EncoderLayout;
import com.github.auties00.smalt.audio.aac.internal.ProgramConfig;
import com.github.auties00.smalt.audio.aac.internal.ProgramConfig.Element;
import com.github.auties00.smalt.audio.aac.internal.ProgramConfig.Height;
import com.github.auties00.smalt.container.aac.AdifDemuxer;
import com.github.auties00.smalt.container.aac.AdifMuxer;
import com.github.auties00.smalt.container.adts.AdtsDemuxer;
import com.github.auties00.smalt.internal.bits.MsbBitWriter;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static com.github.auties00.smalt.audio.Speaker.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the height extension of the program config element. The configurations below are the ones the libfdk-aac
 * decoder binary placed on the same loudspeakers, as a black box (see tools/testdata/aac-height-oracle.py, which
 * prints them), so the encoder writing them bit for bit means that decoder reads the encoder's layouts. ffmpeg 7.1
 * ignores the extension, so it is compared by the tone each channel carries and not by its channel names. The
 * reference streams that carry the extension are in {@link AacHeightConformanceTest}.
 */
class AacHeightExtensionTest {
    private static final int RATE = 48000;

    /** A layout and its configuration, with the loudspeakers in coding order. */
    record Fixture(String name, String config, List<Speaker> coded) {
        ChannelLayout layout() {
            return new ChannelLayout(coded);
        }

        byte[] bytes() {
            return HexFormat.of().parseHex(config);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Fixture> fixtures() {
        return Stream.of(
                new Fixture("5.1 side + top front pair", "118004cc41000108c80003ac042f", List.of(FRONT_CENTER, FRONT_LEFT,
                        FRONT_RIGHT, TOP_FRONT_LEFT, TOP_FRONT_RIGHT, SIDE_LEFT, SIDE_RIGHT, LOW_FREQUENCY)),
                new Fixture("5.1 back + top side pair", "118004c845000108c80003ac042f", List.of(FRONT_CENTER, FRONT_LEFT,
                        FRONT_RIGHT, TOP_SIDE_LEFT, TOP_SIDE_RIGHT, BACK_LEFT, BACK_RIGHT, LOW_FREQUENCY)),
                new Fixture("5.1 back + bottom front pair", "118004cc05000108c80003ac080b", List.of(FRONT_CENTER, FRONT_LEFT,
                        FRONT_RIGHT, BOTTOM_FRONT_LEFT, BOTTOM_FRONT_RIGHT, BACK_LEFT, BACK_RIGHT, LOW_FREQUENCY)),
                new Fixture("stereo + top back pair", "118004c40400211003ac1043", List.of(FRONT_LEFT, FRONT_RIGHT,
                        TOP_BACK_LEFT, TOP_BACK_RIGHT)),
                new Fixture("3.0 + top front center", "118004cc000001008003ac042f", List.of(FRONT_CENTER, FRONT_LEFT,
                        FRONT_RIGHT, TOP_FRONT_CENTER)),
                new Fixture("3.0 + top back center", "118004c8040001008003ac042f", List.of(FRONT_CENTER, FRONT_LEFT,
                        FRONT_RIGHT, TOP_BACK_CENTER)),
                new Fixture("3.0 + top center", "118004c8400001008003ac042f", List.of(FRONT_CENTER, FRONT_LEFT,
                        FRONT_RIGHT, TOP_CENTER)),
                new Fixture("stereo + bottom front center", "118004c80000200003ac20d3", List.of(FRONT_LEFT, FRONT_RIGHT,
                        BOTTOM_FRONT_CENTER)),
                new Fixture("7.1.4", "118004cc49000108ca740004ac0410bd", List.of(FRONT_CENTER, FRONT_LEFT, FRONT_RIGHT,
                        TOP_FRONT_LEFT, TOP_FRONT_RIGHT, SIDE_LEFT, SIDE_RIGHT, BACK_LEFT, BACK_RIGHT, TOP_BACK_LEFT,
                        TOP_BACK_RIGHT, LOW_FREQUENCY)));
    }

    /** Every loudspeaker a program config element can position, 23 channels. */
    private static final ChannelLayout EVERY_POSITION = new ChannelLayout(List.of(FRONT_CENTER, FRONT_LEFT_OF_CENTER,
            FRONT_RIGHT_OF_CENTER, FRONT_LEFT, FRONT_RIGHT, TOP_FRONT_CENTER, TOP_FRONT_LEFT, TOP_FRONT_RIGHT,
            BOTTOM_FRONT_CENTER, BOTTOM_FRONT_LEFT, BOTTOM_FRONT_RIGHT, SIDE_LEFT, SIDE_RIGHT, TOP_SIDE_LEFT,
            TOP_SIDE_RIGHT, TOP_CENTER, BACK_LEFT, BACK_RIGHT, BACK_CENTER, TOP_BACK_LEFT, TOP_BACK_RIGHT,
            TOP_BACK_CENTER, LOW_FREQUENCY));

    /** The 5.1 side fixture up to the comment, whose elements are a center, two front pairs, a side pair and an LFE. */
    private static final String HEAD = "118004cc41000108c800";

    private static List<Speaker> codedSpeakers(byte[] config) {
        return AacEncoderLayoutsTest.codingOrder(config);
    }

    private static AacEncoderOptions options(ChannelLayout layout) {
        return AacEncoderOptions.of(RATE, layout, 64000 * layout.channels());
    }

    @Nested
    @DisplayName("reading")
    class Reading {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.audio.aac.AacHeightExtensionTest#fixtures")
        @DisplayName("places every coded channel on the loudspeaker of its height layer")
        void fixture(Fixture fixture) {
            assertEquals(fixture.coded(), codedSpeakers(fixture.bytes()));
        }

        @Test
        @DisplayName("gives each element the height of its field, in front, side, back order")
        void elementHeights() {
            var program = AudioSpecificConfig.parse(HexFormat.of().parseHex("118004cc49000108ca740004ac0410bd"))
                    .programConfig();
            assertEquals(List.of(new Element(false, 0), new Element(true, 0), new Element(true, 1, Height.TOP)),
                    program.front());
            assertEquals(List.of(new Element(true, 2)), program.side());
            assertEquals(List.of(new Element(true, 3), new Element(true, 4, Height.TOP)), program.back());
            assertEquals(12, program.channels());
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({
                "no comment, 00",
                "plain text, 0568656c6c6f",
                "only a sync word, 01ac",
                "cut before its CRC, 02ac04",
                "another sync word, 03ad043a",
                "CRC initial value 0, 03ac04f8",
                "CRC without the sync word, 03ac04ef",
                "another CRC polynomial, 03ac04fe",
                "a flipped CRC bit, 03ac042e",
                "a reserved height with a valid CRC, 03ac0c17",
        })
        @DisplayName("keeps every element at normal height unless the comment holds a valid extension")
        void plainComments(String name, String comment) {
            assertEquals(List.of(FRONT_CENTER, FRONT_LEFT_OF_CENTER, FRONT_RIGHT_OF_CENTER, FRONT_LEFT, FRONT_RIGHT,
                    SIDE_LEFT, SIDE_RIGHT, LOW_FREQUENCY), codedSpeakers(HexFormat.of().parseHex(HEAD + comment)));
        }

        @Test
        @DisplayName("reads an extension followed by more comment bytes")
        void trailingBytes() {
            assertEquals(List.of(FRONT_CENTER, FRONT_LEFT, FRONT_RIGHT, TOP_FRONT_LEFT, TOP_FRONT_RIGHT, SIDE_LEFT,
                    SIDE_RIGHT, LOW_FREQUENCY), codedSpeakers(HexFormat.of().parseHex(HEAD + "05ac042f7878")));
        }

        @Test
        @DisplayName("takes the heights of each element, even with a top element ahead of a normal one")
        void anyOrder() {
            assertEquals(List.of(FRONT_CENTER, TOP_FRONT_LEFT, TOP_FRONT_RIGHT, FRONT_LEFT, FRONT_RIGHT, SIDE_LEFT,
                    SIDE_RIGHT, LOW_FREQUENCY), codedSpeakers(HexFormat.of().parseHex(HEAD + "03ac1043")));
        }

        @Test
        @DisplayName("feeds positions a layer does not have to unknown loudspeakers, after the positioned channels")
        void surplus() {
            // Three top front pairs, a bottom side pair and a bottom back pair.
            var program = new ProgramConfig(0, 1, 3,
                    List.of(new Element(true, 0, Height.TOP), new Element(true, 1, Height.TOP), new Element(true, 2, Height.TOP)),
                    List.of(new Element(true, 3, Height.BOTTOM)), List.of(new Element(true, 4, Height.BOTTOM)), List.of(),
                    List.of(), 10);
            var speakers = ChannelMapping.ofProgram(program, MediaLimits.DEFAULT).layout().speakers();
            assertEquals(List.of(TOP_FRONT_LEFT, TOP_FRONT_RIGHT), speakers.subList(0, 2));
            assertEquals(Collections.nCopies(8, UNKNOWN), speakers.subList(2, 10));
        }

        @Test
        @DisplayName("rejects a comment longer than the configuration")
        void truncated() {
            assertThrows(MediaException.Malformed.class,
                    () -> AudioSpecificConfig.parse(HexFormat.of().parseHex(HEAD + "05ac042f")));
        }

        @Test
        @DisplayName("reads random comments as comments or heights, always with the channels of the element lists")
        void randomComments() {
            var random = new Random(0x48454947L);
            var head = HexFormat.of().parseHex(HEAD);
            for (var round = 0; round < 20_000; round++) {
                var length = random.nextInt(8);
                var comment = new byte[length];
                random.nextBytes(comment);
                if (length > 0 && random.nextBoolean()) {
                    comment[0] = (byte) 0xAC;
                }
                var config = Arrays.copyOf(head, head.length + 1 + length);
                config[head.length] = (byte) length;
                System.arraycopy(comment, 0, config, head.length + 1, length);
                var mapping = ChannelMapping.ofProgram(AudioSpecificConfig.parse(config).programConfig(), MediaLimits.DEFAULT);
                assertEquals(8, mapping.channels());
            }
        }

        @Test
        @DisplayName("takes the extension of a program config element carried in a raw data block")
        void inBand() {
            var block = blockWithExtension();
            var decoded = AacTestSupport.decodeAll(new AacDecoder(AacEncoderLayoutsTest.track(HexFormat.of().parseHex(HEAD + "00"), RATE)),
                    List.of(block, block));
            assertEquals(List.of(FRONT_LEFT, FRONT_RIGHT, FRONT_CENTER, LOW_FREQUENCY, TOP_FRONT_LEFT, TOP_FRONT_RIGHT,
                    SIDE_LEFT, SIDE_RIGHT).stream().sorted().toList(), decoded.layout().speakers());
        }

        @Test
        @DisplayName("keeps the extension of an in-band element in the configuration an ADTS demuxer builds")
        void adts() throws IOException {
            var block = blockWithExtension();
            var data = AacTestSupport.writeAdts(HexFormat.of().parseHex(HEAD + "00"), List.of(block, block));
            try (var demuxer = new AdtsDemuxer(MediaSource.of(data))) {
                assertEquals(List.of(FRONT_CENTER, FRONT_LEFT, FRONT_RIGHT, TOP_FRONT_LEFT, TOP_FRONT_RIGHT, SIDE_LEFT,
                        SIDE_RIGHT, LOW_FREQUENCY), codedSpeakers(demuxer.tracks().getFirst().codecConfig().orElseThrow()));
            }
        }

        @Test
        @DisplayName("reads an extension of a program with no front, side or back element")
        void noElements() {
            var program = AudioSpecificConfig.parse(HexFormat.of().parseHex("118004c001000002acbe")).programConfig();
            assertEquals(1, program.channels());
            assertEquals(List.of(LOW_FREQUENCY), ChannelMapping.ofProgram(program, MediaLimits.DEFAULT).layout().speakers());
        }

        @Test
        @DisplayName("lets a top side pair leave the side speakers to a back pair, and ends each top list with its center")
        void layers() {
            var program = new ProgramConfig(0, 1, 3, List.of(new Element(true, 0)),
                    List.of(new Element(true, 1, Height.TOP), new Element(false, 0, Height.TOP)),
                    List.of(new Element(true, 2), new Element(true, 3), new Element(true, 4, Height.TOP),
                            new Element(true, 5, Height.TOP), new Element(false, 1, Height.TOP)),
                    List.of(), List.of(), 14);
            assertEquals(List.of(FRONT_LEFT, FRONT_RIGHT, BACK_LEFT, BACK_RIGHT, SIDE_LEFT, SIDE_RIGHT, TOP_CENTER,
                            TOP_BACK_LEFT, TOP_BACK_CENTER, TOP_BACK_RIGHT, TOP_SIDE_LEFT, TOP_SIDE_RIGHT, UNKNOWN, UNKNOWN),
                    ChannelMapping.ofProgram(program, MediaLimits.DEFAULT).layout().speakers());
        }

        // The configuration of HEAD has no extension; this block's element places the third front element on top.
        private static byte[] blockWithExtension() {
            var writer = new MsbBitWriter();
            writer.write(ElementId.PCE, 3);
            new ProgramConfig(0, 1, 3, List.of(new Element(false, 0), new Element(true, 0), new Element(true, 1, Height.TOP)),
                    List.of(new Element(true, 2)), List.of(), List.of(0), List.of(), 8).write(writer);
            silentSingle(writer, ElementId.SCE, 0);
            silentPair(writer, 0);
            silentPair(writer, 1);
            silentPair(writer, 2);
            silentSingle(writer, ElementId.LFE, 0);
            writer.write(ElementId.END, 3);
            writer.alignWithZeros();
            return writer.toByteArray();
        }

        private static void silentSingle(MsbBitWriter writer, int id, int tag) {
            writer.write(id, 3);
            writer.write(tag, 4);
            silentStream(writer);
        }

        private static void silentPair(MsbBitWriter writer, int tag) {
            writer.write(ElementId.CPE, 3);
            writer.write(tag, 4);
            writer.write(0, 1);
            silentStream(writer);
            silentStream(writer);
        }

        // A long window with no scalefactor band: global gain, then the zero ics_info, pulse, TNS and gain control flags.
        private static void silentStream(MsbBitWriter writer) {
            writer.write(100, 8);
            writer.write(0, 11);
            writer.write(0, 3);
        }
    }

    @Nested
    @DisplayName("writing")
    class Writing {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.audio.aac.AacHeightExtensionTest#fixtures")
        @DisplayName("writes the configuration of the layout bit for bit, in any input order")
        void bits(Fixture fixture) {
            assertEquals(fixture.config(), HexFormat.of().formatHex(new AacEncoder(options(fixture.layout())).codecConfig().orElseThrow()));
            var reversed = new ArrayList<>(fixture.coded());
            Collections.reverse(reversed);
            assertEquals(fixture.config(), HexFormat.of().formatHex(
                    new AacEncoder(options(new ChannelLayout(reversed))).codecConfig().orElseThrow()));
        }

        @Test
        @DisplayName("keeps channel configuration 14 for 5.1 with a top front pair, and an empty comment without heights")
        void unchanged() {
            var top = options(new ChannelLayout(List.of(FRONT_LEFT, FRONT_RIGHT, FRONT_CENTER, LOW_FREQUENCY, BACK_LEFT,
                    BACK_RIGHT, TOP_FRONT_LEFT, TOP_FRONT_RIGHT)));
            assertEquals(14, top.channelConfiguration());
            var flat = new AacEncoder(options(ChannelLayout.defaultFor(4))).codecConfig().orElseThrow();
            assertEquals(0, flat[flat.length - 1]);
            assertEquals(9, flat.length);
        }

        @Test
        @DisplayName("refuses a layout with half of a height pair, or a loudspeaker nothing positions")
        void refused() {
            assertTrue(EncoderLayout.of(new ChannelLayout(List.of(FRONT_LEFT, FRONT_RIGHT, TOP_FRONT_LEFT)), RATE).isEmpty());
            assertTrue(EncoderLayout.of(new ChannelLayout(List.of(FRONT_LEFT, FRONT_RIGHT, BOTTOM_FRONT_RIGHT)), RATE).isEmpty());
            assertTrue(EncoderLayout.of(new ChannelLayout(List.of(FRONT_LEFT, FRONT_RIGHT, WIDE_LEFT, WIDE_RIGHT)), RATE).isEmpty());
        }

        @Test
        @DisplayName("positions every loudspeaker of a program config element")
        void everyPosition() {
            var coding = EncoderLayout.of(EVERY_POSITION, RATE).orElseThrow();
            assertEquals(0, coding.channelConfiguration());
            assertEquals(23, coding.programConfig().channels());
        }

        @Test
        @DisplayName("reads back any element lists and heights it writes")
        void roundTrip() {
            var random = new Random(0x50434548L);
            for (var round = 0; round < 5_000; round++) {
                var tag = new int[1];
                var front = elements(random, tag);
                var side = elements(random, tag);
                var back = elements(random, tag);
                var lfe = random.nextInt(3) == 0 ? List.of(0) : List.<Integer>of();
                var channels = lfe.size();
                for (var list : List.of(front, side, back)) {
                    for (var element : list) {
                        channels += element.pair() ? 2 : 1;
                    }
                }
                if (channels == 0) {
                    continue;
                }
                var program = new ProgramConfig(0, 1, 3, front, side, back, lfe, List.of(), channels);
                var writer = new MsbBitWriter();
                writer.write(2, 5);
                writer.write(3, 4);
                writer.write(0, 4);
                writer.write(0, 3);
                program.write(writer);
                writer.alignWithZeros();
                assertEquals(program, AudioSpecificConfig.parse(writer.toByteArray()).programConfig());
            }
        }

        private static List<Element> elements(Random random, int[] tag) {
            var elements = new ArrayList<Element>();
            var heights = Height.values();
            for (var count = random.nextInt(6); count > 0; count--) {
                elements.add(new Element(random.nextBoolean(), tag[0]++ & 15, heights[random.nextInt(heights.length)]));
            }
            return elements;
        }
    }

    @Nested
    @DisplayName("round trips")
    class RoundTrips {
        static Stream<Object[]> layouts() {
            return Stream.concat(fixtures().map(fixture -> new Object[]{fixture.name(), fixture.layout()}),
                    Stream.<Object[]>of(new Object[]{"every position", EVERY_POSITION}));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("layouts")
        @DisplayName("puts every input channel on its loudspeaker through this module's decoder")
        void decoder(String name, ChannelLayout layout) {
            var input = AacEncoderLayoutsTest.tones(layout, RATE, RATE);
            var encoded = AacEncoderTest.encode(options(layout), input);
            var decoded = AacTestSupport.decodeAll(new AacDecoder(AacEncoderLayoutsTest.track(encoded.config(), RATE)), encoded.frames());
            assertEquals(AacEncoderLayoutsTest.sorted(layout), decoded.layout().speakers());
            for (var speaker : layout.speakers()) {
                AacEncoderLayoutsTest.assertTones(name, layout, speaker, decoded.planes()[decoded.layout().indexOf(speaker)]);
            }
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.audio.aac.AacHeightExtensionTest#fixtures")
        @DisplayName("decodes the same samples as ffmpeg, whose channels carry one input channel each")
        void ffmpeg(Fixture fixture) {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var layout = fixture.layout();
            var input = AacEncoderLayoutsTest.tones(layout, RATE, RATE);
            var encoded = AacEncoderTest.encode(options(layout), input);
            var file = AacEncoderLayoutsTest.mp4("height " + fixture.name(), encoded, RATE,
                    new ChannelLayout(AacEncoderLayoutsTest.sorted(layout)));
            Oracle.assertFfmpegDecodes(file);
            var reference = AacEncoderLayoutsTest.ffmpegDecode(file);
            assertEquals(layout.channels(), reference.planes().length);
            var decoded = AacTestSupport.decodeAll(new AacDecoder(AacEncoderLayoutsTest.track(encoded.config(), RATE)), encoded.frames());
            var carried = new HashSet<Speaker>();
            for (var plane : reference.planes()) {
                var speaker = AacEncoderLayoutsTest.dominant(layout, plane);
                assertTrue(carried.add(speaker), "two ffmpeg channels carry " + speaker);
                AacEncoderLayoutsTest.assertTones("ffmpeg", layout, speaker, plane);
                var ours = decoded.planes()[decoded.layout().indexOf(speaker)];
                var length = Math.min(ours.length, plane.length);
                Tolerance.maxAbs(1e-4).assertPcm(fixture.name() + " " + speaker, Arrays.copyOf(plane, length),
                        Arrays.copyOf(ours, length));
            }
        }

        @ParameterizedTest(name = "{0}, {1}-sample frames")
        @CsvSource({"HE, 1024", "HE, 960", "LC, 960"})
        @DisplayName("keeps the extension through HE-AAC signaling and 960-sample frames")
        void profiles(AacProfile profile, int frameLength) {
            var layout = fixtures().filter(fixture -> fixture.name().equals("7.1.4")).findFirst().orElseThrow().layout();
            var options = AacEncoderOptions.builder().format(RATE, layout).bitrate(48000 * layout.channels())
                    .profile(profile).frameLength(frameLength).build();
            var encoded = AacEncoderTest.encode(options, AacEncoderLayoutsTest.tones(layout, RATE, RATE));
            var decoded = AacTestSupport.decodeAll(new AacDecoder(AacEncoderLayoutsTest.track(encoded.config(), RATE)), encoded.frames());
            assertEquals(AacEncoderLayoutsTest.sorted(layout), decoded.layout().speakers());
            for (var speaker : layout.speakers()) {
                AacEncoderLayoutsTest.assertTones(profile.name(), layout, speaker, decoded.planes()[decoded.layout().indexOf(speaker)]);
            }
        }

        @Test
        @DisplayName("keeps the extension through ADIF")
        void adif() throws IOException {
            var layout = fixtures().filter(fixture -> fixture.name().equals("7.1.4")).findFirst().orElseThrow();
            var input = AacEncoderLayoutsTest.tones(layout.layout(), RATE, RATE / 4);
            var encoded = AacEncoderTest.encode(options(layout.layout()), input);
            var sink = MediaSink.memory();
            try (var muxer = new AdifMuxer(sink)) {
                muxer.addTrack(AudioTrack.builder().id(0).codec(Codec.AAC).timeBase(Rational.timeBaseOf(RATE)).duration(-1)
                        .sampleRate(RATE).codecConfig(encoded.config()).build());
                for (var packet : encoded.packets()) {
                    muxer.writePacket(packet);
                }
                muxer.finish();
            }
            try (var demuxer = new AdifDemuxer(MediaSource.of(sink.toByteArray()))) {
                var config = demuxer.tracks().getFirst().codecConfig().orElseThrow();
                assertEquals(layout.coded(), codedSpeakers(config));
            }
        }
    }

    @Test
    @DisplayName("assigns no loudspeaker twice in any fixture")
    void distinct() {
        fixtures().forEach(fixture -> assertEquals(fixture.coded().size(), new HashSet<>(fixture.coded()).size()));
    }
}
