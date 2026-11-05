package com.github.auties00.smalt.image.jpeg;

import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.gainmap.GainMap;
import com.github.auties00.smalt.image.gainmap.GainMapComputeOptions;
import com.github.auties00.smalt.image.gainmap.GainMapOptions;
import com.github.auties00.smalt.image.gainmap.GainMaps;
import com.github.auties00.smalt.image.ops.ChromaUpsampling;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.testkit.Tolerance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compares Smalt's Ultra HDR reading and writing with libultrahdr 1.4.0's {@code ultrahdr_app} as a black box:
 * Smalt's files decoded by libultrahdr and libultrahdr's files decoded by Smalt, rendered to 10-bit PQ at the full
 * headroom. Smalt writes 4:4:4 primary images, so both decoders see the same R'G'B'; libultrahdr's primary
 * images are 4:2:0 and are compared with Smalt's decode upsampled by sample replication, which is what
 * libultrahdr does. A gain map of the image's size is compared within one 10-bit code value; a downscaled one is
 * resampled differently (libultrahdr interpolates by inverse distance weighting, Smalt bilinearly in the log2
 * domain), so it is compared by an RMS that grows with the downscaling factor. libultrahdr is built with its lookup
 * tables off (tools/testdata/oracles.py), so that it computes its transfer functions exactly.
 */
@DisplayName("JPEG gain maps against libultrahdr")
class JpegGainMapDifferentialTest {
    static final Duration TIMEOUT = Duration.ofMinutes(2);

    /** Decodes an Ultra HDR file with ultrahdr_app to 10-bit PQ samples, red, green and blue per pixel. */
    static int[] oracleDecode(Path file, int width, int height) throws IOException {
        var out = file.resolveSibling(file.getFileName() + ".raw");
        Oracle.run(Oracle.Tool.ULTRAHDR_APP, TIMEOUT, List.of("-m", "1", "-j", file.toString(), "-o", "2", "-O", "5",
                "-z", out.toString()));
        var buffer = ByteBuffer.wrap(Files.readAllBytes(out)).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(width * height * 4, buffer.capacity());
        var samples = new int[width * height * 3];
        for (var i = 0; i < width * height; i++) {
            var pixel = buffer.getInt(i * 4);
            samples[i * 3] = pixel & 0x3FF;
            samples[i * 3 + 1] = (pixel >>> 10) & 0x3FF;
            samples[i * 3 + 2] = (pixel >>> 20) & 0x3FF;
        }
        return samples;
    }

    /** Reduces a rendered 16-bit PQ picture to 10-bit samples, red, green and blue per pixel. */
    static int[] tenBit(DecodedImage image) {
        var picture = (Rgba16Picture) image.picture();
        var samples = new int[picture.width() * picture.height() * 3];
        for (var y = 0; y < picture.height(); y++) {
            for (var x = 0; x < picture.width(); x++) {
                for (var c = 0; c < 3; c++) {
                    var value = picture.pixels()[y * picture.stride() + x * 4 + c] & 0xFFFF;
                    samples[(y * picture.width() + x) * 3 + c] = (int) Math.round(value * 1023.0 / 65535);
                }
            }
        }
        return samples;
    }

    static DecodedImage rendered(DecodedImage base, GainMap gainMap) {
        return GainMaps.apply(base, gainMap, GainMapOptions.builder().primaries(ColorPrimaries.BT709).build());
    }

    @Nested
    @DisplayName("Smalt files decoded by libultrahdr")
    class SmaltFiles {
        @ParameterizedTest(name = "multichannel {0}")
        @ValueSource(booleans = {false, true})
        @DisplayName("render alike")
        void render(boolean multichannel) throws IOException {
            Oracle.assume(Oracle.Tool.ULTRAHDR_APP);
            var base = JpegGainMapTest.sdr(97, 61);
            var file = JpegGainMapTest.ultraHdr(base, GainMapComputeOptions.builder().multichannel(multichannel)
                    .build());
            var path = Scratch.directory("jpeg-gainmap").resolve("smalt-" + multichannel + ".jpg");
            Files.write(path, file);
            var expected = oracleDecode(path, 97, 61);
            var primary = JpegDecoder.decode(MediaSource.of(file));
            var gainMap = JpegDecoder.decodeGainMap(MediaSource.of(file)).orElseThrow();
            Tolerance.offByOne().assertWithin("multichannel " + multichannel, expected, tenBit(rendered(primary,
                    gainMap)));
        }
    }

    @Nested
    @DisplayName("libultrahdr files decoded by Smalt")
    class OracleFiles {
        /** Writes the raw inputs of ultrahdr_app: 8-bit sRGB RGBA and the 10-bit PQ RGBA of the HDR rendition. */
        static Path encode(String name, int width, int height, List<String> options) throws IOException {
            var directory = Scratch.directory("jpeg-gainmap");
            var sdr = JpegGainMapTest.sdr(width, height);
            var hdr = (Rgba16Picture) JpegGainMapTest.hdr(sdr).picture();
            var sdrRaw = ByteBuffer.allocate(width * height * 4);
            var hdrRaw = ByteBuffer.allocate(width * height * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    sdrRaw.put(sdr.pixels(), y * sdr.stride() + x * 4, 4);
                    var at = y * hdr.stride() + x * 4;
                    var pixel = 3 << 30;
                    for (var c = 0; c < 3; c++) {
                        pixel |= (int) Math.round((hdr.pixels()[at + c] & 0xFFFF) * 1023.0 / 65535) << (10 * c);
                    }
                    hdrRaw.putInt(pixel);
                }
            }
            var sdrPath = directory.resolve(name + "-sdr.raw");
            var hdrPath = directory.resolve(name + "-hdr.raw");
            Files.write(sdrPath, sdrRaw.array());
            Files.write(hdrPath, hdrRaw.array());
            var out = directory.resolve(name + ".jpg");
            var arguments = new java.util.ArrayList<>(List.of("-m", "0", "-p", hdrPath.toString(), "-y",
                    sdrPath.toString(), "-a", "5", "-b", "3", "-w", Integer.toString(width), "-h",
                    Integer.toString(height), "-C", "0", "-c", "0", "-t", "2", "-R", "1", "-q", "95", "-Q", "95",
                    "-z", out.toString()));
            arguments.addAll(options);
            Oracle.run(Oracle.Tool.ULTRAHDR_APP, TIMEOUT, arguments);
            return out;
        }

        /** Reads the metadata file ultrahdr_app writes with {@code -f}. */
        static Map<String, double[]> metadata(Path file) throws IOException {
            var result = new HashMap<String, double[]>();
            for (var line : Files.readAllLines(file)) {
                var parts = line.trim().split("\\s+");
                if (parts.length < 2) {
                    continue;
                }
                var values = new double[parts.length - 1];
                for (var i = 1; i < parts.length; i++) {
                    values[i - 1] = Double.parseDouble(parts[i]);
                }
                result.put(parts[0], values);
            }
            return result;
        }

        @ParameterizedTest(name = "multichannel {0}, scale {1}")
        @CsvSource({"0, 1", "1, 1", "0, 2", "1, 4"})
        @DisplayName("render alike, with the metadata libultrahdr reads")
        void render(int multichannel, int scale) throws IOException {
            Oracle.assume(Oracle.Tool.ULTRAHDR_APP);
            var width = 96;
            var height = 64;
            var path = encode("oracle-" + multichannel + "-" + scale, width, height, List.of("-M",
                    Integer.toString(multichannel), "-s", Integer.toString(scale)));
            var file = Files.readAllBytes(path);
            var metadataPath = path.resolveSibling(path.getFileName() + ".cfg");
            Oracle.run(Oracle.Tool.ULTRAHDR_APP, TIMEOUT, List.of("-m", "1", "-j", path.toString(), "-o", "2", "-O",
                    "5", "-z", path.resolveSibling(path.getFileName() + ".raw").toString(), "-f",
                    metadataPath.toString()));
            var expectedMetadata = metadata(metadataPath);
            var gainMap = JpegDecoder.decodeGainMap(MediaSource.of(file)).orElseThrow();
            var metadata = gainMap.metadata();
            for (var c = 0; c < 3; c++) {
                var channel = metadata.channel(c);
                var index = expectedMetadata.get("--maxContentBoost").length == 1 ? 0 : c;
                assertEquals(expectedMetadata.get("--maxContentBoost")[index], Math.pow(2, channel.maximum()),
                        1e-5 * Math.pow(2, channel.maximum()));
                assertEquals(expectedMetadata.get("--minContentBoost")[index], Math.pow(2, channel.minimum()),
                        1e-5 * Math.pow(2, channel.minimum()) + 1e-6);
                assertEquals(expectedMetadata.get("--gamma")[index], channel.gamma(), 1e-5);
                assertEquals(expectedMetadata.get("--offsetSdr")[index], channel.baseOffset(), 1e-6);
                assertEquals(expectedMetadata.get("--offsetHdr")[index], channel.alternateOffset(), 1e-6);
            }
            assertEquals(expectedMetadata.get("--hdrCapacityMax")[0], Math.pow(2, metadata.alternateHeadroom()),
                    1e-5 * Math.pow(2, metadata.alternateHeadroom()));
            assertEquals(expectedMetadata.get("--hdrCapacityMin")[0], Math.pow(2, metadata.baseHeadroom()), 1e-5);
            assertTrue(gainMap.image().width() == (width + scale - 1) / scale
                    || gainMap.image().width() == width / scale);
            var primary = JpegDecoder.decode(MediaSource.of(file));
            var replicated = new DecodedImage(ImageOps.toRgba16(primary.picture(), ChromaUpsampling.NEAREST),
                    primary.format(), primary.orientation(), width, height, primary.iccProfile(), primary.exif(),
                    primary.xmp(), primary.colorProfile());
            assertTrue(primary.picture() instanceof YuvPicture);
            var expected = oracleDecode(path, width, height);
            var actual = tenBit(rendered(replicated, gainMap));
            if (scale == 1) {
                Tolerance.offByOne().assertWithin("multichannel " + multichannel, expected, actual);
            } else {
                // libultrahdr interpolates the gain map by inverse distance weighting, Smalt bilinearly in log2
                Tolerance.rms(2.5 * scale).assertWithin("multichannel " + multichannel + ", scale " + scale, expected,
                        actual);
            }
        }
    }
}
