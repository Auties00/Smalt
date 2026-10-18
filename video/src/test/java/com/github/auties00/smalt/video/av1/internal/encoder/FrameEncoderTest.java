package com.github.auties00.smalt.video.av1.internal.encoder;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.video.av1.internal.Av1Picture;
import com.github.auties00.smalt.video.vp9.internal.Vp9TestVideo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the frame encoder directly with fixed parameters and checks that every frame decodes to
 * exactly the encoder's reconstruction, with the module's decoder and with ffmpeg's libaom.
 */
class FrameEncoderTest {
    static List<byte[]> encode(List<YuvPicture> pictures, int width, int height, int qindex, int lf, int cdef,
                               int tilesLog2, List<byte[]> recons) {
        var fe = new FrameEncoder(width, height, ColorInfo.BT601_LIMITED, 8, MediaLimits.DEFAULT);
        var units = new ArrayList<byte[]>();
        for (var i = 0; i < pictures.size(); i++) {
            var p = new FrameParams();
            p.key = i == 0;
            p.orderHint = i;
            p.qindex = qindex;
            p.refreshFlags = 1;
            p.allowHighPrecisionMv = true;
            p.lfLevelY[0] = lf;
            p.lfLevelY[1] = lf;
            p.lfLevelU = lf;
            p.lfLevelV = lf;
            p.cdefYStrength = cdef;
            p.cdefUvStrength = cdef;
            p.tileColsLog2 = tilesLog2;
            units.add(fe.encode(pictures.get(i), p));
            Av1Picture recon = fe.reference(0);
            recons.add(Av1EncTesting.raw(recon, width, height));
        }
        fe.close();
        return units;
    }

    @ParameterizedTest(name = "{0}x{1} q{2} lf{3} cdef{4} tiles{5}")
    @DisplayName("every frame decodes to the encoder's reconstruction")
    @CsvSource({
            "64, 64, 100, 0, 0, 0",
            "176, 144, 100, 0, 0, 0",
            "176, 144, 60, 12, 0, 0",
            "176, 144, 140, 20, 9, 0",
            "200, 120, 120, 16, 17, 0",
            "352, 288, 90, 14, 33, 1",
    })
    void bitExact(int width, int height, int qindex, int lf, int cdef, int tiles) throws Exception {
        var pictures = Vp9TestVideo.synthesize(width, height, 6);
        var recons = new ArrayList<byte[]>();
        var units = encode(pictures, width, height, qindex, lf, cdef, tiles, recons);
        var decoded = Av1EncTesting.decode(width, height, units);
        assertEquals(units.size(), decoded.size());
        for (var i = 0; i < decoded.size(); i++) {
            assertArrayEquals(recons.get(i), Av1EncTesting.raw(decoded.get(i)), "frame " + i);
            var psnr = Av1EncTesting.psnr(Av1EncTesting.raw(pictures.get(i)), recons.get(i));
            assertTrue(psnr > 25, "frame " + i + " psnr " + psnr);
        }
        if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
            var file = Av1EncTesting.writeTemp(Av1EncTesting.ivf(width, height, units));
            var raw = Av1EncTesting.ffmpegDecode(file);
            var frameSize = recons.get(0).length;
            assertEquals(frameSize * units.size(), raw.length);
            for (var i = 0; i < units.size(); i++) {
                assertArrayEquals(recons.get(i), java.util.Arrays.copyOfRange(raw, i * frameSize, (i + 1) * frameSize),
                        "ffmpeg frame " + i);
            }
        }
    }
}
