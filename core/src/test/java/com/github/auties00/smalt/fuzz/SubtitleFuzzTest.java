package com.github.auties00.smalt.fuzz;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.subtitle.BitmapCue;
import com.github.auties00.smalt.subtitle.BitmapSubtitleDecoder;
import com.github.auties00.smalt.subtitle.CaptionExtractor;
import com.github.auties00.smalt.subtitle.CaptionService;
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
import com.github.auties00.smalt.track.VideoTrack;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Fuzz targets of the subtitle readers: the cues of any file ({@link Subtitles#read}: SubRip, WebVTT, SSA, ASS,
 * TTML, SAMI, LRC, MicroDVD and the subtitle tracks of MP4 and Matroska files), the packets of every text subtitle
 * codec and of TTML ({@link SubtitleDecoder}, in its own markup and converted to each other), the markup
 * converters ({@link Subtitles#convertText}), the bitmap subtitle packets ({@link BitmapSubtitleDecoder}) and files
 * ({@link Subtitles#readBitmaps}), the caption packets of teletext, CEA-608 and CEA-708 ({@link SubtitleDecoder}
 * with a service chosen by the options byte) and the closed captions of video packets ({@link CaptionExtractor}).
 */
class SubtitleFuzzTest {
    /** The codecs of the cue target, chosen by the options byte modulo their count. */
    private static final Codec[] CODECS = {Codec.SUBRIP, Codec.WEBVTT, Codec.MOV_TEXT, Codec.ASS, Codec.TTML};

    /** The codecs of the bitmap target, chosen by the options byte modulo their count. */
    private static final Codec[] BITMAPS = {Codec.PGS, Codec.DVB_SUBTITLE, Codec.DVD_SUBTITLE};

    /** The codecs of the caption target, chosen by the options byte modulo their count. */
    private static final Codec[] CAPTIONS = {Codec.DVB_TELETEXT, Codec.CEA_608, Codec.CEA_708};

    /** The video codecs of the closed caption target, chosen by the options byte modulo their count. */
    private static final Codec[] VIDEO = {Codec.H264, Codec.HEVC, Codec.MPEG2_VIDEO, Codec.AV1};

    /** The most bitmap cues rendered per input. */
    private static final int MAX_RENDERS = 8;

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
            run.value("cue " + index, () -> decoder.decodeAll(packet)).ifPresent(cues -> cues.stream().limit(MAX_CUES)
                    .forEach(cue -> convert(run, cue)));
            for (var markup : SubtitleMarkup.values()) {
                run.step("cue " + index + " as " + markup, () -> decoder.decodeAll(packet, markup));
            }
        }
    }

    @FuzzTest
    void bitmaps(byte[] data) {
        var run = Fuzz.start("bitmaps");
        var input = FuzzPackets.parse(data, 1);
        var codec = BITMAPS[input.header().options() % BITMAPS.length];
        var track = DataTrack.builder().id(1).codec(codec).timeBase(Rational.timeBaseOf(90_000));
        input.header().config().ifPresent(track::codecConfig);
        var decoder = new BitmapSubtitleDecoder(track.build(), Fuzz.LIMITS);
        var rendered = new int[1];
        for (var index = 0; index < input.packets().size(); index++) {
            var part = input.packets().get(index);
            var packet = new Packet(1, part.data(), index * 9000L, index * 9000L, Packet.UNKNOWN_DURATION, true);
            run.value("packet " + index, () -> decoder.decode(packet)).ifPresent(cues -> render(run, cues, rendered));
        }
        run.value("flush", decoder::flush).ifPresent(cues -> render(run, cues, rendered));
    }

    @FuzzTest
    void readBitmaps(byte[] data) {
        var run = Fuzz.start("readBitmaps");
        var rendered = new int[1];
        run.value("read", () -> Subtitles.readBitmaps(MediaSource.of(data), SubtitleReaderOptions.builder()
                .limits(Fuzz.LIMITS).build())).ifPresent(cues -> render(run, cues, rendered));
    }

    @FuzzTest
    void captions(byte[] data) {
        var run = Fuzz.start("captions");
        var input = FuzzPackets.parse(data, 1);
        var options = input.header().options();
        var codec = CAPTIONS[options % CAPTIONS.length];
        var builder = DataTrack.builder().id(1).codec(codec).timeBase(new Rational(1001, 30000));
        input.header().config().ifPresent(builder::codecConfig);
        var track = builder.build();
        var channel = CaptionService.cea608(1 + (options >> 2 & 3));
        var service = switch (codec) {
            case CEA_608 -> channel;
            case CEA_708 -> (options & 0x10) != 0 ? CaptionService.cea708(1 + (options >> 5 & 7)) : channel;
            default -> CaptionService.defaultFor(track);
        };
        var decoder = new SubtitleDecoder(track, service);
        for (var index = 0; index < input.packets().size(); index++) {
            var part = input.packets().get(index);
            var packet = new Packet(1, part.data(), index, index, 1, true);
            run.value("packet " + index, () -> decoder.decodeAll(packet)).ifPresent(cues -> cues.stream()
                    .limit(MAX_CUES).forEach(cue -> convert(run, cue)));
        }
        run.value("flush", decoder::flush).ifPresent(cues -> cues.forEach(cue -> convert(run, cue)));
    }

    @FuzzTest
    void closedCaptions(byte[] data) {
        var run = Fuzz.start("closedCaptions");
        var input = FuzzPackets.parse(data, 1);
        var codec = VIDEO[input.header().options() % VIDEO.length];
        var builder = VideoTrack.builder().id(1).codec(codec).timeBase(new Rational(1001, 30000)).width(16)
                .height(16);
        input.header().config().ifPresent(builder::codecConfig);
        var extractor = new CaptionExtractor(builder.build());
        var decoder = new SubtitleDecoder(extractor.track());
        for (var index = 0; index < input.packets().size(); index++) {
            var part = input.packets().get(index);
            var packet = new Packet(1, part.data(), index, index, 1, part.keyframe());
            var label = "captions " + index;
            run.value("packet " + index, () -> extractor.extract(packet)).ifPresent(captions -> captions.forEach(
                    caption -> run.value(label, () -> decoder.decodeAll(caption))
                            .ifPresent(cues -> cues.forEach(cue -> convert(run, cue)))));
        }
        for (var caption : extractor.flush()) {
            run.value("flushed captions", () -> decoder.decodeAll(caption)).ifPresent(cues -> cues.forEach(
                    cue -> convert(run, cue)));
        }
        run.value("flush", decoder::flush).ifPresent(cues -> cues.forEach(cue -> convert(run, cue)));
    }

    private static void render(FuzzRun run, List<BitmapCue> cues, int[] rendered) {
        for (var cue : cues) {
            run.output();
            if (rendered[0]++ < MAX_RENDERS) {
                run.step("render", cue::render);
            }
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
