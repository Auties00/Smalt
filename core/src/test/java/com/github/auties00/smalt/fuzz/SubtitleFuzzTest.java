package com.github.auties00.smalt.fuzz;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.subtitle.SubtitleCue;
import com.github.auties00.smalt.subtitle.SubtitleDecoder;
import com.github.auties00.smalt.subtitle.SubtitleMarkup;
import com.github.auties00.smalt.subtitle.SubtitleReaderOptions;
import com.github.auties00.smalt.subtitle.Subtitles;
import com.github.auties00.smalt.testkit.fuzz.Fuzz;
import com.github.auties00.smalt.testkit.fuzz.FuzzPackets;
import com.github.auties00.smalt.testkit.fuzz.FuzzRun;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.DataTrack;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;

import java.nio.charset.StandardCharsets;

/**
 * Fuzz targets of the subtitle readers: the cues of any file ({@link Subtitles#read}), the cue packets of every
 * text subtitle codec ({@link SubtitleDecoder}) and the markup converters ({@link Subtitles#convertText}).
 */
class SubtitleFuzzTest {
    /** The codecs of the cue target, chosen by the options byte modulo their count. */
    private static final Codec[] CODECS = {Codec.SUBRIP, Codec.WEBVTT, Codec.MOV_TEXT, Codec.ASS};

    /** The most cues of a file whose markup is converted. */
    private static final int MAX_CUES = 64;

    @FuzzTest
    void read(byte[] data) {
        var run = Fuzz.start("read");
        var cues = run.value("read", () -> Subtitles.read(MediaSource.of(data),
                SubtitleReaderOptions.builder().limits(Fuzz.LIMITS).build()));
        cues.ifPresent(list -> list.stream().limit(MAX_CUES).forEach(cue -> convert(run, cue)));
    }

    @FuzzTest
    void cues(byte[] data) {
        var run = Fuzz.start("cues");
        var input = FuzzPackets.parse(data, 1);
        var codec = CODECS[input.header().options() % CODECS.length];
        var track = DataTrack.builder().id(1).codec(codec).timeBase(new Rational(1, 1000));
        input.header().config().ifPresent(track::codecConfig);
        var decoder = new SubtitleDecoder(track.build());
        for (var index = 0; index < input.packets().size(); index++) {
            var part = input.packets().get(index);
            var packet = new Packet(1, part.data(), index * 1000L, index * 1000L, 900, true);
            run.value("cue " + index, () -> decoder.decode(packet)).ifPresent(cue -> convert(run, cue));
        }
    }

    @FuzzTest
    void markup(byte[] data) {
        var run = Fuzz.start("markup");
        var text = new String(data, StandardCharsets.UTF_8);
        for (var from : SubtitleMarkup.values()) {
            run.value("plain " + from, () -> Subtitles.plainText(text, from)).ifPresent(plain -> run.output());
            for (var to : SubtitleMarkup.values()) {
                run.step(from + " to " + to, () -> Subtitles.convertText(text, from, to));
            }
        }
    }

    private static void convert(FuzzRun run, SubtitleCue cue) {
        run.output();
        for (var from : SubtitleMarkup.values()) {
            for (var to : SubtitleMarkup.values()) {
                run.step("convert " + from + " to " + to, () -> Subtitles.convert(cue, from, to));
            }
        }
    }
}
