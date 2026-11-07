package com.github.auties00.smalt.video.h264.internal;

import com.github.auties00.smalt.MediaException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Parses hand-coded depth parameter sets: a minimal valid set, and sets whose view count or references the
 * sequence parameter set cannot back.
 */
@DisplayName("H.264 3D-AVC depth parameter sets")
class H264Avc3dExtensionTest {
    /** Appends {@code ue(v)} codes, fixed-length fields and flags to a bit string. */
    private static final class Bits {
        private final StringBuilder bits = new StringBuilder();

        Bits ue(int value) {
            var code = Integer.toBinaryString(value + 1);
            bits.append("0".repeat(code.length() - 1)).append(code);
            return this;
        }

        Bits u(int value, int count) {
            for (var i = count - 1; i >= 0; i--) {
                bits.append((value >> i) & 1);
            }
            return this;
        }

        H264BitReader reader() {
            var data = new byte[(bits.length() + 7) / 8 + H264BitReader.PADDING];
            for (var i = 0; i < bits.length(); i++) {
                if (bits.charAt(i) == '1') {
                    data[i >> 3] |= (byte) (0x80 >> (i & 7));
                }
            }
            var reader = new H264BitReader();
            reader.init(data, 0, (bits.length() + 7) / 8 + 1);
            return reader;
        }
    }

    /** A set with an id, coded on its own, with a number of views and nothing else present. */
    private static Bits plainSet(int id, int views) {
        // pred_direction 2, num_depth_views_minus1, no depth ranges, no disparities, no extension, linear
        return new Bits().ue(id).u(2, 2).ue(views - 1).u(0, 2).u(0, 1).u(0, 1).ue(0).u(1, 1);
    }

    @Test
    @DisplayName("parses a set whose depth ranges come from the sequence parameter set")
    void plain() {
        var sequence = new H264Avc3dExtension.Acquisition(2);
        var set = H264Avc3dExtension.DepthParameterSet.parse(plainSet(5, 2).reader(), sequence,
                new H264Avc3dExtension.DepthParameterSet[64]);
        assertEquals(5, set.id());
        assertEquals(2, set.acquisition().views);
        assertEquals(2, set.nonlinearPoints().length);
    }

    @Test
    @DisplayName("rejects a set with more depth views than the sequence parameter set has views")
    void tooManyViews() {
        var sequence = new H264Avc3dExtension.Acquisition(2);
        assertThrows(MediaException.Malformed.class, () -> H264Avc3dExtension.DepthParameterSet.parse(
                plainSet(1, 1024).reader(), sequence, new H264Avc3dExtension.DepthParameterSet[64]));
    }

    @Test
    @DisplayName("rejects a set predicted from a depth parameter set that was never sent")
    void missingReference() {
        var sequence = new H264Avc3dExtension.Acquisition(1);
        var bits = new Bits().ue(2).u(1, 2).ue(7).ue(0).u(0, 2).u(0, 1).u(0, 1).ue(0).u(1, 1);
        assertThrows(MediaException.Malformed.class, () -> H264Avc3dExtension.DepthParameterSet.parse(bits.reader(),
                sequence, new H264Avc3dExtension.DepthParameterSet[64]));
    }
}
