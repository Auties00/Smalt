package com.github.auties00.smalt.image.jxl.internal.vardct;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ForwardTransformTest {
    @ParameterizedTest(name = "transform {0}")
    @ValueSource(ints = {TransformType.DCT8, TransformType.HORNUSS, TransformType.DCT2, TransformType.DCT4,
            TransformType.DCT4X8, TransformType.DCT8X4, TransformType.AFV0, TransformType.AFV1, TransformType.AFV2,
            TransformType.AFV3})
    @DisplayName("the encoder's forward 8x8 transforms are inverted by the decoder's")
    void inverts(int type) {
        var random = new Random(type);
        var stride = 24;
        var plane = new float[stride * 16];
        for (var i = 0; i < plane.length; i++) {
            plane[i] = random.nextFloat() - 0.25f;
        }
        var scratch = new float[2048];
        var coefficients = new float[64];
        VarDctEncoder.forward(plane, stride, 8, 8, type, coefficients, scratch);
        var lf = new float[]{coefficients[0]};
        Varblocks.transform(type, coefficients, 0, 8, lf, 0, 1, scratch);
        for (var y = 0; y < 8; y++) {
            for (var x = 0; x < 8; x++) {
                assertEquals(plane[(8 + y) * stride + 8 + x], coefficients[y * 8 + x], 1e-5, "sample " + x + "," + y);
            }
        }
    }
}
