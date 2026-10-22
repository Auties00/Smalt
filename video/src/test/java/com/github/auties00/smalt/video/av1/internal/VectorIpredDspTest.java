package com.github.auties00.smalt.video.av1.internal;

import com.github.auties00.smalt.testkit.Skip;
import com.github.auties00.smalt.internal.simd.Simd;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;

/**
 * Runs the golden hashes of {@link IpredDspTest} against the Vector API intra prediction kernels.
 */
@DisplayName("AV1 vector intra prediction kernels match dav1d's C reference")
class VectorIpredDspTest extends IpredDspTest {
    @BeforeEach
    void vectorKernels() {
        Skip.unlessVector(Simd.ENABLED);
        dsp = new VectorIpredDsp();
    }
}
