package com.github.auties00.smalt.fuzz;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.github.auties00.smalt.internal.compress.Bzip2Decoder;
import com.github.auties00.smalt.internal.compress.Lzo1xDecoder;
import com.github.auties00.smalt.testkit.fuzz.Fuzz;

/**
 * Fuzz targets of the bzip2 and LZO1X decompressors of Matroska's content compression ({@code ContentCompAlgo} 1 and
 * 2): each input is one compressed stream, decompressed under {@link Fuzz#LIMITS}.
 */
class CompressionFuzzTest {
    @FuzzTest
    void bzip2(byte[] data) {
        var run = Fuzz.start("bzip2");
        run.value("decode", () -> Bzip2Decoder.decode(data, 0, data.length, Fuzz.LIMITS)).ifPresent(decoded -> run.output());
    }

    @FuzzTest
    void lzo1x(byte[] data) {
        var run = Fuzz.start("lzo1x");
        run.value("decode", () -> Lzo1xDecoder.decode(data, 0, data.length, Fuzz.LIMITS)).ifPresent(decoded -> run.output());
    }
}
