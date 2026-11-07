package com.github.auties00.smalt.video.av1;

import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.av1.internal.BitReader;
import com.github.auties00.smalt.video.av1.internal.FrameHeader;
import com.github.auties00.smalt.video.av1.internal.SequenceHeader;
import com.github.auties00.smalt.video.av1.internal.encoder.Av1EncTesting;
import com.github.auties00.smalt.video.vp9.internal.Vp9TestVideo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the timing information of the predicted usages: the sequence header's timing, decoder
 * model and display model fields, the removal and presentation times of every frame header, and
 * streams that decode with the module's decoder and with dav1d exactly as without timing.
 */
class Av1EncoderTimingTest {
    private static final Rational TIME_BASE = new Rational(1, 90_000);

    private static final int WIDTH = 96;

    private static final int HEIGHT = 64;

    private static final int FRAMES = 20;

    private static final long FRAME_TICKS = 3000;

    static Av1EncoderOptions options(Av1EncoderUsage usage, Av1TimingInfo timing) {
        var base = usage == Av1EncoderUsage.REALTIME
                ? Av1EncoderOptions.forRealtime().toBuilder().bitrate(400_000).dropFrames(false)
                        .keyframeInterval(12)
                : Av1EncoderOptions.ofQuality(55).toBuilder().speed(Av1EncoderSpeed.FAST).lookahead(10)
                        .keyframeInterval(12);
        return base.timeBase(TIME_BASE).timingInfo(timing).build();
    }

    static Stream<Arguments> streams() {
        return Stream.of(Av1EncoderUsage.REALTIME, Av1EncoderUsage.GOOD_QUALITY)
                .flatMap(usage -> Stream.of(Av1TimingInfo.CONSTANT_FRAME_RATE, Av1TimingInfo.DECODER_MODEL)
                        .map(timing -> Arguments.of(usage, timing)));
    }

    record Encoded(List<Packet> packets, List<Picture> decoded, byte[] config) {
    }

    static Encoded run(Av1EncoderOptions options) {
        var sources = new ArrayList<Picture>(Vp9TestVideo.synthesize(WIDTH, HEIGHT, FRAMES));
        try (var encoder = new Av1Encoder(WIDTH, HEIGHT, options)) {
            var packets = Av1EncoderPictureFormatTest.encode(encoder, sources);
            return new Encoded(packets, Av1EncoderPictureFormatTest.decode(encoder, packets),
                    encoder.codecConfig().orElseThrow());
        }
    }

    static SequenceHeader sequence(byte[] config) {
        // av1C: four bytes of fields, then the sequence header OBU with its size
        var offset = 4;
        var header = config[offset] & 0xFF;
        assertEquals(1, (header >> 3) & 15, "sequence header OBU");
        offset++;
        var size = 0;
        var shift = 0;
        int b;
        do {
            b = config[offset++] & 0xFF;
            size |= (b & 0x7F) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);
        var gb = new BitReader();
        gb.init(config, offset, size);
        var seq = new SequenceHeader();
        assertTrue(seq.parse(gb, true), "sequence header parses");
        return seq;
    }

    /** Parses every frame header of the packets, in order, tracking the reference slots. */
    static List<FrameHeader> frameHeaders(SequenceHeader seq, List<Packet> packets) {
        var out = new ArrayList<FrameHeader>();
        var refs = new FrameHeader[8];
        for (var packet : packets) {
            var data = packet.data();
            var pos = 0;
            while (pos < data.length) {
                var header = data[pos++] & 0xFF;
                var type = (header >> 3) & 15;
                var extension = (header & 4) != 0;
                var temporalId = 0;
                if (extension) {
                    temporalId = (data[pos++] & 0xFF) >> 5;
                }
                var size = 0;
                var shift = 0;
                int b;
                do {
                    b = data[pos++] & 0xFF;
                    size |= (b & 0x7F) << shift;
                    shift += 7;
                } while ((b & 0x80) != 0);
                if (type == 3 || type == 6) {
                    var gb = new BitReader();
                    gb.init(data, pos, size);
                    var h = new FrameHeader();
                    h.temporalId = temporalId;
                    assertTrue(h.parse(seq, gb, refs, false), "frame header parses");
                    out.add(h);
                    if (!h.showExistingFrame) {
                        for (var s = 0; s < 8; s++) {
                            if ((h.refreshFrameFlags & (1 << s)) != 0) {
                                refs[s] = h;
                            }
                        }
                    }
                }
                pos += size;
            }
        }
        return out;
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("streams")
    @DisplayName("signals timing in a stream that decodes to dav1d's output exactly")
    void decodes(Av1EncoderUsage usage, Av1TimingInfo timing) throws IOException {
        var stream = run(options(usage, timing));
        var plain = run(options(usage, Av1TimingInfo.NONE));
        assertEquals(FRAMES, stream.decoded().size(), "decoded frames");
        if (usage == Av1EncoderUsage.GOOD_QUALITY) {
            // a constant quality spends the same decisions whatever the headers carry
            for (var i = 0; i < FRAMES; i++) {
                assertArrayEquals(Av1EncoderPictureFormatTest.raw(plain.decoded().get(i)),
                        Av1EncoderPictureFormatTest.raw(stream.decoded().get(i)), "frame " + i);
            }
        }
        if (Oracle.find(Oracle.Tool.FFMPEG).isEmpty()) {
            return;
        }
        var units = new ArrayList<byte[]>();
        for (var p : stream.packets()) {
            units.add(p.data());
        }
        var file = Scratch.directory("av1-timing").resolve(usage + "-" + timing + ".ivf");
        Files.write(file, Av1EncTesting.ivf(WIDTH, HEIGHT, units));
        var reference = Oracle.decodeVideo(file, List.of("-c:v", "libdav1d"), "yuv420p", 0);
        var ours = new ByteArrayOutputStream();
        for (var p : stream.decoded()) {
            ours.writeBytes(Av1EncoderPictureFormatTest.raw(p));
        }
        assertArrayEquals(reference, ours.toByteArray(), "dav1d output");
    }

    @Test
    @DisplayName("signals a constant frame rate with the display model")
    void constantFrameRate() {
        var options = options(Av1EncoderUsage.REALTIME, Av1TimingInfo.CONSTANT_FRAME_RATE).toBuilder()
                .frameRate(new Rational(30000, 1001)).build();
        var seq = sequence(run(options).config());
        assertTrue(seq.timingInfoPresent);
        assertEquals(1001, seq.numUnitsInTick);
        assertEquals(30000, seq.timeScale);
        assertTrue(seq.equalPictureInterval);
        assertEquals(1, seq.numTicksPerPicture);
        assertFalse(seq.decoderModelInfoPresent);
        assertTrue(seq.displayModelInfoPresent);
        assertTrue(seq.displayModelParamPresent[0]);
        assertEquals(8, seq.initialDisplayDelay[0]);
    }

    @Test
    @DisplayName("signals no timing by default")
    void none() {
        var seq = sequence(run(options(Av1EncoderUsage.GOOD_QUALITY, Av1TimingInfo.NONE)).config());
        assertFalse(seq.timingInfoPresent);
        assertFalse(seq.decoderModelInfoPresent);
        assertFalse(seq.displayModelInfoPresent);
        assertEquals(Av1TimingInfo.NONE, Av1EncoderOptions.defaults().timingInfo());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("usages")
    @DisplayName("signals the decoder model and each frame's removal and presentation times")
    void decoderModel(Av1EncoderUsage usage) {
        var stream = run(options(usage, Av1TimingInfo.DECODER_MODEL));
        var seq = sequence(stream.config());
        assertTrue(seq.timingInfoPresent);
        assertEquals(1, seq.numUnitsInTick);
        assertEquals(90_000, seq.timeScale);
        assertFalse(seq.equalPictureInterval);
        assertTrue(seq.decoderModelInfoPresent);
        assertEquals(FRAME_TICKS, seq.numUnitsInDecodingTick);
        assertEquals(16, seq.encoderDecoderBufferDelayLength);
        assertEquals(16, seq.bufferRemovalDelayLength);
        assertEquals(32, seq.framePresentationDelayLength);
        assertTrue(seq.decoderModelParamPresent[0]);
        assertEquals(45_000, seq.decoderBufferDelay[0]);
        assertEquals(45_000, seq.encoderBufferDelay[0]);
        assertFalse(seq.lowDelayMode[0]);
        assertEquals(8, seq.initialDisplayDelay[0]);
        var headers = frameHeaders(seq, stream.packets());
        var shown = 0;
        var keyDisplay = -1L;
        var decoded = 0L;
        var keys = 0;
        for (var h : headers) {
            if (!h.showExistingFrame) {
                assertTrue(h.bufferRemovalTimePresent, "removal times present");
                if (h.frameType == FrameHeader.KEY) {
                    keys++;
                    decoded = 0;
                    keyDisplay = shown;
                }
                assertEquals(decoded, h.bufferRemovalTime[0], "removal time");
                decoded++;
            }
            if (h.showExistingFrame || h.showFrame) {
                assertEquals((shown - keyDisplay) * FRAME_TICKS + 8 * FRAME_TICKS, h.framePresentationTime,
                        "presentation time of shown frame " + shown);
                shown++;
            }
        }
        assertEquals(FRAMES, shown, "shown frames");
        assertTrue(keys >= 2, "key frames " + keys);
    }

    static Stream<Av1EncoderUsage> usages() {
        return Stream.of(Av1EncoderUsage.REALTIME, Av1EncoderUsage.GOOD_QUALITY);
    }

    @Test
    @DisplayName("rejects timing outside the predicted usages and changes of a running encoder")
    void rejects() {
        assertThrows(IllegalArgumentException.class, () -> Av1EncoderOptions.forIntraOnly(50).toBuilder()
                .timingInfo(Av1TimingInfo.CONSTANT_FRAME_RATE).build());
        assertThrows(NullPointerException.class, () -> Av1EncoderOptions.builder().timingInfo(null));
        var options = options(Av1EncoderUsage.REALTIME, Av1TimingInfo.DECODER_MODEL);
        try (var encoder = new Av1Encoder(WIDTH, HEIGHT, options)) {
            assertThrows(IllegalArgumentException.class,
                    () -> encoder.reconfigure(options.toBuilder().timingInfo(Av1TimingInfo.NONE).build()));
            assertThrows(IllegalArgumentException.class,
                    () -> encoder.reconfigure(options.toBuilder().frameRate(new Rational(25, 1)).build()));
            encoder.reconfigure(options.toBuilder().bitrate(300_000).build());
        }
    }
}
