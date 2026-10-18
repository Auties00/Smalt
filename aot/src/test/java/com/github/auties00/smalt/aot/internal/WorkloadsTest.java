package com.github.auties00.smalt.aot.internal;

import com.github.auties00.smalt.aot.TrainingFamily;
import com.github.auties00.smalt.aot.TrainingMode;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.raw.RawDecoder;
import com.github.auties00.smalt.image.raw.RawFormat;
import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Creates every workload of every family and runs two rounds of each, so that a codec API change
 * that breaks a workload fails here rather than as a skipped workload in a user's training run.
 */
class WorkloadsTest {
    @ParameterizedTest(name = "{0}")
    @EnumSource(TrainingFamily.class)
    @DisplayName("every workload of a family is created under its factory's name and runs its rounds")
    void runs(TrainingFamily family) throws Exception {
        var factories = Workloads.of(family);
        assertFalse(factories.isEmpty());
        for (var factory : factories) {
            var workload = factory.create();
            assertEquals(factory.name(), workload.name());
            workload.round();
            workload.round();
        }
    }

    @Test
    @DisplayName("the real-time workloads cover every artifact with real-time codecs and no other")
    void realtimeModes() {
        var realtime = EnumSet.of(TrainingFamily.IMAGE, TrainingFamily.AUDIO, TrainingFamily.VIDEO, TrainingFamily.MPEG,
                TrainingFamily.CAPTURE);
        for (var family : TrainingFamily.values()) {
            var hasRealtime = Workloads.of(family).stream().anyMatch(factory -> factory.mode() == TrainingMode.REALTIME);
            assertEquals(realtime.contains(family), hasRealtime, family.name());
        }
    }

    @Test
    @DisplayName("workload names are unique across families")
    void uniqueNames() {
        var names = new HashSet<String>();
        for (var family : TrainingFamily.values()) {
            for (var factory : Workloads.of(family)) {
                assertTrue(names.add(factory.name()), factory.name());
            }
        }
    }

    @Test
    @DisplayName("synthetic video is deterministic and has a flat band and black frames")
    void syntheticVideo() {
        var first = SyntheticVideo.natural(64, 32, 6);
        var second = SyntheticVideo.natural(64, 32, 6);
        for (var t = 0; t < first.size(); t++) {
            assertArrayEquals(first.get(t).y(), second.get(t).y());
        }
        assertEquals((byte) 128, first.getFirst().y()[0]);
        var black = first.get(5);
        assertEquals((byte) 16, black.y()[black.yStride() * 31 + 63]);
        for (var kind = 0; kind < SyntheticVideo.CONTENT_CLASSES; kind++) {
            List<YuvPicture> clip = SyntheticVideo.content(kind, 64, 32, 3);
            assertEquals(3, clip.size());
        }
    }

    @Test
    @DisplayName("synthetic speech and music are deterministic and normalized")
    void syntheticAudio() {
        var speech = SyntheticAudio.speech(16000, 1);
        assertArrayEquals(speech, SyntheticAudio.speech(16000, 1));
        assertEquals(16000, speech.length);
        var peak = 0f;
        for (var sample : speech) {
            peak = Math.max(peak, Math.abs(sample));
        }
        assertEquals(0.5f, peak, 1e-6f);
        var music = SyntheticAudio.music(8000, 1);
        assertEquals(2, music.length);
        assertEquals(8000, music[1].length);
    }

    @Test
    @DisplayName("synthetic MRW, CRW and X3F files are decodable files of their formats")
    void syntheticVendorRaw() {
        var picture = SyntheticImages.rgba(SyntheticVideo.natural(64, 48, 1).getFirst(), false);
        var files = List.of(SyntheticVendorRaw.mrw(picture, true), SyntheticVendorRaw.mrw(picture, false),
                SyntheticVendorRaw.crw(picture), SyntheticVendorRaw.x3f(picture, false),
                SyntheticVendorRaw.x3f(picture, true));
        var formats = List.of(RawFormat.MRW, RawFormat.MRW, RawFormat.CRW, RawFormat.X3F, RawFormat.X3F);
        // the image areas: MRW 8 photosites smaller, CRW without its masked columns, X3F its active area
        var sizes = new int[][]{{56, 40}, {56, 40}, {56, 44}, {60, 44}, {60, 44}};
        for (var index = 0; index < files.size(); index++) {
            var info = RawDecoder.info(MediaSource.of(files.get(index)));
            assertEquals(formats.get(index), info.format());
            assertTrue(info.isDecodable(), info.unsupportedFeature().orElse(""));
            assertEquals(sizes[index][0], info.width(), formats.get(index).name());
            assertEquals(sizes[index][1], info.height(), formats.get(index).name());
            var raw = RawDecoder.decodeRaw(MediaSource.of(files.get(index)));
            assertEquals(info.width(), raw.width());
        }
        assertArrayEquals(RawDecoder.decodeRaw(MediaSource.of(files.get(0))).samples(),
                RawDecoder.decodeRaw(MediaSource.of(files.get(1))).samples());
    }
}
