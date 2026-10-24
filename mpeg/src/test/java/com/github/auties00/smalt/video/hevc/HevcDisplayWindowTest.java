package com.github.auties00.smalt.video.hevc;

import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.codec.VideoConfigs;
import com.github.auties00.smalt.internal.nal.NalUnits;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.h264.internal.H264BitReader;
import com.github.auties00.smalt.video.hevc.internal.HevcSps;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the VUI fields the HEVC sequence parameter set parser keeps for display: the sample
 * aspect ratio (from the table and as {@code Extended_SAR}), {@code field_seq_flag} and the default
 * display window, and the decoder's optional crop by that window, checked against ffmpeg's
 * {@code -apply_defdispwin} decode. The streams are encoded by x265 through ffmpeg.
 */
@DisplayName("HEVC VUI display fields")
class HevcDisplayWindowTest {
    /**
     * An x265 stream; the display window option is in chroma sample units, left, top, right, bottom.
     */
    static Path stream(String sar, String window) {
        var params = "log-level=error" + (window == null ? "" : ":display-window=" + window);
        return Fixtures.ffmpeg("mkv", List.of("-f", "lavfi", "-i", "testsrc2=size=176x144:rate=25:duration=0.2"),
                List.of("-vf", "setsar=" + sar, "-c:v", "libx265", "-x265-params", params, "-pix_fmt", "yuv420p"));
    }

    record Demuxed(VideoTrack track, List<Packet> packets) {
    }

    static Demuxed demux(Path file) {
        try (var demuxer = Containers.openDemuxer(MediaSource.of(Fixtures.read(file)))) {
            var track = (VideoTrack) demuxer.tracks().getFirst();
            var packets = new ArrayList<Packet>();
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                packets.add(packet);
            }
            return new Demuxed(track, packets);
        }
    }

    /**
     * Parses the first sequence parameter set of an {@code hvcC} record with the decoder's parser.
     */
    static HevcSps sps(byte[] hvcC) {
        var position = 23;
        for (var array = 0; array < (hvcC[22] & 0xFF); array++) {
            var type = hvcC[position] & 0x3F;
            var count = ((hvcC[position + 1] & 0xFF) << 8) | (hvcC[position + 2] & 0xFF);
            position += 3;
            for (var unit = 0; unit < count; unit++) {
                var length = ((hvcC[position] & 0xFF) << 8) | (hvcC[position + 1] & 0xFF);
                position += 2;
                if (type == 33) {
                    var rbsp = new byte[length + H264BitReader.PADDING];
                    var size = NalUnits.unescape(hvcC, position + 2, length - 2, rbsp);
                    var reader = new H264BitReader();
                    reader.init(Arrays.copyOf(rbsp, size + H264BitReader.PADDING), 0, size);
                    return HevcSps.parse(reader);
                }
                position += length;
            }
        }
        throw new AssertionError("no sequence parameter set");
    }

    static List<YuvPicture> decode(Demuxed demuxed, HevcDecoderOptions options) {
        var pictures = new ArrayList<YuvPicture>();
        try (var decoder = new HevcDecoder(demuxed.track(), options)) {
            for (var packet : demuxed.packets()) {
                decoder.decode(packet, frame -> pictures.add(copy((YuvPicture) frame.picture())));
            }
            decoder.flush(frame -> pictures.add(copy((YuvPicture) frame.picture())));
        }
        return pictures;
    }

    static YuvPicture copy(YuvPicture picture) {
        var copy = YuvPicture.allocate(picture.width(), picture.height(), picture.chromaFormat(), false, picture.color());
        for (var y = 0; y < picture.height(); y++) {
            System.arraycopy(picture.y(), y * picture.yStride(), copy.y(), y * copy.yStride(), picture.width());
        }
        return copy;
    }

    static byte[] luma(YuvPicture picture) {
        var out = new byte[picture.width() * picture.height()];
        for (var y = 0; y < picture.height(); y++) {
            System.arraycopy(picture.y(), y * picture.yStride(), out, y * picture.width(), picture.width());
        }
        return out;
    }

    @ParameterizedTest(name = "setsar {0}")
    @CsvSource({"64/45, 64, 45", "16/11, 16, 11", "7/5, 7, 5", "1/1, 1, 1"})
    @DisplayName("keeps the VUI sample aspect ratio, from the table or as Extended_SAR, as ffprobe reads it")
    void sampleAspect(String sar, int width, int height) {
        var file = stream(sar, null);
        var config = demux(file).track().codecConfig().orElseThrow();
        var parsed = sps(config);
        assertEquals(width, parsed.sarWidth);
        assertEquals(height, parsed.sarHeight);
        assertEquals(new Rational(width, height), VideoConfigs.describe(com.github.auties00.smalt.track.Codec.HEVC, config)
                .sampleAspect());
        assertFalse(parsed.fieldSeq);
        assertEquals(0, parsed.displayLeft + parsed.displayRight + parsed.displayTop + parsed.displayBottom);
    }

    @Test
    @DisplayName("keeps the default display window in luma samples")
    void window() {
        var parsed = sps(demux(stream("1/1", "8,4,16,2")).track().codecConfig().orElseThrow());
        assertEquals(16, parsed.displayLeft);
        assertEquals(8, parsed.displayTop);
        assertEquals(32, parsed.displayRight);
        assertEquals(4, parsed.displayBottom);
    }

    @Test
    @DisplayName("crops by the default display window only when asked, as ffmpeg's -apply_defdispwin does")
    void crop() {
        var file = stream("1/1", "8,4,16,2");
        var demuxed = demux(file);
        var full = decode(demuxed, HevcDecoderOptions.defaults());
        var windowed = decode(demuxed, HevcDecoderOptions.builder().defaultDisplayWindow(true).build());
        assertEquals(full.size(), windowed.size());
        assertEquals(176, full.getFirst().width());
        assertEquals(144, full.getFirst().height());
        assertEquals(128, windowed.getFirst().width());
        assertEquals(132, windowed.getFirst().height());
        var whole = luma(full.getFirst());
        var inside = new byte[128 * 132];
        for (var y = 0; y < 132; y++) {
            System.arraycopy(whole, (y + 8) * 176 + 16, inside, y * 128, 128);
        }
        assertArrayEquals(inside, luma(windowed.getFirst()), "the window inside the full picture");
        Oracle.assume(Oracle.Tool.FFMPEG);
        // the luma plane of a yuv420p frame comes first; gray would rescale it to full range
        assertArrayEquals(Arrays.copyOf(Oracle.decodeVideo(file, "yuv420p", 1), 176 * 144), whole, "full picture");
        var reference = Oracle.decodeVideo(file, List.of("-apply_defdispwin", "1"), "yuv420p", 1);
        assertArrayEquals(Arrays.copyOf(reference, 128 * 132), luma(windowed.getFirst()), "windowed picture");
    }

    @Test
    @DisplayName("ignores the default display window of a stream without one")
    void noWindow() {
        var demuxed = demux(stream("1/1", null));
        var windowed = decode(demuxed, HevcDecoderOptions.builder().defaultDisplayWindow(true).build());
        assertEquals(176, windowed.getFirst().width());
        assertEquals(144, windowed.getFirst().height());
    }

    @ParameterizedTest(name = "display-window {0}")
    @CsvSource(delimiter = ';', value = {"100,100,100,100; 200", "70000,0,0,0; 0"})
    @DisplayName("ignores a default display window no picture could hold, as ffmpeg does, and still decodes")
    void invalidWindow(String window, int keptLeft) {
        var file = stream("1/1", window);
        var demuxed = demux(file);
        // offsets above 65535 chroma samples are dropped while parsing; smaller ones are kept and checked when cropping
        assertEquals(keptLeft, sps(demuxed.track().codecConfig().orElseThrow()).displayLeft);
        var full = decode(demuxed, HevcDecoderOptions.defaults());
        var windowed = decode(demuxed, HevcDecoderOptions.builder().defaultDisplayWindow(true).build());
        assertEquals(full.size(), windowed.size());
        assertEquals(176, windowed.getFirst().width());
        assertEquals(144, windowed.getFirst().height());
        assertArrayEquals(luma(full.getFirst()), luma(windowed.getFirst()));
        Oracle.assume(Oracle.Tool.FFMPEG);
        var reference = Oracle.decodeVideo(file, List.of("-apply_defdispwin", "1"), "yuv420p", 1);
        assertArrayEquals(Arrays.copyOf(reference, 176 * 144), luma(windowed.getFirst()), "whole picture");
    }

    @Test
    @DisplayName("records the option in equality and the string form")
    void options() {
        var on = HevcDecoderOptions.builder().defaultDisplayWindow(true).build();
        assertTrue(on.defaultDisplayWindow());
        assertFalse(HevcDecoderOptions.defaults().defaultDisplayWindow());
        assertNotEquals(HevcDecoderOptions.defaults(), on);
        assertEquals(on, on.toBuilder().build());
        assertTrue(on.toString().contains("defaultDisplayWindow=true"));
    }
}
