package com.github.auties00.smalt.image.dds;

import com.github.auties00.smalt.image.ChannelFloatPicture;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Tolerance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compares the depth-stencil, {@code R1_UNORM}, video and legacy palette formats with DirectXTex, whose decode of
 * every fixture of {@code dds/dxtex} (see its README) is checked in: the texel values Smalt decodes equal DirectXTex's
 * exactly, once both are expressed in Smalt's output (stencils scaled from 0..255 to 0..1, 10-bit samples widened to
 * 16 bits, floating-point values kept).
 */
@DisplayName("DDS against DirectXTex")
class DdsDirectXTexDifferentialTest {
    private static final ImageDecoderOptions NATIVE = ImageDecoderOptions.builder().highBitDepth(true)
            .keepChannels(true).build();

    /** Fixtures whose decode is a floating-point picture. */
    private static final Set<String> FLOAT = Set.of("d32-float-s8x24-uint", "r32-float-x8x24-typeless", "d32-float",
            "d24-unorm-s8-uint", "r24-unorm-x8-typeless");
    /** Fixtures of one color channel. */
    private static final Set<String> GRAY = Set.of("r32-float-x8x24-typeless", "d32-float", "r24-unorm-x8-typeless",
            "d16-unorm", "r1-unorm");
    /** Fixtures with an 8-bit stencil in green, which DirectXTex reports as 0..255. */
    private static final Set<String> STENCIL = Set.of("d32-float-s8x24-uint", "x32-typeless-g8x24-uint",
            "d24-unorm-s8-uint", "x24-typeless-g8-uint");
    /** Fixtures of 10-bit RGB, which DirectXTex reports as fractions of 1023. */
    private static final Set<String> TEN_BIT = Set.of("y410", "y210", "p010");
    /** Fixtures that decode to 16-bit pictures. */
    private static final Set<String> SIXTEEN_BIT = Set.of("d16-unorm", "y410", "y416", "y210", "y216", "p010", "p016");

    private record Fixture(String name, int dxgi, int width, int height, String ddsSha256, String rawSha256) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Fixture> fixtures() {
        var text = new String(resource("expected.tsv"), StandardCharsets.UTF_8);
        return text.lines().filter(line -> !line.startsWith("#") && !line.isBlank()).map(line -> {
            var columns = line.split("\t");
            return new Fixture(columns[0], Integer.parseInt(columns[1]), Integer.parseInt(columns[2]),
                    Integer.parseInt(columns[3]), columns[4], columns[5]);
        });
    }

    private static byte[] resource(String name) {
        try (InputStream in = DdsDirectXTexDifferentialTest.class.getResourceAsStream("dxtex/" + name)) {
            return Objects.requireNonNull(in, "missing fixture dxtex/" + name).readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    /** {@return the samples of a decoded picture, packed, as integers; float samples as their bits} */
    static int[] samples(Picture picture) {
        return switch (picture) {
            case RgbaPicture rgba -> {
                var out = new int[rgba.width() * rgba.height() * 4];
                for (var y = 0; y < rgba.height(); y++) {
                    for (var x = 0; x < rgba.width() * 4; x++) {
                        out[y * rgba.width() * 4 + x] = rgba.pixels()[y * rgba.stride() + x] & 0xFF;
                    }
                }
                yield out;
            }
            case Rgba16Picture wide -> {
                var out = new int[wide.width() * wide.height() * 4];
                for (var y = 0; y < wide.height(); y++) {
                    for (var x = 0; x < wide.width() * 4; x++) {
                        out[y * wide.width() * 4 + x] = wide.pixels()[y * wide.stride() + x] & 0xFFFF;
                    }
                }
                yield out;
            }
            case ChannelFloatPicture floats -> {
                var row = floats.width() * floats.channelCount();
                var out = new int[row * floats.height()];
                for (var y = 0; y < floats.height(); y++) {
                    for (var x = 0; x < row; x++) {
                        out[y * row + x] = Float.floatToRawIntBits(floats.pixels()[y * floats.stride() + x]);
                    }
                }
                yield out;
            }
            default -> throw new AssertionError("unexpected picture " + picture.getClass().getSimpleName());
        };
    }

    @Test
    @DisplayName("covers every DirectXTex-decodable format the decoder added")
    void coverage() {
        var formats = fixtures().map(Fixture::dxgi).collect(Collectors.toSet());
        assertEquals(Set.of(20, 21, 22, 28, 40, 45, 46, 47, 55, 66, 100, 101, 102, 103, 104, 105, 107, 108, 109,
                110), formats);
    }

    @ParameterizedTest
    @MethodSource("fixtures")
    @DisplayName("decodes the fixture to DirectXTex's values exactly")
    void matches(Fixture fixture) {
        var file = resource(fixture.name() + ".dds");
        var raw = resource(fixture.name() + ".f32");
        assertEquals(fixture.ddsSha256(), sha256(file), "fixture " + fixture.name() + ".dds");
        assertEquals(fixture.rawSha256(), sha256(raw), "fixture " + fixture.name() + ".f32");
        var picture = DdsDecoder.decode(MediaSource.of(file), NATIVE).picture();
        assertEquals(fixture.width(), picture.width());
        assertEquals(fixture.height(), picture.height());
        var oracle = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
        var count = fixture.width() * fixture.height();
        assertEquals(count * 4, oracle.remaining());
        var name = fixture.name();
        var floating = FLOAT.contains(name);
        var gray = GRAY.contains(name);
        var channels = floating ? (gray ? 1 : 3) : 4;
        var expected = new int[count * channels];
        var texel = new double[4];
        for (var index = 0; index < count; index++) {
            for (var channel = 0; channel < 4; channel++) {
                texel[channel] = oracle.get(index * 4 + channel);
            }
            if (STENCIL.contains(name)) {
                texel[1] /= 255;
            }
            if (TEN_BIT.contains(name)) {
                for (var channel = 0; channel < 3; channel++) {
                    texel[channel] = Math.round(texel[channel] * 1023) / 1023.0;
                }
                texel[3] = Math.round(texel[3] * 3) / 3.0;
            }
            if (gray) {
                texel[1] = texel[2] = texel[0];
            }
            for (var channel = 0; channel < channels; channel++) {
                var value = texel[channel];
                expected[index * channels + channel] = floating ? Float.floatToRawIntBits((float) value)
                        : SIXTEEN_BIT.contains(name) ? (int) Math.round(value * 65535)
                        : (int) Math.round(value * 255);
            }
        }
        var actual = samples(picture);
        if (floating) {
            assertInstanceOf(ChannelFloatPicture.class, picture);
        } else if (SIXTEEN_BIT.contains(name)) {
            assertInstanceOf(Rgba16Picture.class, picture);
        } else {
            assertInstanceOf(RgbaPicture.class, picture);
        }
        Tolerance.exact().assertWithin(name, expected, actual);
    }

    /** The DirectXTex fixtures keyed by name, for the tests of formats that share their texels. */
    static Map<String, byte[]> files() {
        return fixtures().collect(Collectors.toMap(Fixture::name, fixture -> resource(fixture.name() + ".dds")));
    }
}
