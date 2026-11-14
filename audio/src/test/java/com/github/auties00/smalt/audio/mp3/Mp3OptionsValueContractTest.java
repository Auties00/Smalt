package com.github.auties00.smalt.audio.mp3;

import com.github.auties00.smalt.testkit.BuilderVariants;
import com.github.auties00.smalt.testkit.ValueContract;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks that every setting of the MPEG audio encoder options takes part in {@code equals} and {@code hashCode}, and
 * that {@code toBuilder} keeps them all.
 */
@DisplayName("MPEG audio encoder option values")
class Mp3OptionsValueContractTest {
    @Test
    @DisplayName("Layer III encoder options")
    void layer3() {
        ValueContract.check(() -> Mp3EncoderOptions.builder().build(),
                BuilderVariants.of(Mp3EncoderOptions::builder, Mp3EncoderOptions.Builder::build, 3));
        var options = Mp3EncoderOptions.builder().build();
        assertEquals(options, options.toBuilder().build());
    }

    @Test
    @DisplayName("Layer II encoder options")
    void layer2() {
        ValueContract.check(() -> Mp2EncoderOptions.builder().build(),
                BuilderVariants.of(Mp2EncoderOptions::builder, Mp2EncoderOptions.Builder::build, 3));
        var options = Mp2EncoderOptions.builder().build();
        assertEquals(options, options.toBuilder().build());
    }
}
