package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.subtitle.SubtitleCue;
import com.github.auties00.smalt.subtitle.SubtitleMarkup;
import com.github.auties00.smalt.subtitle.SubtitleReaderOptions;
import com.github.auties00.smalt.subtitle.Subtitles;
import com.github.auties00.smalt.track.Codec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static com.github.auties00.smalt.container.mp4.Mp4Builder.box;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.concat;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.file;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.mvhd;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.stsz;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.table;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.trak;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.u16;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.u32;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.zeros;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks QuickTime text media ({@code text} sample entries, QuickTime File Format "Text Media") in MP4 and MOV
 * files: the track reads as 3GPP timed text with its styles, and a remux writes it as a {@code tx3g} track.
 */
@DisplayName("QuickTime text tracks")
class Mp4QuickTimeTextTest {
    private static byte[] rgb16(int red, int green, int blue) {
        return concat(u16(red), u16(green), u16(blue));
    }

    /** A {@code text} sample entry: centered, white Helvetica (font 21), a text box of 40 by 320 pixels. */
    private static byte[] textEntry() {
        var name = "Helvetica".getBytes(StandardCharsets.US_ASCII);
        return box("text", zeros(6), u16(1), u32(0), u32(1), rgb16(0, 0, 0), u16(0), u16(0), u16(40), u16(320), zeros(8),
                u16(21), u16(0), zeros(3), rgb16(0xFFFF, 0xFFFF, 0xFFFF), new byte[]{(byte) name.length}, name);
    }

    /** A text sample in Mac OS Roman with a style scrap making its first word italic Courier. */
    private static byte[] sample(String text, int italicEnd) {
        var bytes = text.getBytes(StandardCharsets.ISO_8859_1);
        var styles = box("styl", u16(2),
                concat(u32(0), u16(16), u16(12), u16(22), new byte[]{2, 0}, u16(12), rgb16(0xFFFF, 0xFFFF, 0xFFFF)),
                concat(u32(italicEnd), u16(16), u16(12), u16(21), new byte[]{0, 0}, u16(12), rgb16(0xFFFF, 0xFFFF, 0xFFFF)));
        return concat(u16(bytes.length), bytes, styles);
    }

    private static byte[] movie() {
        var first = sample("Hello there", 5);
        var second = new byte[]{0, 0};
        var third = sample("Bye now", 3);
        var data = concat(first, second, third);
        var start = Mp4Builder.DATA_START;
        var tables = concat(table("stts", 0, 2, 3, 600), table("stsc", 0, 3, 1, 3, 1), stsz(0, first.length, second.length,
                third.length), table("stco", 0, 1, start));
        return file(data, mvhd(600, 1800), trak(1, "text", 600, 1800, textEntry(), tables));
    }

    @Test
    @DisplayName("reads the samples as 3GPP timed text cues with their styles")
    void read() {
        var file = movie();
        try (var demuxer = Containers.openDemuxer(MediaSource.of(file))) {
            assertEquals(Codec.MOV_TEXT, demuxer.tracks().getFirst().codec());
        }
        var cues = Subtitles.read(MediaSource.of(file), SubtitleReaderOptions.builder().markup(SubtitleMarkup.SUBRIP).build());
        assertEquals(List.of(SubtitleCue.of(0, 1_000_000, "<font face=\"Courier\"><i>Hello</i></font> there"),
                SubtitleCue.of(2_000_000, 3_000_000, "<font face=\"Courier\"><i>Bye</i></font> now")), cues);
    }

    @Test
    @DisplayName("remuxes the track as tx3g with the same cues")
    void remux() {
        var sink = MediaSink.memory();
        Mp4Remuxer.remux(MediaSource.of(movie()), sink, RemuxOptions.builder().allSubtitleTracks().build());
        var output = sink.toByteArray();
        com.github.auties00.smalt.testkit.validate.Validators.assertValid("quicktime-text.mp4", output);
        assertEquals(-1, new String(output, StandardCharsets.ISO_8859_1).indexOf("text\u0000\u0000"));
        var cues = Subtitles.read(MediaSource.of(output), SubtitleReaderOptions.builder().markup(SubtitleMarkup.SUBRIP).build());
        assertEquals(List.of(SubtitleCue.of(0, 1_000_000, "<font face=\"Courier\"><i>Hello</i></font> there"),
                SubtitleCue.of(2_000_000, 3_000_000, "<font face=\"Courier\"><i>Bye</i></font> now")), cues);
    }
}
