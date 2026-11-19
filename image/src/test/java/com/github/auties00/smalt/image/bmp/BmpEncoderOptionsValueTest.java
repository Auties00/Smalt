package com.github.auties00.smalt.image.bmp;

import com.github.auties00.smalt.image.PixelDensity;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.ops.Dithering;
import com.github.auties00.smalt.image.ops.QuantizeMethod;
import com.github.auties00.smalt.testkit.ValueContract;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the value semantics of {@link BmpEncoderOptions}: every setting takes part in {@code equals} and
 * {@code hashCode}, {@code toString} names the settings that differ from automatic, and {@code toBuilder} keeps them.
 */
@DisplayName("BMP encoder options values")
class BmpEncoderOptionsValueTest {
    private static BmpEncoderOptions.Builder base() {
        return BmpEncoderOptions.builder().pixelFormat(BmpEncoderOptions.PixelFormat.PALETTE_8);
    }

    @Test
    @DisplayName("compare every setting")
    void contract() {
        ValueContract.check(() -> base().build(), List.of(
                base().pixelFormat(BmpEncoderOptions.PixelFormat.PALETTE_4).build(),
                base().runLength(true).build(),
                base().headerVersion(BmpEncoderOptions.HeaderVersion.V5).build(),
                base().colorProfile(ColorProfile.srgb()).build(),
                base().topDown(true).build(),
                base().density(PixelDensity.ofDotsPerInch(300)).build(),
                base().method(QuantizeMethod.NEUQUANT).build(),
                base().dithering(Dithering.NONE).build()));
    }

    @Test
    @DisplayName("name the settings that are set, and keep them through toBuilder")
    void text() {
        var automatic = BmpEncoderOptions.builder().build();
        assertTrue(automatic.toString().contains("pixelFormat=automatic"), automatic.toString());
        assertTrue(automatic.toString().contains("headerVersion=automatic"), automatic.toString());
        var full = base().runLength(true).headerVersion(BmpEncoderOptions.HeaderVersion.V5)
                .colorProfile(ColorProfile.srgb()).density(PixelDensity.ofDotsPerInch(72)).build();
        var text = full.toString();
        for (var part : List.of("pixelFormat=PALETTE_8", "runLength", "headerVersion=V5", "colorProfile=", "density=")) {
            assertTrue(text.contains(part), text);
        }
        var top = base().topDown(true).build();
        assertTrue(top.toString().contains("topDown"), top.toString());
        assertEquals(full, full.toBuilder().build());
        assertEquals(automatic, full.toBuilder().automaticPixelFormat().runLength(false).automaticHeaderVersion()
                .noColorProfile().noDensity().build());
    }
}
