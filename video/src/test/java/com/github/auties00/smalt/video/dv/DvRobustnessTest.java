package com.github.auties00.smalt.video.dv;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoders;
import com.github.auties00.smalt.video.VideoFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Feeds the DV decoder truncated, foreign and corrupted frames: a frame that is not DIF or is cut short raises
 * {@link MediaException.Malformed}, and a frame whose compressed data is damaged still decodes to a full picture, as
 * the segment independence of DV allows. Also round trips synthetic pictures through Smalt's encoder and decoder
 * without ffmpeg.
 */
@DisplayName("DV decoder robustness")
class DvRobustnessTest {
    /** A gradient with a moving square, independent of ffmpeg. */
    private static Picture synthetic(DvProfile profile, int phase) {
        var chroma = profile == DvProfile.DV_625 ? ChromaFormat.YUV420 : ChromaFormat.YUV422;
        var color = profile.width() > 720 ? ColorInfo.BT709_LIMITED : ColorInfo.BT601_LIMITED;
        var picture = YuvPicture.allocate(profile.width(), profile.height(), chroma, false, color);
        for (var y = 0; y < profile.height(); y++) {
            for (var x = 0; x < profile.width(); x++) {
                var inside = Math.abs(x - 200 - 8 * phase) < 40 && Math.abs(y - 150) < 40;
                picture.y()[y * picture.yStride() + x] = (byte) (inside ? 230 : 16 + (x + y) % 200);
            }
        }
        for (var y = 0; y < picture.chromaHeight(); y++) {
            for (var x = 0; x < picture.chromaWidth(); x++) {
                picture.u()[y * picture.uvStride() + x] = (byte) (64 + x % 128);
                picture.v()[y * picture.uvStride() + x] = (byte) (64 + y % 128);
            }
        }
        return picture;
    }

    private static byte[] frame(DvProfile profile) {
        return DvTesting.encode(DvEncoderOptions.of(profile), List.of(synthetic(profile, 0))).getFirst().data();
    }

    private static Packet packet(byte[] data) {
        return new Packet(0, data, 0, 0, 1, true);
    }

    @ParameterizedTest
    @EnumSource(DvProfile.class)
    @DisplayName("round trips synthetic pictures through Smalt's encoder and decoder")
    void roundTrip(DvProfile profile) {
        var pictures = List.of(synthetic(profile, 0), synthetic(profile, 1));
        var packets = DvTesting.encode(DvEncoderOptions.of(profile), pictures);
        var decoder = new DvDecoder();
        for (var i = 0; i < packets.size(); i++) {
            var original = DvTesting.luma(pictures.get(i));
            var frames = new ArrayList<byte[]>();
            decoder.decode(packets.get(i), frame -> frames.add(DvTesting.luma(frame.picture())));
            assertEquals(1, frames.size());
            var psnr = com.github.auties00.smalt.video.intra.IntraTesting.psnr(original, frames.getFirst(), false, 255);
            assertTrue(psnr > 32, profile + " frame " + i + " luma PSNR " + psnr);
        }
    }

    @Test
    @DisplayName("rejects data that is not a DIF frame")
    void notDif() {
        var decoder = new DvDecoder();
        var random = new byte[120000];
        new Random(1).nextBytes(random);
        random[0] = (byte) 0xFF;
        assertThrows(MediaException.Malformed.class, () -> decoder.decode(packet(random), frame -> { }));
        assertThrows(MediaException.Malformed.class, () -> decoder.decode(packet(new byte[0]), frame -> { }));
        assertThrows(MediaException.Malformed.class, () -> decoder.decode(packet(new byte[100]), frame -> { }));
    }

    @ParameterizedTest
    @EnumSource(value = DvProfile.class, names = {"DV_525", "DVCPRO50_625", "DVCPRO_HD_1080I60",
            "DVCPRO_HD_720P50"})
    @DisplayName("rejects a frame cut short")
    void truncated(DvProfile profile) {
        var data = frame(profile);
        var decoder = new DvDecoder();
        for (var length : List.of(80 * 6, 12000, data.length / 2, data.length - 1)) {
            var cut = Arrays.copyOf(data, length);
            assertThrows(MediaException.Malformed.class, () -> decoder.decode(packet(cut), frame -> { }),
                    "length " + length);
        }
    }

    @ParameterizedTest
    @EnumSource(DvProfile.class)
    @DisplayName("decodes a frame whose video blocks are damaged to a full picture")
    void corrupted(DvProfile profile) {
        var data = frame(profile);
        var random = new Random(profile.ordinal());
        var decoder = new DvDecoder();
        for (var round = 0; round < 40; round++) {
            var damaged = data.clone();
            for (var i = 0; i < 2000; i++) {
                var block = random.nextInt(damaged.length / 80);
                if ((damaged[block * 80] & 0xE0) >>> 5 == 4) {
                    damaged[block * 80 + 3 + random.nextInt(77)] = (byte) random.nextInt(256);
                }
            }
            var frames = new ArrayList<VideoFrame>();
            decoder.decode(packet(damaged), frames::add);
            assertEquals(1, frames.size());
            assertEquals(profile.width(), frames.getFirst().picture().width());
        }
        var silent = data.clone();
        for (var block = 0; block < silent.length / 80; block++) {
            if ((silent[block * 80] & 0xE0) >>> 5 == 4) {
                Arrays.fill(silent, block * 80 + 3, block * 80 + 80, (byte) (round(block) ? 0xFF : 0));
            }
        }
        decoder.decode(packet(silent), frame -> assertEquals(profile.height(), frame.picture().height()));
    }

    private static boolean round(int block) {
        return block % 2 == 0;
    }

    @Test
    @DisplayName("is created by VideoDecoders for DV tracks and follows a change of format")
    void registry() {
        assertTrue(VideoDecoders.isSupported(Codec.DV));
        var track = VideoTrack.builder().id(1).codec(Codec.DV).timeBase(new Rational(1001, 30000)).width(720)
                .height(480).build();
        try (var decoder = VideoDecoders.create(track)) {
            var sizes = new ArrayList<String>();
            for (var profile : List.of(DvProfile.DV_525, DvProfile.DV_625, DvProfile.DVCPRO_HD_720P60)) {
                decoder.decode(packet(frame(profile)), frame -> sizes.add(frame.picture().width() + "x"
                        + frame.picture().height() + " " + ((YuvPicture) frame.picture()).chromaFormat()));
            }
            assertEquals(List.of("720x480 YUV422", "720x576 YUV420", "960x720 YUV422"), sizes);
        }
        var other = VideoTrack.builder().id(1).codec(Codec.PRORES).timeBase(new Rational(1, 25)).width(720)
                .height(480).build();
        assertThrows(IllegalArgumentException.class, () -> new DvDecoder(other));
    }
}
