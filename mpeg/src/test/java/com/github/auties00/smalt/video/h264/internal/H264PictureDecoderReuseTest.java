package com.github.auties00.smalt.video.h264.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks that a picture decoder taken from the idle pool works on any picture whatever it decoded before. With
 * several threads the picture jobs take idle decoders in any order, so a decoder may decode a picture after it
 * decoded a later one of the same stream, whose slice numbers are larger; a picture without slices stands for both
 * pictures here, since concealment marks every macroblock with the picture's slice number.
 */
@DisplayName("H.264 picture decoder reuse")
class H264PictureDecoderReuseTest {
    private static final int MB_WIDTH = 4;
    private static final int MB_HEIGHT = 3;

    @Test
    @DisplayName("conceals every macroblock of a picture after decoding a later picture of the stream")
    void earlierAfterLater() {
        var decoder = new PictureDecoder();
        var later = new DecoderFrame(MB_WIDTH, MB_HEIGHT, 1);
        decoder.start(later, DecoderFrame.FRAME, false, 10, 10, null, H264Dsp.INSTANCE);
        decoder.finish();
        var earlier = new DecoderFrame(MB_WIDTH, MB_HEIGHT, 1);
        Arrays.fill(earlier.luma, (byte) 7);
        decoder.start(earlier, DecoderFrame.FRAME, false, 5, 5, null, H264Dsp.INSTANCE);
        decoder.finish();
        assertGray(earlier);
    }

    @Test
    @DisplayName("conceals every macroblock of a picture after decoding an earlier picture of the stream")
    void laterAfterEarlier() {
        var decoder = new PictureDecoder();
        var earlier = new DecoderFrame(MB_WIDTH, MB_HEIGHT, 1);
        decoder.start(earlier, DecoderFrame.FRAME, false, 5, 5, null, H264Dsp.INSTANCE);
        decoder.finish();
        var later = new DecoderFrame(MB_WIDTH, MB_HEIGHT, 1);
        Arrays.fill(later.luma, (byte) 7);
        decoder.start(later, DecoderFrame.FRAME, false, 10, 10, null, H264Dsp.INSTANCE);
        decoder.finish();
        assertGray(later);
    }

    private static void assertGray(DecoderFrame frame) {
        for (var y = 0; y < MB_HEIGHT * 16; y++) {
            for (var x = 0; x < MB_WIDTH * 16; x++) {
                assertEquals(128, frame.luma[frame.lumaOrigin + y * frame.lumaStride + x] & 0xFF,
                        "luma at " + x + ", " + y);
            }
        }
    }
}
