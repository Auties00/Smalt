package com.github.auties00.smalt.container.packaging;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.ts.TsDemuxer;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.validate.Validators;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.DataTrack;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.TrackKind;
import com.github.auties00.smalt.track.VideoTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static com.github.auties00.smalt.container.packaging.Presentations.Input;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Packages a ladder of two variants with two alternate audio languages and three subtitle renditions (SubRip, WebVTT
 * with settings, forced, and TTML), and audio-only presentations, into HLS and DASH; checks them with the test kit's
 * validators (RFC 8216 rendition groups and WebVTT segments, the DASH schema and text adaptation sets), with ffmpeg
 * and ffprobe reading the manifests, and by reading them back with the HLS and DASH demuxers and comparing every
 * packet and cue with the inputs, on one timeline across streams.
 */
@DisplayName("Packager renditions: alternate audio and subtitles")
class PackagerRenditionsTest {
    private static Path high() {
        return Presentations.video("320x240", "400k", 30);
    }

    private static Path low() {
        return Presentations.video("160x120", "150k", 30);
    }

    private static List<Input> ladder(boolean ttml) {
        var inputs = new ArrayList<>(List.of(
                new Input(high(), builder -> builder.name("high")),
                new Input(low(), builder -> builder.name("low")),
                new Input(Presentations.audio(440, "eng"), builder -> builder.kind(Rendition.Kind.AUDIO).name("en")
                        .label("English").defaultRendition(true)),
                new Input(Presentations.audio(660, "spa"), builder -> builder.kind(Rendition.Kind.AUDIO).name("es")
                        .label("Espanol")),
                new Input(Presentations.text("subtitles.srt", Presentations.SRT), builder -> builder
                        .kind(Rendition.Kind.SUBTITLES).name("sub_en").language("en").label("English CC")),
                new Input(Presentations.text("subtitles.vtt", Presentations.VTT), builder -> builder
                        .kind(Rendition.Kind.SUBTITLES).name("sub_fr").language("fr").label("Francais").forced(true))));
        if (ttml) {
            inputs.add(new Input(Presentations.text("subtitles.ttml", Presentations.TTML), builder -> builder
                    .kind(Rendition.Kind.SUBTITLES).name("sub_de").label("Deutsch")));
        }
        return inputs;
    }

    private static PackagingOptions options(PackagingOptions preset) {
        return preset.toBuilder().segmentMicros(2_000_000).build();
    }

    @Nested
    @DisplayName("HLS with transport stream segments")
    class TransportStream {
        @Test
        @DisplayName("writes one audio group, one subtitle group and video-only variants that name both")
        void groups() {
            var directory = Presentations.directory("renditions-ts");
            var result = Presentations.pack(directory, options(PackagingOptions.hls()), ladder(false));
            var master = directory.resolve(result.masterPlaylist().orElseThrow());
            Validators.assertValidHls(master);
            var text = Presentations.text(master);
            assertTrue(text.contains("#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio\",NAME=\"English\",LANGUAGE=\"en\","
                    + "DEFAULT=YES,AUTOSELECT=YES,CHANNELS=\"2\",URI=\"en.m3u8\""), text);
            assertTrue(text.contains("#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio\",NAME=\"Espanol\",LANGUAGE=\"es\","
                    + "DEFAULT=NO,AUTOSELECT=YES,CHANNELS=\"2\",URI=\"es.m3u8\""), text);
            assertTrue(text.contains("#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"subs\",NAME=\"English CC\","
                    + "LANGUAGE=\"en\",DEFAULT=NO,AUTOSELECT=YES,URI=\"sub_en.m3u8\""), text);
            assertTrue(text.contains("NAME=\"Francais\",LANGUAGE=\"fr\",DEFAULT=NO,AUTOSELECT=YES,FORCED=YES,"
                    + "URI=\"sub_fr.m3u8\""), text);
            var variants = Pattern.compile("#EXT-X-STREAM-INF:([^\n]*)\n([^\n]*)").matcher(text);
            var count = 0;
            while (variants.find()) {
                count++;
                assertTrue(variants.group(1).contains("AUDIO=\"audio\",SUBTITLES=\"subs\""), variants.group(1));
                assertTrue(variants.group(1).contains(",mp4a.40.2\""), variants.group(1));
            }
            assertEquals(2, count, text);
            // the variants carry video alone, the audio renditions audio alone
            for (var name : List.of("high_00001.ts", "en_00001.ts")) {
                try (var source = MediaSource.of(directory.resolve(name)); var demuxer = new TsDemuxer(source)) {
                    assertEquals(1, demuxer.tracks().size(), name);
                    assertEquals(name.startsWith("high") ? TrackKind.VIDEO : TrackKind.AUDIO,
                            demuxer.tracks().getFirst().kind(), name);
                }
            }
            assertEquals(List.of(Rendition.Kind.VARIANT, Rendition.Kind.VARIANT, Rendition.Kind.AUDIO,
                    Rendition.Kind.AUDIO, Rendition.Kind.SUBTITLES, Rendition.Kind.SUBTITLES),
                    result.streams().stream().map(PackagedStream::renditionKind).toList());
            assertEquals(PackagedStream.Format.WEBVTT, result.streams().get(4).format());
        }

        @Test
        @DisplayName("cuts WebVTT segments at the variants' segments, maps them to the 90 kHz timeline and repeats "
                + "spanning cues")
        void webVttSegments() {
            var directory = Presentations.directory("renditions-vtt");
            Presentations.pack(directory, options(PackagingOptions.hls()), ladder(false));
            var video = Presentations.text(directory.resolve("high.m3u8"));
            var subtitles = Presentations.text(directory.resolve("sub_en.m3u8"));
            assertEquals(extinf(video), extinf(subtitles), "the subtitle segments span the video segments");
            var segments = new ArrayList<String>();
            for (var index = 1; Files.isRegularFile(directory.resolve(String.format("sub_en_%05d.vtt", index)));
                 index++) {
                segments.add(Presentations.text(directory.resolve(String.format("sub_en_%05d.vtt", index))));
            }
            assertEquals(extinf(video).size(), segments.size());
            for (var segment : segments) {
                // media time zero is the 700 ms multiplexing delay of the transport stream segments
                assertTrue(segment.startsWith("WEBVTT\nX-TIMESTAMP-MAP=MPEGTS:63000,LOCAL:00:00:00.000\n\n"), segment);
            }
            var spanning = "00:00:01.800 --> 00:00:04.400\nThis cue spans two segment boundaries";
            assertTrue(segments.get(0).contains(spanning) && segments.get(1).contains(spanning)
                    && segments.get(2).contains(spanning), "the spanning cue is in every segment it is shown in");
            assertFalse(segments.get(0).contains("Bye"), segments.get(0));
            assertTrue(segments.get(2).contains("00:00:05.000 --> 00:00:05.900\n<i>Bye</i>"), segments.get(2));
            var french = Presentations.text(directory.resolve("sub_fr_00001.vtt"));
            assertTrue(french.contains("00:00:00.400 --> 00:00:03.000 line:0 align:start\nBonjour"), french);
        }

        @Test
        @DisplayName("is read back by ffmpeg without a warning, every audio rendition, and every WebVTT segment")
        void ffmpeg() {
            var directory = Presentations.directory("renditions-ffmpeg");
            var result = Presentations.pack(directory, options(PackagingOptions.hls()), ladder(false));
            var master = directory.resolve(result.masterPlaylist().orElseThrow());
            var warnings = Presentations.ffmpeg(master);
            assertTrue(warnings.isBlank(), warnings);
            var codecs = Presentations.probe(master);
            // ffmpeg lists the streams of the renditions under every variant that names them
            assertTrue(codecs.stream().filter("h264"::equals).count() >= 2, codecs.toString());
            assertTrue(codecs.stream().filter("aac"::equals).count() >= 2, codecs.toString());
            // ffmpeg's HLS demuxer skips subtitle renditions, so it reads each WebVTT segment as a file of its own
            for (var name : result.files()) {
                if (!name.endsWith(".vtt")) {
                    continue;
                }
                var segment = directory.resolve(name);
                assertEquals(List.of("webvtt"), Presentations.probe(segment), name);
                var cues = Presentations.text(segment).split("-->", -1).length - 1;
                assertEquals(cues, Presentations.packets(segment), name);
            }
        }

        @Test
        @DisplayName("reads back through the HLS demuxer: every packet and cue of every rendition, on one timeline")
        void readBack() {
            var directory = Presentations.directory("renditions-readback-ts");
            var inputs = ladder(false);
            var result = Presentations.pack(directory, options(PackagingOptions.hls()), inputs);
            assertReadBack(directory.resolve(result.masterPlaylist().orElseThrow()), inputs, false, true);
        }
    }

    @Nested
    @DisplayName("HLS and DASH over CMAF segments")
    class Cmaf {
        @Test
        @DisplayName("writes text adaptation sets, labeled audio adaptation sets with roles, and valid manifests")
        void manifests() {
            var directory = Presentations.directory("renditions-cmaf");
            var result = Presentations.pack(directory, options(PackagingOptions.cmaf()), ladder(true));
            Validators.assertValidHls(directory.resolve(result.masterPlaylist().orElseThrow()));
            var manifest = directory.resolve(result.manifest().orElseThrow());
            Validators.assertValidDash(manifest);
            var mpd = Presentations.text(manifest);
            assertTrue(mpd.contains("contentType=\"audio\" mimeType=\"audio/mp4\" startWithSAP=\"1\" lang=\"en\">\n"
                    + "      <Label>English</Label>\n"
                    + "      <Role schemeIdUri=\"urn:mpeg:dash:role:2011\" value=\"main\"/>"), mpd);
            assertTrue(mpd.contains("lang=\"es\">\n      <Label>Espanol</Label>\n"
                    + "      <Role schemeIdUri=\"urn:mpeg:dash:role:2011\" value=\"alternate\"/>"), mpd);
            assertTrue(mpd.contains("contentType=\"text\" mimeType=\"application/mp4\" startWithSAP=\"1\" lang=\"en\">"
                    + "\n      <Label>English CC</Label>\n"
                    + "      <Role schemeIdUri=\"urn:mpeg:dash:role:2011\" value=\"subtitle\"/>"), mpd);
            assertTrue(mpd.contains("value=\"forced-subtitle\""), mpd);
            assertTrue(mpd.contains("codecs=\"wvtt\""), mpd);
            assertTrue(mpd.contains("codecs=\"stpp.ttml.im1t\""), mpd);
            assertEquals(1, mpd.split("contentType=\"video\"", -1).length - 1, mpd);
            assertTrue(mpd.contains("<Representation id=\"high_video\"")
                    && mpd.contains("<Representation id=\"low_video\""), mpd);
            assertFalse(mpd.contains("high_audio"), "variants carry no audio next to audio renditions");
            // the HLS playlists name WebVTT segments, the DASH manifest the CMAF text tracks
            assertTrue(Files.isRegularFile(directory.resolve("sub_de_00001.vtt")));
            assertTrue(Files.isRegularFile(directory.resolve("sub_de_text_init.mp4")));
        }

        @Test
        @DisplayName("is read by ffmpeg through both manifests without a warning")
        void ffmpeg() {
            var directory = Presentations.directory("renditions-cmaf-ffmpeg");
            var result = Presentations.pack(directory, options(PackagingOptions.cmaf()), ladder(false));
            var hls = Presentations.ffmpeg(directory.resolve(result.masterPlaylist().orElseThrow()));
            assertTrue(hls.isBlank(), hls);
            var codecs = Presentations.probe(directory.resolve(result.manifest().orElseThrow()));
            assertTrue(codecs.contains("h264") && codecs.contains("aac"), codecs.toString());
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"hls", "dash"})
        @DisplayName("reads back through the HLS and DASH demuxers: every packet and cue, byte for byte, one timeline")
        void readBack(String manifest) {
            var directory = Presentations.directory("renditions-readback-" + manifest);
            var inputs = ladder(true);
            var result = Presentations.pack(directory, options(PackagingOptions.cmaf()), inputs);
            var file = directory.resolve(manifest.equals("hls") ? result.masterPlaylist().orElseThrow()
                    : result.manifest().orElseThrow());
            assertReadBack(file, inputs, manifest.equals("dash"), false);
        }
    }

    @Nested
    @DisplayName("audio-only presentations")
    class AudioOnly {
        @Test
        @DisplayName("list one variant per audio group, naming its default rendition and the subtitles")
        void hls() {
            var directory = Presentations.directory("renditions-audio-only");
            var result = Presentations.pack(directory, options(PackagingOptions.hlsCmaf()), List.of(
                    new Input(Presentations.audio(440, "eng"), builder -> builder.kind(Rendition.Kind.AUDIO).name("en")),
                    new Input(Presentations.audio(660, "spa"), builder -> builder.kind(Rendition.Kind.AUDIO).name("es")
                            .defaultRendition(true)),
                    new Input(Presentations.text("lyrics.srt", Presentations.SRT), builder -> builder
                            .kind(Rendition.Kind.SUBTITLES).name("lyrics").language("en"))));
            var master = directory.resolve(result.masterPlaylist().orElseThrow());
            Validators.assertValidHls(master);
            var text = Presentations.text(master);
            assertTrue(text.contains("NAME=\"es\",LANGUAGE=\"es\",DEFAULT=YES"), text);
            assertTrue(Pattern.compile("#EXT-X-STREAM-INF:BANDWIDTH=\\d+,AVERAGE-BANDWIDTH=\\d+,CODECS=\"mp4a.40.2\","
                    + "AUDIO=\"audio\",SUBTITLES=\"subs\"\nes_audio.m3u8\n").matcher(text).find(), text);
            var warnings = Presentations.ffmpeg(master);
            assertTrue(warnings.isBlank(), warnings);
        }

        @Test
        @DisplayName("write a DASH manifest of the audio and text adaptation sets alone")
        void dash() {
            var directory = Presentations.directory("renditions-audio-only-dash");
            var result = Presentations.pack(directory, options(PackagingOptions.dash()), List.of(
                    new Input(Presentations.audio(440, "eng"), builder -> builder.kind(Rendition.Kind.AUDIO).name("en")),
                    new Input(Presentations.text("lyrics.srt", Presentations.SRT), builder -> builder
                            .kind(Rendition.Kind.SUBTITLES).name("lyrics").language("en"))));
            var manifest = directory.resolve(result.manifest().orElseThrow());
            Validators.assertValidDash(manifest);
            try (var demuxer = Presentations.dash(manifest)) {
                assertEquals(List.of(Codec.AAC, Codec.WEBVTT), demuxer.tracks().stream().map(track -> track.codec())
                        .toList());
            }
        }
    }

    @Nested
    @DisplayName("the application's packets")
    class Writer {
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"hls", "cmaf"})
        @DisplayName("package to the same bytes as the packaging of the files")
        void sameAsFiles(String format) {
            // the application's tracks carry no tags, so the files' are left out too
            var options = options(format.equals("hls") ? PackagingOptions.hls() : PackagingOptions.cmaf())
                    .toBuilder().noTags().build();
            var inputs = List.of(new Input(high(), builder -> builder.name("main")),
                    new Input(Presentations.audio(660, "spa"), builder -> builder.kind(Rendition.Kind.AUDIO).name("es")),
                    new Input(Presentations.text("writer.srt", Presentations.SRT), builder -> builder
                            .kind(Rendition.Kind.SUBTITLES).name("subs").language("en")));
            var files = Presentations.directory("writer-files-" + format);
            var expected = Presentations.pack(files, options, inputs);
            var written = Presentations.directory("writer-packets-" + format);
            var streams = new ArrayList<Presentations.Stream>();
            var renditions = new ArrayList<Rendition>();
            for (var index = 0; index < inputs.size(); index++) {
                var input = inputs.get(index);
                var kind = index == 0 ? TrackKind.VIDEO : index == 1 ? TrackKind.AUDIO : TrackKind.DATA;
                var stream = Presentations.source(input.file(), kind);
                streams.add(stream);
                var tracks = new ArrayList<com.github.auties00.smalt.track.Track>(List.of(stream.track()));
                if (index == 0) {
                    tracks.add(Presentations.source(input.file(), TrackKind.AUDIO).track());
                }
                var builder = Rendition.builder(tracks);
                input.settings().accept(builder);
                renditions.add(builder.build());
            }
            PackagingResult actual;
            try (var writer = Packager.open(renditions, PackageOutput.directory(written), options)) {
                // interleave the renditions by decoding time, as a live source would
                var cursors = new int[streams.size()];
                while (true) {
                    var next = -1;
                    var best = Long.MAX_VALUE;
                    for (var index = 0; index < streams.size(); index++) {
                        var packets = streams.get(index).packets();
                        if (cursors[index] < packets.size()) {
                            var packet = packets.get(cursors[index]);
                            var time = streams.get(index).track().timeBase().toMicros(packet.dts());
                            if (time < best) {
                                best = time;
                                next = index;
                            }
                        }
                    }
                    if (next < 0) {
                        break;
                    }
                    writer.writePacket(renditions.get(next).name().orElseThrow(),
                            streams.get(next).packets().get(cursors[next]++));
                }
                actual = writer.finish();
                assertThrows(IllegalStateException.class, writer::finish);
            }
            // the files complete in an order that depends on how the renditions interleave
            assertEquals(expected.files().stream().sorted().toList(), actual.files().stream().sorted().toList());
            for (var name : expected.files()) {
                assertArrayEquals(Presentations.read(files.resolve(name)), Presentations.read(written.resolve(name)),
                        name);
            }
        }

        @Test
        @DisplayName("rejects unknown renditions and packets after the end")
        void misuse() {
            var video = VideoTrack.builder().id(1).codec(Codec.H264).timeBase(Rational.timeBaseOf(90_000)).width(320)
                    .height(240).codecConfig(new byte[]{1, 0x64, 0, 0x1F, (byte) 0xFF, (byte) 0xE0, 0}).build();
            var writer = Packager.open(List.of(Rendition.builder(List.of(video)).name("v").build()),
                    name -> MediaSink.memory(), PackagingOptions.hls());
            assertThrows(IllegalArgumentException.class, () -> writer.writePacket("w",
                    new com.github.auties00.smalt.track.Packet(1, new byte[1], 0, 0, 1, true)));
            writer.close();
            assertThrows(IllegalStateException.class, () -> writer.writePacket("v",
                    new com.github.auties00.smalt.track.Packet(1, new byte[1], 0, 0, 1, true)));
            writer.close();
        }
    }

    @Nested
    @DisplayName("options and renditions")
    class Validation {
        @Test
        @DisplayName("reject renditions whose settings do not fit their kind")
        void renditions() {
            var source = MediaSource.of(new byte[0]);
            assertThrows(IllegalArgumentException.class, () -> Rendition.builder(source).trackId(1).build());
            assertThrows(IllegalArgumentException.class, () -> Rendition.builder(source).defaultRendition(true).build());
            assertThrows(IllegalArgumentException.class, () -> Rendition.builder(source).kind(Rendition.Kind.AUDIO)
                    .forced(true).build());
            assertThrows(IllegalArgumentException.class, () -> Rendition.builder(source).kind(Rendition.Kind.AUDIO)
                    .label("a \"quoted\" label").build());
            assertThrows(IllegalArgumentException.class, () -> Rendition.builder(source).kind(Rendition.Kind.AUDIO)
                    .language("en us").build());
            assertThrows(IllegalArgumentException.class, () -> Rendition.builder(source).trackId(-1));
            assertThrows(IllegalArgumentException.class, () -> Rendition.builder(List.of()));
            var audio = AudioTrack.builder().id(1).codec(Codec.AAC).timeBase(Rational.timeBaseOf(48_000))
                    .sampleRate(48_000).build();
            assertThrows(IllegalArgumentException.class, () -> Rendition.builder(List.of(audio, audio)));
            var rendition = Rendition.builder(source).kind(Rendition.Kind.SUBTITLES).name("s").language("pt-BR")
                    .label("Portugues").defaultRendition(true).forced(true).trackId(3).build();
            assertEquals(rendition, rendition.toBuilder().build());
            assertEquals(3, rendition.trackId().orElseThrow());
            assertEquals(Rendition.Kind.VARIANT, Rendition.of(source).kind());
            assertEquals(Rendition.Kind.AUDIO, Rendition.ofAudio("a", source).kind());
            assertEquals(Rendition.Kind.SUBTITLES, Rendition.ofSubtitles("s", source).kind());
        }

        @Test
        @DisplayName("reject presentations without a playable rendition, with duplicate labels or colliding files")
        void presentations() {
            var source = MediaSource.of(new byte[0]);
            var sink = (PackageOutput) name -> MediaSink.memory();
            assertThrows(IllegalArgumentException.class, () -> Packager.pack(List.of(Rendition.ofSubtitles("s", source)),
                    sink, PackagingOptions.hls()));
            assertThrows(IllegalArgumentException.class, () -> Packager.pack(List.of(
                    Rendition.builder(source).kind(Rendition.Kind.AUDIO).name("a").label("X").build(),
                    Rendition.builder(source).kind(Rendition.Kind.AUDIO).name("b").label("X").build()), sink,
                    PackagingOptions.hls()));
            var audio = AudioTrack.builder().id(1).codec(Codec.AAC).timeBase(Rational.timeBaseOf(48_000))
                    .sampleRate(48_000).layout(ChannelLayout.STEREO).codecConfig(new byte[]{0x11, (byte) 0x90}).build();
            assertThrows(IllegalArgumentException.class, () -> Packager.pack(List.of(Rendition.builder(List.of(audio))
                    .build()), sink, PackagingOptions.hls()));
            assertThrows(IllegalArgumentException.class, () -> Packager.open(List.of(Rendition.of(source)), sink,
                    PackagingOptions.hls()));
            // a variant's I-frame playlist and an audio rendition's playlist would share a name
            var video = VideoTrack.builder().id(2).codec(Codec.H264).timeBase(Rational.timeBaseOf(90_000)).width(320)
                    .height(240).codecConfig(new byte[]{1, 0x64, 0, 0x1F, (byte) 0xFF, (byte) 0xE0, 0}).build();
            assertThrows(IllegalArgumentException.class, () -> Packager.open(List.of(
                    Rendition.builder(List.of(video)).name("a").build(),
                    Rendition.builder(List.of(audio)).kind(Rendition.Kind.AUDIO).name("a_iframes").build()), sink,
                    PackagingOptions.hls().toBuilder().trickPlay(true).build()));
        }

        @Test
        @DisplayName("report a variant without video next to audio renditions, and a subtitle rendition without text")
        void tracks() {
            try (var tone = MediaSource.of(Presentations.audio(440, "eng"))) {
                assertThrows(MediaException.Unsupported.class, () -> Packager.pack(List.of(Rendition.of("v", tone),
                        Rendition.ofAudio("a", tone)), name -> MediaSink.memory(), PackagingOptions.hls()));
                assertThrows(MediaException.Unsupported.class, () -> Packager.pack(List.of(Rendition.ofAudio("a", tone),
                        Rendition.ofSubtitles("s", tone)), name -> MediaSink.memory(), PackagingOptions.hls()));
                assertThrows(MediaException.Unsupported.class, () -> Packager.pack(List.of(Rendition.builder(tone)
                        .kind(Rendition.Kind.AUDIO).name("a").trackId(99).build()), name -> MediaSink.memory(),
                        PackagingOptions.hls()));
            }
        }

        @Test
        @DisplayName("keep a declared language on the track the segments carry")
        void language() {
            var directory = Presentations.directory("renditions-language");
            Presentations.pack(directory, options(PackagingOptions.dash()), List.of(
                    new Input(Presentations.audio(440, "eng"), builder -> builder.kind(Rendition.Kind.AUDIO).name("a")
                            .language("pt-BR"))));
            try (var source = MediaSource.of(Presentations.read(directory.resolve("a_audio_init.mp4")));
                 var demuxer = Containers.openDemuxer(source)) {
                assertEquals("por", demuxer.tracks().getFirst().language().orElseThrow());
            }
            assertTrue(Presentations.text(directory.resolve("manifest.mpd")).contains("lang=\"pt-BR\""));
        }

        @Test
        @DisplayName("declare an ISO 639-2 rendition language as its RFC 5646 tag")
        void languageCode() {
            var directory = Presentations.directory("renditions-language-code");
            var result = Presentations.pack(directory, options(PackagingOptions.cmaf()), List.of(
                    new Input(Presentations.video("320x240", "400k", 30), builder -> builder.name("v")),
                    new Input(Presentations.audio(440, "eng"), builder -> builder.kind(Rendition.Kind.AUDIO).name("a")
                            .language("fre-CA")),
                    new Input(Presentations.audio(550, "eng"), builder -> builder.kind(Rendition.Kind.AUDIO).name("b")
                            .language("ger"))));
            var master = Presentations.text(directory.resolve(result.masterPlaylist().orElseThrow()));
            assertTrue(master.contains("NAME=\"a\",LANGUAGE=\"fr-CA\""), master);
            assertTrue(master.contains("NAME=\"b\",LANGUAGE=\"de\""), master);
            var mpd = Presentations.text(directory.resolve(result.manifest().orElseThrow()));
            assertTrue(mpd.contains("lang=\"fr-CA\"") && mpd.contains("lang=\"de\""), mpd);
        }
    }

    /** Returns the EXTINF durations of a media playlist. */
    private static List<String> extinf(String playlist) {
        var durations = new ArrayList<String>();
        var matcher = Pattern.compile("#EXTINF:([0-9.]+),").matcher(playlist);
        while (matcher.find()) {
            durations.add(matcher.group(1));
        }
        return durations;
    }

    /**
     * Reads a presentation back and compares each of its tracks with its input: video packets one for one (the same
     * bytes in CMAF; transport stream segments convert them to Annex B), audio packets byte for byte, cues by text
     * and time, all on the timeline of the first variant's video.
     */
    private static void assertReadBack(Path manifest, List<Input> inputs, boolean dash, boolean transportStream) {
        var expected = new ArrayList<Presentations.Stream>();
        var kinds = new ArrayList<TrackKind>();
        for (var input : inputs) {
            var builder = Rendition.builder(MediaSource.of(new byte[0]));
            input.settings().accept(builder);
            var kind = switch (builder.build().kind()) {
                case VARIANT -> TrackKind.VIDEO;
                case AUDIO -> TrackKind.AUDIO;
                case SUBTITLES -> TrackKind.DATA;
            };
            kinds.add(kind);
            expected.add(Presentations.source(input.file(), kind));
        }
        try (var demuxer = dash ? Presentations.dash(manifest) : Presentations.hls(manifest)) {
            var read = Presentations.streams(demuxer);
            assertEquals(inputs.size(), read.size(), "one track per rendition: " + demuxer.tracks());
            var videoOrigin = read.getFirst().startMicros() - expected.getFirst().startMicros();
            for (var index = 0; index < inputs.size(); index++) {
                var what = "rendition " + index + " (" + read.get(index).track().codec() + ")";
                if (kinds.get(index) == TrackKind.DATA) {
                    var input = Presentations.cues(inputs.get(index).file());
                    Presentations.assertSameCues(what, input, read.get(index).cues(), videoOrigin);
                    continue;
                }
                assertEquals(expected.get(index).track().codec(), read.get(index).track().codec(), what);
                Presentations.assertSamePackets(what, expected.get(index), read.get(index), videoOrigin,
                        kinds.get(index) == TrackKind.AUDIO || !transportStream);
            }
            var titles = demuxer.trackMetadata(read.get(2).track().id());
            assertEquals("English", titles.get("title"));
            assertEquals("eng", read.get(2).track().language().orElseThrow());
            assertTrue(demuxer.trackDisposition(read.get(2).track().id())
                    .contains(com.github.auties00.smalt.track.TrackDisposition.DEFAULT));
            assertTrue(demuxer.trackDisposition(read.get(5).track().id())
                    .contains(com.github.auties00.smalt.track.TrackDisposition.FORCED));
            assertTrue(read.get(4).track() instanceof DataTrack);
        }
    }
}
