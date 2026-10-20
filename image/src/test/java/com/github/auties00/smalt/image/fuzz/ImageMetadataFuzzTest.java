package com.github.auties00.smalt.image.fuzz;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.exif.ExifData;
import com.github.auties00.smalt.image.exif.ExifReader;
import com.github.auties00.smalt.image.exif.ExifWriter;
import com.github.auties00.smalt.image.jpeg.JpegTransformOptions;
import com.github.auties00.smalt.image.jpeg.JpegTransforms;
import com.github.auties00.smalt.image.metadata.ImageMetadata;
import com.github.auties00.smalt.image.metadata.ImageMetadataEdit;
import com.github.auties00.smalt.image.metadata.ImageMetadataEditor;
import com.github.auties00.smalt.image.metadata.IptcData;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.ops.Region;
import com.github.auties00.smalt.image.webp.WebpContainer;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.fuzz.Fuzz;
import com.github.auties00.smalt.testkit.fuzz.FuzzRun;

import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * Fuzz targets of the image metadata readers and editors (EXIF, IPTC, the metadata of every image format, the
 * WebP chunk editor) and of the lossless JPEG transforms.
 *
 * <p>Besides the exception contract they check properties: a block Smalt writes is read back, an image whose
 * metadata was edited still decodes when the original did and holds no metadata after {@code stripAll}, a WebP
 * chunk edit keeps the image chunks byte for byte, and a transformed JPEG decodes.
 */
class ImageMetadataFuzzTest {
    /** The transforms the JPEG target applies, each to the original input. */
    private static final List<JpegTransformOptions> TRANSFORMS = List.of(
            JpegTransformOptions.builder().transform(Orientation.ROTATE_90).trim(true).build(),
            JpegTransformOptions.builder().transform(Orientation.TRANSVERSE).build(),
            JpegTransformOptions.builder().crop(new Region(3, 5, 17, 9)).progressive(true).optimizeHuffman(true)
                    .build(),
            JpegTransformOptions.builder().grayscale(true).restartInterval(1).transformThumbnail(true).build(),
            JpegTransformOptions.jpegtran()
    );

    /** The chunks of a WebP file that hold its image. */
    private static final Set<String> WEBP_IMAGE_CHUNKS = Set.of("VP8 ", "VP8L", "ALPH", "ANIM", "ANMF");

    /** The EXIF payload the WebP target stores: a little-endian TIFF header with an empty directory. */
    private static final byte[] WEBP_EXIF = {'I', 'I', 42, 0, 8, 0, 0, 0, 0, 0, 0, 0, 0, 0};

    /** The XMP packet the WebP target stores. */
    private static final byte[] WEBP_XMP = ("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF "
            + "xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"/></x:xmpmeta>").getBytes(StandardCharsets.UTF_8);

    /** The ICC profile the WebP target stores. */
    private static final byte[] WEBP_ICC = ColorProfile.srgb().toByteArray();

    @FuzzTest
    void exif(byte[] data) {
        var run = Fuzz.start("exif");
        run.step("orientation", () -> ExifReader.orientation(data));
        var parsed = run.value("parse", () -> ExifData.parse(data));
        if (parsed.isEmpty()) {
            return;
        }
        var exif = parsed.get();
        exif.thumbnail();
        exif.hasLocation();
        var edited = exif.withOrientation(Orientation.ROTATE_270).withoutGps();
        for (var order : new ByteOrder[]{ByteOrder.BIG_ENDIAN, ByteOrder.LITTLE_ENDIAN}) {
            var reread = ExifData.parse(ExifWriter.write(edited, order));
            if (reread.orientation() != Orientation.ROTATE_270 || reread.hasLocation()) {
                throw new AssertionError("written EXIF read back as " + reread);
            }
        }
        run.step("rewrite", () -> ExifData.parse(ExifWriter.rewrite(data, edited)));
        run.step("with orientation", () -> ExifReader.orientation(ExifWriter.withOrientation(data,
                Orientation.FLIP_VERTICAL)));
        run.output();
    }

    @FuzzTest
    void iptc(byte[] data) {
        var run = Fuzz.start("iptc");
        run.value("parse", () -> IptcData.parse(data)).ifPresent(iptc -> {
            iptc.dataSets();
            iptc.hasLocation();
            var stripped = iptc.withoutLocation();
            if (stripped.hasLocation()) {
                throw new AssertionError("IPTC location kept after withoutLocation");
            }
            run.output();
        });
    }

    @FuzzTest
    void metadata(byte[] data) {
        var run = Fuzz.start("metadata");
        var read = run.value("read", () -> ImageMetadata.read(MediaSource.of(data), Fuzz.LIMITS));
        if (read.isEmpty()) {
            return;
        }
        var metadata = read.get();
        metadata.exif();
        metadata.xmp();
        metadata.iptc();
        metadata.colorProfile();
        metadata.comments();
        metadata.text();
        metadata.hasLocation();
        metadata.orientation();
        run.output();
        var decodes = decodes(data);
        edit(run, data, ImageMetadataEdit.stripAll(), decodes, true, true);
        edit(run, data, ImageMetadataEdit.stripLocation(), decodes, false, true);
        edit(run, data, ImageMetadataEdit.builder()
                .orientation(Orientation.ROTATE_180)
                .comments(List.of("fuzz"))
                .limits(Fuzz.LIMITS)
                .build(), decodes, false, false);
        edit(run, data, ImageMetadataEdit.builder().colorProfile(ColorProfile.displayP3()).limits(Fuzz.LIMITS).build(),
                decodes, false, false);
        edit(run, data, ImageMetadataEdit.builder().removeIccProfile().limits(Fuzz.LIMITS).build(), decodes, false,
                false);
    }

    @FuzzTest
    void webpContainer(byte[] data) {
        var run = Fuzz.start("webpContainer");
        if (run.value("parse", () -> WebpContainer.parse(data)).isEmpty()) {
            return;
        }
        WebpContainer.isAnimated(data);
        run.value("exif", () -> WebpContainer.exif(data));
        run.value("xmp", () -> WebpContainer.xmp(data));
        run.value("icc", () -> WebpContainer.iccProfile(data));
        container(run, data, "with EXIF", file -> WebpContainer.withExif(file, WEBP_EXIF), WebpContainer::exif,
                WEBP_EXIF);
        container(run, data, "with XMP", file -> WebpContainer.withXmp(file, WEBP_XMP), WebpContainer::xmp, WEBP_XMP);
        container(run, data, "with ICC", file -> WebpContainer.withIccProfile(file, WEBP_ICC),
                WebpContainer::iccProfile, WEBP_ICC);
        container(run, data, "without EXIF", WebpContainer::removeExif, WebpContainer::exif, null);
        container(run, data, "without XMP", WebpContainer::removeXmp, WebpContainer::xmp, null);
        container(run, data, "without ICC", WebpContainer::removeIccProfile, WebpContainer::iccProfile, null);
    }

    private static void container(FuzzRun run, byte[] data, String name, UnaryOperator<byte[]> edit,
                                  Function<byte[], Optional<byte[]>> read, byte[] expected) {
        run.value(name, () -> edit.apply(data)).ifPresent(file -> {
            var stored = read.apply(file);
            if (expected == null ? stored.isPresent() : !Arrays.equals(stored.orElse(null), expected)) {
                throw new AssertionError(name + " stored " + stored.map(Arrays::toString));
            }
            for (var type : WEBP_IMAGE_CHUNKS) {
                if (!Arrays.deepEquals(chunks(data, type), chunks(file, type))) {
                    throw new AssertionError(name + " changed the " + type + " chunks");
                }
            }
            run.output();
        });
    }

    // the payloads of the chunks of one type in file order; a metadata edit copies the image chunks unchanged, but
    // may move a chunk of one type past another to the order the specification gives
    private static Object[] chunks(byte[] file, String type) {
        var payloads = new ArrayList<byte[]>();
        for (var chunk : WebpContainer.parse(file)) {
            if (chunk.fourCc().equals(type)) {
                payloads.add(Arrays.copyOfRange(file, chunk.payloadOffset(), chunk.payloadOffset() + chunk.size()));
            }
        }
        return payloads.toArray();
    }

    @FuzzTest
    void jpegTransform(byte[] data) {
        var run = Fuzz.start("jpegTransform");
        var detected = run.value("detect", () -> Images.detect(MediaSource.of(data)).orElse(null));
        if (detected.isEmpty() || detected.get() != ImageFormat.JPEG) {
            run.skip("not a JPEG");
            return;
        }
        for (var options : TRANSFORMS) {
            run.value("transform " + options, () -> JpegTransforms.transform(MediaSource.of(data), options))
                    .ifPresent(output -> {
                        try {
                            Images.decode(MediaSource.of(output), ImageDecoderOptions.builder().limits(Fuzz.LIMITS)
                                    .build());
                        } catch (MediaException exception) {
                            throw new AssertionError("a transformed JPEG does not decode", exception);
                        }
                        run.output();
                    });
        }
    }

    private static void edit(FuzzRun run, byte[] data, ImageMetadataEdit edit, boolean decodes, boolean strip,
                             boolean noLocation) {
        run.optional("edit " + edit, () -> ImageMetadataEditor.apply(data, edit)).ifPresent(output -> {
            ImageMetadata reread;
            try {
                reread = ImageMetadata.read(MediaSource.of(output), Fuzz.LIMITS);
            } catch (MediaException exception) {
                throw new AssertionError("an edited image is not readable", exception);
            }
            if (strip && (!onlyRequired(reread.exif()) || reread.xmp().isPresent() || reread.iptc().isPresent()
                    || !reread.comments().isEmpty() || !reread.text().isEmpty())) {
                throw new AssertionError("metadata left after stripAll: " + reread);
            }
            if (noLocation && reread.hasLocation()) {
                throw new AssertionError("location left after " + edit);
            }
            if (decodes && !decodes(output)) {
                throw new AssertionError("an image no longer decodes after a metadata edit");
            }
        });
    }

    // stripAll keeps the IFD0 entries TIFF requires: XResolution, YResolution and ResolutionUnit
    private static boolean onlyRequired(Optional<ExifData> exif) {
        return exif.isEmpty() || (exif.get().exif().isEmpty() && exif.get().gps().isEmpty()
                && exif.get().interop().isEmpty() && Set.of(282, 283, 296).containsAll(exif.get().ifd0().keySet()));
    }

    private static boolean decodes(byte[] data) {
        try {
            Images.decode(MediaSource.of(data), ImageDecoderOptions.builder().limits(Fuzz.LIMITS).build());
            return true;
        } catch (MediaException exception) {
            return false;
        }
    }
}
