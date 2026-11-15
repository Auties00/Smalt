package com.github.auties00.smalt.image;

import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.testkit.BuilderVariants;
import com.github.auties00.smalt.testkit.ValueContract;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

/**
 * Checks that every setting of the codec-independent image options takes part in {@code equals} and
 * {@code hashCode}, the metadata settings included.
 */
@DisplayName("image option values")
class ImageOptionsValueContractTest {
    @Test
    @DisplayName("encoder options")
    void encoder() {
        var variants = new ArrayList<>(BuilderVariants.of(ImageEncoderOptions::builder, ImageEncoderOptions.Builder::build,
                2));
        variants.add(ImageEncoderOptions.builder().colorProfile(ColorProfile.srgb()).build());
        variants.add(ImageEncoderOptions.builder().exif(new byte[]{'M', 'M', 0, 42, 0, 0, 0, 8, 0, 0}).build());
        variants.add(ImageEncoderOptions.builder().xmp("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"/>".getBytes(
                java.nio.charset.StandardCharsets.UTF_8)).build());
        ValueContract.check(() -> ImageEncoderOptions.builder().build(), variants);
    }

    @Test
    @DisplayName("decoder options")
    void decoder() {
        ValueContract.check(ImageDecoderOptions::defaults,
                BuilderVariants.of(ImageDecoderOptions::builder, ImageDecoderOptions.Builder::build, 2));
    }
}
