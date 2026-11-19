package com.github.auties00.smalt.image;

import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.testkit.ValueContract;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers the validation of the image metadata records and the value semantics of {@link DecodedImage}.
 */
@DisplayName("image value records")
class ImageValueRecordsTest {
    private static final ContentColorVolume.Coordinate POINT = new ContentColorVolume.Coordinate(0.3, 0.3);

    @Test
    @DisplayName("a content color volume holds no or three primaries and finite non-negative luminances")
    void contentColorVolume() {
        var none = OptionalDouble.empty();
        assertDoesNotThrow(() -> new ContentColorVolume(List.of(), none, none, none));
        assertDoesNotThrow(() -> new ContentColorVolume(List.of(POINT, POINT, POINT), OptionalDouble.of(0),
                OptionalDouble.of(1000), OptionalDouble.of(50)));
        assertThrows(IllegalArgumentException.class, () -> new ContentColorVolume(List.of(POINT), none, none, none));
        for (var bad : new double[]{-1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> new ContentColorVolume(List.of(), OptionalDouble.of(bad),
                    none, none));
            assertThrows(IllegalArgumentException.class, () -> new ContentColorVolume(List.of(), none,
                    OptionalDouble.of(bad), none));
            assertThrows(IllegalArgumentException.class, () -> new ContentColorVolume(List.of(), none, none,
                    OptionalDouble.of(bad)));
            assertThrows(IllegalArgumentException.class, () -> new ContentColorVolume.Coordinate(bad == -1 ? Double.NaN
                    : bad, 0));
            assertThrows(IllegalArgumentException.class, () -> new ContentColorVolume.Coordinate(0, bad == -1
                    ? Double.NEGATIVE_INFINITY : bad));
        }
        assertThrows(NullPointerException.class, () -> new ContentColorVolume(List.of(), null, none, none));
        assertThrows(NullPointerException.class, () -> new ContentColorVolume(List.of(), none, null, none));
        assertThrows(NullPointerException.class, () -> new ContentColorVolume(List.of(), none, none, null));
    }

    @ParameterizedTest(name = "{0} lux")
    @ValueSource(doubles = {0, -5, Double.NaN, Double.POSITIVE_INFINITY})
    @DisplayName("an ambient viewing environment needs a positive finite illuminance")
    void ambient(double illuminance) {
        var white = new Chromaticity(0.3127, 0.329);
        assertThrows(IllegalArgumentException.class, () -> new AmbientViewingEnvironment(illuminance, white));
        assertDoesNotThrow(() -> new AmbientViewingEnvironment(314, white));
        assertThrows(NullPointerException.class, () -> new AmbientViewingEnvironment(314, null));
    }

    @Test
    @DisplayName("an auxiliary image needs a non-negative index and a positive size")
    void auxiliary() {
        assertDoesNotThrow(() -> new AuxiliaryImage(0, AuxiliaryImageType.ALPHA, "urn:x", 4, 4));
        assertThrows(IllegalArgumentException.class, () -> new AuxiliaryImage(-1, AuxiliaryImageType.ALPHA, "", 4, 4));
        assertThrows(IllegalArgumentException.class, () -> new AuxiliaryImage(0, AuxiliaryImageType.ALPHA, "", 0, 4));
        assertThrows(IllegalArgumentException.class, () -> new AuxiliaryImage(0, AuxiliaryImageType.ALPHA, "", 4, 0));
        assertThrows(NullPointerException.class, () -> new AuxiliaryImage(0, null, "", 4, 4));
        assertThrows(NullPointerException.class, () -> new AuxiliaryImage(0, AuxiliaryImageType.ALPHA, null, 4, 4));
    }

    @Test
    @DisplayName("a pixel layout's color channels must form a color model")
    void layouts() {
        assertEquals(ColorModel.RGB, PixelLayout.of(Channel.of(ChannelType.RED), Channel.of(ChannelType.GREEN),
                Channel.of(ChannelType.BLUE)).colorModel());
        assertEquals(ColorModel.CMYK, PixelLayout.of(Channel.of(ChannelType.CYAN), Channel.of(ChannelType.MAGENTA),
                Channel.of(ChannelType.YELLOW), Channel.of(ChannelType.BLACK)).colorModel());
        for (var channels : List.of(
                List.of(ChannelType.RED, ChannelType.BLUE, ChannelType.GREEN),
                List.of(ChannelType.RED, ChannelType.GREEN, ChannelType.GREEN),
                List.of(ChannelType.CYAN, ChannelType.YELLOW, ChannelType.MAGENTA, ChannelType.BLACK),
                List.of(ChannelType.CYAN, ChannelType.MAGENTA, ChannelType.MAGENTA, ChannelType.BLACK),
                List.of(ChannelType.CYAN, ChannelType.MAGENTA, ChannelType.YELLOW, ChannelType.CYAN),
                List.of(ChannelType.GRAY, ChannelType.GRAY))) {
            var array = channels.stream().map(Channel::of).toArray(Channel[]::new);
            assertThrows(IllegalArgumentException.class, () -> PixelLayout.of(array), channels.toString());
        }
    }

    @Test
    @DisplayName("decoded images compare their picture by identity and their metadata by content")
    void decodedImages() {
        var picture = RgbaPicture.allocate(2, 2, false);
        var other = RgbaPicture.allocate(2, 2, false);
        var icc = Optional.of(new byte[]{1, 2});
        var exif = Optional.of(new byte[]{3});
        var xmp = Optional.of(new byte[]{4});
        var profile = Optional.of(ColorProfile.srgb());
        var density = Optional.of(PixelDensity.ofDotsPerInch(72));
        ValueContract.check(() -> new DecodedImage(picture, ImageFormat.PNG, Orientation.NORMAL, 2, 2, icc.map(byte[]::clone),
                exif, xmp, profile, density), List.of(
                new DecodedImage(other, ImageFormat.PNG, Orientation.NORMAL, 2, 2, icc, exif, xmp, profile, density),
                new DecodedImage(picture, ImageFormat.JPEG, Orientation.NORMAL, 2, 2, icc, exif, xmp, profile, density),
                new DecodedImage(picture, ImageFormat.PNG, Orientation.ROTATE_90, 2, 2, icc, exif, xmp, profile, density),
                new DecodedImage(picture, ImageFormat.PNG, Orientation.NORMAL, 3, 2, icc, exif, xmp, profile, density),
                new DecodedImage(picture, ImageFormat.PNG, Orientation.NORMAL, 2, 3, icc, exif, xmp, profile, density),
                new DecodedImage(picture, ImageFormat.PNG, Orientation.NORMAL, 2, 2, Optional.of(new byte[]{9}), exif,
                        xmp, profile, density),
                new DecodedImage(picture, ImageFormat.PNG, Orientation.NORMAL, 2, 2, icc, Optional.empty(), xmp,
                        profile, density),
                new DecodedImage(picture, ImageFormat.PNG, Orientation.NORMAL, 2, 2, icc, exif, Optional.of(new byte[]{5}),
                        profile, density),
                new DecodedImage(picture, ImageFormat.PNG, Orientation.NORMAL, 2, 2, icc, exif, xmp, Optional.empty(),
                        density),
                new DecodedImage(picture, ImageFormat.PNG, Orientation.NORMAL, 2, 2, icc, exif, xmp, profile,
                        Optional.empty())));
    }
}
