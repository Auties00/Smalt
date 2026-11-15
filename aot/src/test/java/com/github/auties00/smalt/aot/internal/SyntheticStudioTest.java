package com.github.auties00.smalt.aot.internal;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.mpeg4.Mpeg4Decoder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks that the synthetic MPEG-4 Studio streams of the training workload decode, every VOP to a frame of the
 * layer's size, chroma format and precision, with the samples their DPCM macroblocks code.
 */
@DisplayName("synthetic MPEG-4 Studio streams of the training workloads")
class SyntheticStudioTest {
    @ParameterizedTest(name = "{0}x{1}, chroma {2}, {3} bits, interlaced {4}")
    @CsvSource({"320, 192, 2, 10, true", "160, 96, 3, 12, false", "160, 96, 1, 8, false"})
    @DisplayName("every VOP decodes to a frame")
    void decodes(int width, int height, int chroma, int bits, boolean interlaced) {
        var stream = SyntheticStudio.write(width, height, chroma, bits, interlaced, 3);
        var frames = new ArrayList<Picture>();
        var firstSamples = new ArrayList<Integer>();
        try (var decoder = new Mpeg4Decoder(VideoStreams.track(Codec.MPEG4_PART2, width, height, stream.config()),
                VideoDecoderOptions.builder().highBitDepth(true).build())) {
            for (var packet : stream.packets()) {
                decoder.decode(packet, frame -> {
                    frames.add(frame.picture());
                    firstSamples.add(bits == 8 ? ((YuvPicture) frame.picture()).y()[16] & 0xFF
                            : ((Yuv16Picture) frame.picture()).y()[16] & 0xFFFF);
                });
            }
            decoder.flush(frame -> frames.add(frame.picture()));
        }
        assertEquals(3, frames.size());
        var format = chroma == 1 ? ChromaFormat.YUV420 : chroma == 2 ? ChromaFormat.YUV422 : ChromaFormat.YUV444;
        for (var picture : frames) {
            assertEquals(width, picture.width());
            assertEquals(height, picture.height());
            if (bits == 8) {
                assertEquals(format, ((YuvPicture) picture).chromaFormat());
            } else {
                assertEquals(format, ((Yuv16Picture) picture).chromaFormat());
                assertEquals(bits, ((Yuv16Picture) picture).bitDepth());
            }
        }
        // the second macroblock of the first frame is DPCM coded top down: its first sample is the start
        // predictor plus the first residual (the odd code 1, which maps to -1); the frames are read in the
        // callback because the decoder reuses its output picture
        assertEquals((1 << (bits - 1)) - 1, firstSamples.getFirst());
    }
}
