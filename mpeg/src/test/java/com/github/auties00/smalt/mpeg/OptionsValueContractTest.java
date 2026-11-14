package com.github.auties00.smalt.mpeg;

import com.github.auties00.smalt.audio.aac.AacDecoderOptions;
import com.github.auties00.smalt.audio.aac.AacEncoderOptions;
import com.github.auties00.smalt.testkit.BuilderVariants;
import com.github.auties00.smalt.testkit.ValueContract;
import com.github.auties00.smalt.video.h263.H263EncoderOptions;
import com.github.auties00.smalt.video.h264.H264DecoderOptions;
import com.github.auties00.smalt.video.h264.encoder.H264EncoderOptions;
import com.github.auties00.smalt.video.hevc.HevcDecoderOptions;
import com.github.auties00.smalt.video.hevc.encoder.HevcEncoderOptions;
import com.github.auties00.smalt.video.mpeg4.Mpeg4EncoderOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks that every setting of the option types of this artifact takes part in {@code equals} and {@code hashCode},
 * and that {@code toBuilder} keeps them all.
 */
@DisplayName("option values of the MPEG codecs")
class OptionsValueContractTest {
    @Test
    @DisplayName("H.264 encoder options")
    void h264Encoder() {
        ValueContract.check(() -> H264EncoderOptions.builder().build(),
                BuilderVariants.of(H264EncoderOptions::builder, H264EncoderOptions.Builder::build, 8));
        var options = H264EncoderOptions.builder().build();
        assertEquals(options, options.toBuilder().build());
    }

    @Test
    @DisplayName("H.264 decoder options")
    void h264Decoder() {
        ValueContract.check(() -> H264DecoderOptions.builder().build(),
                BuilderVariants.of(H264DecoderOptions::builder, H264DecoderOptions.Builder::build, 2));
    }

    @Test
    @DisplayName("H.263 encoder options")
    void h263Encoder() {
        ValueContract.check(() -> H263EncoderOptions.builder().build(),
                BuilderVariants.of(H263EncoderOptions::builder, H263EncoderOptions.Builder::build, 6));
    }

    @Test
    @DisplayName("MPEG-4 Part 2 encoder options")
    void mpeg4Encoder() {
        ValueContract.check(() -> Mpeg4EncoderOptions.builder().build(),
                BuilderVariants.of(Mpeg4EncoderOptions::builder, Mpeg4EncoderOptions.Builder::build, 6));
    }

    @Test
    @DisplayName("HEVC decoder and encoder options, every preset")
    void hevc() {
        ValueContract.check(HevcDecoderOptions::defaults,
                BuilderVariants.of(HevcDecoderOptions::builder, HevcDecoderOptions.Builder::build, 2));
        for (var preset : new HevcEncoderOptions[]{HevcEncoderOptions.defaults(), HevcEncoderOptions.forFile(),
                HevcEncoderOptions.forRealtime(), HevcEncoderOptions.forStillPicture()}) {
            ValueContract.check(() -> preset.toBuilder().build(),
                    BuilderVariants.of(preset::toBuilder, HevcEncoderOptions.Builder::build, 6));
        }
    }

    @Test
    @DisplayName("AAC decoder and encoder options")
    void aac() {
        ValueContract.check(AacDecoderOptions::defaults,
                BuilderVariants.of(AacDecoderOptions::builder, AacDecoderOptions.Builder::build, 2));
        ValueContract.check(AacEncoderOptions::defaults,
                BuilderVariants.of(() -> AacEncoderOptions.defaults().toBuilder(), AacEncoderOptions.Builder::build, 3));
    }
}
