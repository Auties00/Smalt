package com.github.auties00.smalt.video.vp9.internal;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.YuvPicture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the good-quality VP9 loop directly on a clip without scene cuts and a short key frame
 * interval, so that the interval forces its key frames, and checks that they are coded where the
 * interval puts them, measured against the frame before them, and that every shown frame decodes.
 */
@DisplayName("good-quality VP9 loop")
class Vp9GoodEncoderTest {
    private static final int WIDTH = 96;

    private static final int HEIGHT = 64;

    private static final long TICKS = Vp9RtEncoder.TICKS_PER_SECOND / 30;

    @ParameterizedTest(name = "speed {0}, best {1}, two pass {2}")
    @CsvSource({"0, false, true", "2, false, true", "4, false, true", "4, false, false", "2, true, true",
            "2, true, false"})
    @DisplayName("key frames the interval forces are coded at the interval and the stream decodes")
    void forcedKeyFrames(int speed, boolean best, boolean twoPass) {
        var frames = 36;
        var interval = 12;
        List<YuvPicture> pictures = Vp9TestVideo.synthesize(WIDTH, HEIGHT, frames);
        double[] stats = null;
        if (twoPass) {
            var pass = new Vp9FirstPassRunner(WIDTH, HEIGHT, 1, 1, 8, 1);
            for (var picture : pictures) {
                pass.add(picture, TICKS);
            }
            stats = pass.records();
        }
        var encoder = new Vp9GoodEncoder(WIDTH, HEIGHT, 1, 1, 8, 0, 1, MediaLimits.DEFAULT);
        encoder.configure(new Vp9GoodEncoder.Settings(0, 250_000, 30, Vp9GoodEncoder.qindexOf(4),
                Vp9GoodEncoder.qindexOf(63), Vp9GoodEncoder.qindexOf(20), interval, 16, true, 5, 7, speed, best, stats,
                twoPass ? frames : 0));
        var packets = new ArrayList<Vp9GoodEncoder.Packet>();
        for (var i = 0; i < frames; i++) {
            encoder.encode(pictures.get(i), i * 3000L, 3000, TICKS, false, packets::add);
        }
        encoder.flush(packets::add);
        assertEquals(frames, packets.size());
        var keys = new ArrayList<Integer>();
        for (var i = 0; i < frames; i++) {
            if (packets.get(i).key()) {
                keys.add(i);
            }
        }
        assertEquals(0, keys.getFirst(), "first key frame");
        for (var k = 1; k < keys.size(); k++) {
            assertTrue(keys.get(k) - keys.get(k - 1) <= interval, "key frames " + keys);
        }
        assertTrue(keys.getLast() >= frames - interval, "key frames " + keys);
        if (twoPass) {
            assertTrue(encoder.forcedKeyFrames >= 2, "forced key frames " + encoder.forcedKeyFrames);
        } else {
            // libvpx codes every frame of a one-pass encode once (DISALLOW_RECODE)
            assertEquals(encoder.codedFrames, encoder.codings, "recodes in a one-pass encode");
        }
        var decoder = new Vp9FrameDecoder(MediaLimits.DEFAULT, false);
        for (var i = 0; i < frames; i++) {
            var data = packets.get(i).data();
            decoder.decodePacket(data, 0, data.length);
            assertNotNull(decoder.shownFrame(), "frame " + i);
        }
    }
}
