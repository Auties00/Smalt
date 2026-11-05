package com.github.auties00.smalt.video.h264.encoder;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.container.ts.TsMuxer;
import com.github.auties00.smalt.container.ts.TsMuxerOptions;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.validate.Validators;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.FieldOrder;
import com.github.auties00.smalt.video.FrameSideData;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.h264.H264Decoder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the hypothetical reference decoder signaling: every stream is checked against the decoder it signals
 * by {@link HrdVerifier}, an exact model of ITU-T H.264 Annex C written from the specification (itself checked
 * on a stream libx264 coded with {@code nal-hrd=cbr}, run through ffmpeg as a black box, and on corruptions of
 * it); ffmpeg's {@code trace_headers} filter parses the same HRD parameters and SEI values independently; ffmpeg
 * and Smalt decode the streams without error; constant bitrate streams carry filler data and keep the rate.
 */
class HrdTest {
    private static final int WIDTH = 160;

    private static final int HEIGHT = 96;

    private static final int FPS = 30;

    /**
     * A clip whose complexity changes: still content, which a constant bitrate stream must pad, heavy noise,
     * which fills the buffer, and moderate motion; 150 frames.
     */
    static List<YuvPicture> clip(int width, int height) {
        var pictures = new ArrayList<YuvPicture>();
        var still = EncoderHarness.synthetic(width, height, 0, 2, 0);
        for (var i = 0; i < 50; i++) {
            pictures.add(still);
        }
        for (var i = 0; i < 50; i++) {
            pictures.add(EncoderHarness.synthetic(width, height, 50 + i, 4, 14.0));
        }
        for (var i = 0; i < 50; i++) {
            pictures.add(EncoderHarness.synthetic(width, height, 100 + i, 6, 2.0));
        }
        return pictures;
    }

    static H264EncoderOptions.Builder cbr(long bitrate) {
        return H264EncoderOptions.forFile().toBuilder()
                .frameRate(new Rational(FPS, 1))
                .timeBase(new Rational(1, 90_000))
                .rateControl(H264RateControl.CONSTANT_BITRATE)
                .bitrate(bitrate)
                .packetFormat(H264PacketFormat.ANNEX_B)
                .hrd(true);
    }

    /** The packets of an encode together with its parameter set out of band. */
    record Stream(List<Packet> packets, byte[] avcC, H264EncoderOptions options, int width, int height) {
        boolean annexB() {
            return options.packetFormat() == H264PacketFormat.ANNEX_B;
        }

        List<HrdVerifier.Nal> nals() {
            var nals = new ArrayList<HrdVerifier.Nal>();
            for (var packet : packets) {
                nals.addAll(annexB() ? HrdVerifier.annexB(packet.data()) : HrdVerifier.lengthPrefixed(packet.data()));
            }
            return nals;
        }

        List<Long> display() {
            var display = new ArrayList<Long>();
            var ticks = options.timeBase().invert().toDouble() / options.frameRate().toDouble();
            for (var packet : packets) {
                var count = annexB() ? HrdVerifier.annexB(packet.data()).size()
                        : HrdVerifier.lengthPrefixed(packet.data()).size();
                for (var i = 0; i < count; i++) {
                    display.add(Math.round(packet.pts() / ticks));
                }
            }
            return display;
        }

        byte[] sps() {
            if (annexB()) {
                return null;
            }
            var length = ((avcC[6] & 0xFF) << 8) | (avcC[7] & 0xFF);
            return java.util.Arrays.copyOfRange(avcC, 8, 8 + length);
        }

        HrdVerifier.Report verify() {
            return HrdVerifier.verify(nals(), display(), sps());
        }

        /** Returns the stream in the byte stream format, parameter sets first when they travel out of band. */
        byte[] byteStream() {
            var out = new ByteArrayOutputStream();
            if (!annexB()) {
                var spsLength = ((avcC[6] & 0xFF) << 8) | (avcC[7] & 0xFF);
                var ppsOffset = 8 + spsLength + 1;
                var ppsLength = ((avcC[ppsOffset] & 0xFF) << 8) | (avcC[ppsOffset + 1] & 0xFF);
                out.writeBytes(new byte[]{0, 0, 0, 1});
                out.write(avcC, 8, spsLength);
                out.writeBytes(new byte[]{0, 0, 0, 1});
                out.write(avcC, ppsOffset + 2, ppsLength);
            }
            for (var nal : nals()) {
                out.writeBytes(new byte[]{0, 0, 0, 1});
                out.writeBytes(nal.data());
            }
            return out.toByteArray();
        }
    }

    static Stream encode(int width, int height, H264EncoderOptions options, List<YuvPicture> pictures,
                         boolean captions) {
        var packets = new ArrayList<Packet>();
        byte[] avcC;
        var ticks = Math.round(options.timeBase().invert().toDouble() / options.frameRate().toDouble());
        try (var encoder = new H264Encoder(width, height, options)) {
            for (var i = 0; i < pictures.size(); i++) {
                var side = captions ? FrameSideData.builder().closedCaptions(new byte[]{(byte) 0xFC, 0x14, 0x2C,
                        (byte) 0xFC, (byte) (0x41 + i % 26), 0x42}).build() : FrameSideData.NONE;
                encoder.encode(new VideoFrame(pictures.get(i), i * ticks, ticks, false, side), packets::add);
            }
            encoder.flush(packets::add);
            avcC = encoder.codecConfig().orElse(null);
        }
        return new Stream(packets, avcC, options, width, height);
    }

    record Case(String name, H264EncoderOptions options, int width, int height, boolean captions) {
        @Override
        public String toString() {
            return name;
        }
    }

    static java.util.stream.Stream<Case> cases() {
        return java.util.stream.Stream.of(
                new Case("CBR 300 kbps, B pictures and look-ahead", cbr(300_000).build(), WIDTH, HEIGHT, false),
                new Case("CBR 120 kbps, one-second buffer", cbr(120_000).bufferSize(Duration.ofSeconds(1)).build(),
                        WIDTH, HEIGHT, false),
                new Case("CBR 29.97 frames per second", cbr(250_000).frameRate(new Rational(30_000, 1001)).build(),
                        WIDTH, HEIGHT, false),
                new Case("CBR real-time preset", H264EncoderOptions.forRealtime().toBuilder().bitrate(200_000)
                        .hrd(true).build(), WIDTH, HEIGHT, false),
                new Case("CBR length-prefixed packets", cbr(300_000).packetFormat(H264PacketFormat.LENGTH_PREFIXED)
                        .build(), WIDTH, HEIGHT, false),
                new Case("CBR with closed captions", cbr(300_000).build(), WIDTH, HEIGHT, true),
                new Case("CBR four slices", cbr(300_000).slices(4).build(), WIDTH, HEIGHT, false),
                new Case("CBR High 4:2:2 10-bit", cbr(500_000).profile(H264Profile.HIGH_422)
                        .chromaFormat(ChromaFormat.YUV422).bitDepth(10).build(), WIDTH, HEIGHT, false),
                new Case("CBR interlaced field pictures", cbr(400_000).profile(H264Profile.HIGH)
                        .fieldOrder(FieldOrder.BOTTOM_FIRST).fieldCoding(H264FieldCoding.FIELD_PICTURES).build(),
                        WIDTH, HEIGHT, false),
                new Case("CBR interlaced MBAFF frames", cbr(400_000).profile(H264Profile.HIGH)
                        .fieldOrder(FieldOrder.TOP_FIRST).fieldCoding(H264FieldCoding.MBAFF).build(), WIDTH, HEIGHT,
                        false),
                new Case("CBR three temporal layers", cbr(300_000).temporalLayers(3).build(), WIDTH, HEIGHT, false),
                new Case("CBR intra refresh, one buffering period", cbr(300_000).intraRefresh(30).build(), WIDTH,
                        HEIGHT, false),
                new Case("VBR average bitrate", cbr(300_000).rateControl(H264RateControl.AVERAGE_BITRATE).build(),
                        WIDTH, HEIGHT, false),
                new Case("VBR interlaced field pictures", cbr(400_000).rateControl(H264RateControl.AVERAGE_BITRATE)
                        .profile(H264Profile.HIGH).fieldOrder(FieldOrder.TOP_FIRST)
                        .fieldCoding(H264FieldCoding.FIELD_PICTURES).build(), WIDTH, HEIGHT, false),
                new Case("VBR average bitrate, tight buffer", cbr(300_000)
                        .rateControl(H264RateControl.AVERAGE_BITRATE).maxBitrate(400_000)
                        .bufferSize(Duration.ofMillis(400)).build(), WIDTH, HEIGHT, false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("the stream conforms to the decoder it signals")
    void conforms(Case test) {
        var stream = encode(test.width(), test.height(), test.options(), clip(test.width(), test.height()),
                test.captions());
        var report = stream.verify();
        var config = new H264Setup(test.width(), test.height(), test.options()).resolved();
        assertEquals(config.effectiveMaxBitrate(), report.bitRate());
        assertEquals(config.effectiveBufferSize(), report.cpbSize());
        assertEquals(0, report.bitRate() % 64);
        assertEquals(test.options().rateControl() == H264RateControl.CONSTANT_BITRATE, report.cbr());
        assertTrue(report.picStructPresent());
        var fields = test.options().fieldCoding() == H264FieldCoding.FIELD_PICTURES
                && test.options().fieldOrder().isInterlaced();
        assertEquals(150 * (fields ? 2 : 1), report.accessUnits());
        for (var packet : stream.packets()) {
            var units = stream.annexB() ? HrdVerifier.annexB(packet.data()) : HrdVerifier.lengthPrefixed(packet.data());
            assertEquals(9, units.getFirst().type(), "every packet starts with an access unit delimiter");
            if (packet.keyframe()) {
                assertTrue(units.stream().anyMatch(nal -> nal.type() == 7) && units.stream().anyMatch(nal -> nal.type() == 8),
                        "an IDR access unit carries the parameter sets");
            }
        }
        if (report.cbr()) {
            assertTrue(report.fillerUnits() > 0, "a constant bitrate stream over still content carries filler data");
            // a constant delivery rate fills the buffer at exactly the bitrate: what arrived by the last removal
            // less what the stream holds is the last fullness, between 0 and the buffer
            var arrived = report.lastRemoval().toDouble() * report.bitRate();
            assertTrue(arrived - report.totalBits() <= report.cpbSize() && arrived >= report.totalBits(),
                    "arrived " + arrived + ", stream " + report.totalBits());
        } else {
            assertEquals(0, report.fillerUnits());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("ffmpeg parses the same HRD parameters and timing and decodes every picture without error")
    void ffmpeg(Case test) {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var stream = encode(test.width(), test.height(), test.options(), clip(test.width(), test.height()),
                test.captions());
        var report = stream.verify();
        var file = EncoderHarness.write("hrd-" + test.name().replaceAll("\\W+", "-") + ".264", stream.byteStream());
        var trace = trace(file);
        assertEquals(List.of(1L), distinct(trace, "nal_hrd_parameters_present_flag"));
        assertEquals(List.of(report.bitRate() / 64 - 1), distinct(trace, "bit_rate_value_minus1[0]"));
        assertEquals(List.of(report.cpbSize() / 16 - 1), distinct(trace, "cpb_size_value_minus1[0]"));
        assertEquals(List.of(report.cbr() ? 1L : 0L), distinct(trace, "cbr_flag[0]"));
        assertEquals(List.of(0L), distinct(trace, "low_delay_hrd_flag"));
        assertEquals(List.of(1L), distinct(trace, "pic_struct_present_flag"));
        assertEquals(report.removalDelays(), values(trace, "cpb_removal_delay"));
        assertEquals(report.outputDelays(), values(trace, "dpb_output_delay"));
        assertEquals(report.initialDelays(), values(trace, "initial_cpb_removal_delay[0]"));
        assertEquals(report.fillerUnits(), values(trace, "nal_unit_type").stream().filter(type -> type == 12).count());
        var frameSize = test.width() * test.height() * 3 / 2;
        if (test.options().bitDepth() == 8 && test.options().chromaFormat() == ChromaFormat.YUV420) {
            var decoded = EncoderHarness.decodeStrict(file);
            assertEquals(150L * frameSize, decoded.length);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("Smalt's decoder decodes every picture")
    void smaltDecodes(Case test) {
        var stream = encode(test.width(), test.height(), test.options(), clip(test.width(), test.height()),
                test.captions());
        var track = VideoTrack.builder().id(1).codec(Codec.H264).timeBase(test.options().timeBase())
                .width(test.width()).height(test.height());
        if (stream.avcC() != null) {
            track.codecConfig(stream.avcC());
        }
        var count = new int[1];
        try (var decoder = new H264Decoder(track.build())) {
            for (var packet : stream.packets()) {
                decoder.decode(packet, frame -> count[0]++);
            }
            decoder.flush(frame -> count[0]++);
        }
        assertEquals(150, count[0]);
    }

    @Test
    @DisplayName("a constant bitrate stream with filler decodes in ffmpeg to exactly the encoder's reconstruction")
    void bitExact() {
        var options = cbr(200_000).packetFormat(H264PacketFormat.LENGTH_PREFIXED).build();
        var pictures = clip(WIDTH, HEIGHT);
        var packets = new ArrayList<Packet>();
        var byDisplay = new java.util.TreeMap<Long, byte[]>();
        byte[] sps;
        byte[] pps;
        byte[] avcC;
        try (var encoder = new H264Encoder(WIDTH, HEIGHT, options)) {
            var core = encoder.core();
            core.setReconstructUnreferenced(true);
            for (var i = 0; i < pictures.size(); i++) {
                var packet = core.encode(pictures.get(i), i * 3000L, 3000, null);
                if (packet != null) {
                    packets.add(packet);
                    byDisplay.put(core.lastDisplayIndex(), core.lastReconstructionI420());
                }
            }
            for (var packet = core.flush(); packet != null; packet = core.flush()) {
                packets.add(packet);
                byDisplay.put(core.lastDisplayIndex(), core.lastReconstructionI420());
            }
            sps = core.sequenceParameterSet();
            pps = core.pictureParameterSet();
            avcC = core.configurationRecord();
        }
        var stream = new Stream(packets, avcC, options, WIDTH, HEIGHT);
        assertTrue(stream.verify().fillerUnits() > 0);
        var encoded = new EncoderHarness.Encoded(new H264Setup(WIDTH, HEIGHT, options), packets,
                new ArrayList<>(byDisplay.values()), sps, pps, avcC, new long[0]);
        EncoderHarness.assertBitExact("hrd-cbr", encoded);
    }

    @Test
    @DisplayName("a constant bitrate stream keeps its conformance through a constant rate transport stream")
    void transportStream() {
        var options = cbr(400_000).packetFormat(H264PacketFormat.LENGTH_PREFIXED).build();
        var stream = encode(WIDTH, HEIGHT, options, clip(WIDTH, HEIGHT), false);
        var direct = stream.verify();
        var track = VideoTrack.builder().id(1).codec(Codec.H264).timeBase(options.timeBase()).width(WIDTH)
                .height(HEIGHT).codecConfig(stream.avcC()).build();
        var sink = MediaSink.memory();
        try (var muxer = new TsMuxer(sink, TsMuxerOptions.builder().muxRate(600_000).build())) {
            muxer.addTrack(track);
            for (var packet : stream.packets()) {
                muxer.writePacket(packet.withTrackId(1));
            }
            muxer.finish();
        }
        var ts = sink.toByteArray();
        Validators.assertValidTs(EncoderHarness.write("hrd-cbr.ts", ts));
        var nals = HrdVerifier.annexB(elementaryStream(ts));
        var carried = HrdVerifier.verify(nals, nals.stream().map(nal -> -1L).toList(), null);
        assertEquals(direct.accessUnits(), carried.accessUnits());
        assertEquals(direct.totalBits(), carried.totalBits(), "the multiplexer adds nothing to the access units");
        assertEquals(direct.fillerUnits(), carried.fillerUnits());
    }

    /** Returns the concatenated payloads of the video PES packets of a transport stream. */
    static byte[] elementaryStream(byte[] ts) {
        var out = new ByteArrayOutputStream();
        var videoPid = -1;
        for (var offset = 0; offset + 188 <= ts.length; offset += 188) {
            assertEquals(0x47, ts[offset] & 0xFF, "sync byte of the transport packet at " + offset);
            var pid = ((ts[offset + 1] & 0x1F) << 8) | (ts[offset + 2] & 0xFF);
            var start = (ts[offset + 1] & 0x40) != 0;
            var control = (ts[offset + 3] >> 4) & 3;
            if ((control & 1) == 0) {
                continue;
            }
            var payload = offset + 4 + ((control & 2) != 0 ? 1 + (ts[offset + 4] & 0xFF) : 0);
            if (start && payload + 9 <= offset + 188 && ts[payload] == 0 && ts[payload + 1] == 0 && ts[payload + 2] == 1
                    && (ts[payload + 3] & 0xF0) == 0xE0) {
                videoPid = pid;
                payload += 9 + (ts[payload + 8] & 0xFF);
            } else if (pid != videoPid) {
                continue;
            }
            out.write(ts, payload, offset + 188 - payload);
        }
        return out.toByteArray();
    }

    @Test
    @DisplayName("a two-pass encode signals a variable bitrate decoder it conforms to")
    void twoPass() {
        var options = cbr(300_000).rateControl(H264RateControl.AVERAGE_BITRATE).timeBase(new Rational(1, FPS)).build();
        var pictures = clip(WIDTH, HEIGHT);
        var pass = new H264FirstPass(WIDTH, HEIGHT, options);
        for (var i = 0; i < pictures.size(); i++) {
            pass.add(new VideoFrame(pictures.get(i), i, 1, false));
        }
        var stats = pass.finish();
        var stream = encode(WIDTH, HEIGHT, options.toBuilder().firstPassStats(stats).build(), pictures, false);
        var report = stream.verify();
        assertFalse(report.cbr());
        var target = 300_000 * stats.duration().toNanos() / 1e9;
        var actual = stream.packets().stream().mapToLong(Packet::size).sum() * 8.0;
        assertTrue(Math.abs(actual - target) <= 0.02 * target, actual + " bits for a target of " + target);
    }

    @Nested
    @DisplayName("the reference model")
    class Model {
        /** A constant bitrate stream libx264 coded with nal-hrd=cbr through ffmpeg, a black-box reference. */
        private byte[] x264() {
            Oracle.assumeEncoder("libx264");
            var file = Fixtures.ffmpeg("264", List.of("-f", "lavfi", "-i", "testsrc2=size=160x96:rate=25,"
                            + "noise=alls=20:allf=t+u"),
                    List.of("-frames:v", "75", "-c:v", "libx264", "-preset", "veryfast", "-x264-params",
                            "nal-hrd=cbr:force-cfr=1:keyint=25", "-b:v", "150k", "-minrate", "150k", "-maxrate",
                            "150k", "-bufsize", "75k", "-f", "h264"));
            try {
                return Files.readAllBytes(file);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }

        private static List<Long> unknown(List<HrdVerifier.Nal> nals) {
            return nals.stream().map(nal -> -1L).toList();
        }

        @Test
        @DisplayName("accepts libx264's constant bitrate stream, which carries filler data")
        void acceptsReference() {
            var nals = HrdVerifier.annexB(x264());
            var report = HrdVerifier.verify(nals, unknown(nals), null);
            assertTrue(report.cbr());
            assertEquals(75, report.accessUnits());
            assertTrue(report.fillerUnits() > 0);
        }

        @Test
        @DisplayName("rejects the reference stream without its filler data, which then overflows the buffer")
        void rejectsMissingFiller() {
            var nals = new ArrayList<>(HrdVerifier.annexB(x264()));
            nals.removeIf(nal -> nal.type() == 12);
            var violation = assertThrows(HrdVerifier.Violation.class, () -> HrdVerifier.verify(nals, unknown(nals),
                    null));
            assertTrue(violation.getMessage().contains("overflow") || violation.getMessage().contains("gap"),
                    violation.getMessage());
        }

        @Test
        @DisplayName("rejects an access unit too large to arrive before its removal")
        void rejectsUnderflow() {
            var nals = new ArrayList<>(HrdVerifier.annexB(x264()));
            var index = 0;
            for (var i = 0; i < nals.size(); i++) {
                if (nals.get(i).type() == 1) {
                    index = i;
                    break;
                }
            }
            nals.add(index + 1, new HrdVerifier.Nal(fillerOf(20_000), 4));
            var violation = assertThrows(HrdVerifier.Violation.class, () -> HrdVerifier.verify(nals, unknown(nals),
                    null));
            assertTrue(violation.getMessage().contains("underflow"), violation.getMessage());
        }

        @Test
        @DisplayName("rejects the encoder's stream when the filler data is removed")
        void rejectsEncoderWithoutFiller() {
            var stream = encode(WIDTH, HEIGHT, cbr(300_000).build(), clip(WIDTH, HEIGHT), false);
            var nals = new ArrayList<>(stream.nals());
            var display = new ArrayList<>(stream.display());
            for (var i = nals.size() - 1; i >= 0; i--) {
                if (nals.get(i).type() == 12) {
                    nals.remove(i);
                    display.remove(i);
                }
            }
            assertThrows(HrdVerifier.Violation.class, () -> HrdVerifier.verify(nals, display, null));
        }

        private static byte[] fillerOf(int bytes) {
            var data = new byte[bytes + 2];
            data[0] = 12;
            java.util.Arrays.fill(data, 1, bytes + 1, (byte) 0xFF);
            data[bytes + 1] = (byte) 0x80;
            return data;
        }
    }

    @Nested
    @DisplayName("validation")
    class Validation {
        @Test
        @DisplayName("the decoder needs a lossy bitrate mode")
        void modes() {
            assertThrows(IllegalArgumentException.class, () -> cbr(300_000)
                    .rateControl(H264RateControl.CONSTANT_QUALITY).build());
            assertThrows(IllegalArgumentException.class, () -> cbr(300_000).profile(H264Profile.HIGH_444_PREDICTIVE)
                    .lossless(true).build());
            assertThrows(IllegalArgumentException.class, () -> cbr(300_000).maxBitrate(400_000).build());
            assertEquals(300_000, cbr(300_000).maxBitrate(300_000).build().maxBitrate().orElseThrow());
        }

        @Test
        @DisplayName("the buffer must last at least two frame periods")
        void buffer() {
            var options = cbr(300_000).bufferSize(Duration.ofMillis(50)).build();
            assertThrows(IllegalArgumentException.class, () -> new H264Encoder(WIDTH, HEIGHT, options));
            var tiny = cbr(300_000).bitrate(100).maxBitrate(100).bufferSize(Duration.ofMillis(100)).build();
            assertThrows(IllegalArgumentException.class, () -> new H264Encoder(WIDTH, HEIGHT, tiny));
        }

        @Test
        @DisplayName("the bitrate is fixed")
        void fixedBitrate() {
            try (var encoder = new H264Encoder(WIDTH, HEIGHT, cbr(300_000).build())) {
                assertThrows(IllegalStateException.class, () -> encoder.setTargetBitrate(200_000));
            }
        }

        @Test
        @DisplayName("bitrates and buffers round down to what the syntax represents")
        void rounding() {
            var options = cbr(300_050).bufferSize(Duration.ofMillis(777)).build();
            var config = new H264Setup(WIDTH, HEIGHT, options).resolved();
            assertEquals(300_032, config.bitrate());
            assertEquals(300_032, config.effectiveMaxBitrate());
            assertEquals(0, config.effectiveBufferSize() % 16);
            assertTrue(config.effectiveBufferSize() > 300_032 * 0.777 - 16
                    && config.effectiveBufferSize() <= 300_050 * 0.777);
        }

        @Test
        @DisplayName("without the option the stream signals no decoder and carries no filler")
        void off() {
            var stream = encode(WIDTH, HEIGHT, cbr(300_000).hrd(false).build(), clip(WIDTH, HEIGHT), false);
            assertTrue(stream.nals().stream().noneMatch(nal -> nal.type() == 12));
            var violation = assertThrows(HrdVerifier.Violation.class, stream::verify);
            assertTrue(violation.getMessage().contains("no NAL HRD"), violation.getMessage());
        }

        @Test
        @DisplayName("the option round-trips through the builder")
        void builder() {
            var options = cbr(300_000).build();
            assertTrue(options.hrd());
            assertEquals(options, options.toBuilder().build());
            assertFalse(options.toBuilder().hrd(false).build().hrd());
            assertTrue(options.toString().contains("hrd=true"));
        }
    }

    /** Returns every syntax element ffmpeg's trace_headers filter reports, in order, as name and value pairs. */
    static List<String[]> trace(java.nio.file.Path file) {
        var text = Oracle.runMerged(Oracle.Tool.FFMPEG, Duration.ofMinutes(5), List.of("-v", "trace", "-f", "h264",
                "-i", file.toString(), "-c", "copy", "-bsf:v", "trace_headers", "-f", "null", "-"));
        var pattern = Pattern.compile("\\[trace_headers @ [^\\]]*\\]\\s+\\d+\\s+(\\S+)\\s+[01]+ = (-?\\d+)");
        var values = new ArrayList<String[]>();
        for (var line : text.split("\\R")) {
            var matcher = pattern.matcher(line);
            if (matcher.find()) {
                values.add(new String[]{matcher.group(1), matcher.group(2)});
            }
        }
        return values;
    }

    static List<Long> values(List<String[]> trace, String name) {
        return trace.stream().filter(entry -> entry[0].equals(name)).map(entry -> Long.parseLong(entry[1])).toList();
    }

    static List<Long> distinct(List<String[]> trace, String name) {
        return values(trace, name).stream().distinct().toList();
    }
}
