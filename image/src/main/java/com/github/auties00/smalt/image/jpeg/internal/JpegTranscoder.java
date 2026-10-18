/* Ports libjpeg-turbo (jpegtran.c, jctrans.c, transupp.c). Copyright (C) 1995-2019, Thomas G. Lane, Guido
   Vollbeding; Copyright (C) 2010-2024, D. R. Commander; IJG License. Modified for Smalt. */
package com.github.auties00.smalt.image.jpeg.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.exif.ExifReader;
import com.github.auties00.smalt.image.exif.ExifWriter;
import com.github.auties00.smalt.image.jpeg.JpegMarkerCopy;
import com.github.auties00.smalt.image.jpeg.JpegTransformOptions;
import com.github.auties00.smalt.internal.io.ByteArrays;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Transforms a JPEG file losslessly: decodes its quantized DCT coefficients, rotates, flips,
 * crops or drops them, and writes a new file from them with the source's quantization tables.
 *
 * @implNote This implementation follows the sequence of jpegtran: the marker segments of
 * {@code jcopy_markers_setup}, the parameters of {@code jpeg_copy_critical_parameters} and
 * {@code jtransform_adjust_parameters} (with its update of the EXIF image size), the transform of
 * {@link LosslessTransform}, and the output of {@link CoefficientWriter}.
 */
public final class JpegTranscoder {
    /**
     * The largest data length of a marker segment.
     */
    private static final int MAX_SEGMENT_DATA = 65533;

    /**
     * Prevents instantiation.
     */
    private JpegTranscoder() {
        throw new AssertionError();
    }

    /**
     * Transforms a JPEG file.
     *
     * @param data the file
     * @param options the transformation
     * @return the transformed file
     * @throws IllegalArgumentException if the crop region does not fit the image
     * @throws MediaException.Malformed if the file is not a valid JPEG stream
     * @throws MediaException.Unsupported if the file uses a coding feature the decoder does not
     * support, or the transformation is impossible for it
     * @throws MediaException.LimitExceeded if the image exceeds the limits
     */
    public static byte[] transcode(byte[] data, JpegTransformOptions options) {
        return transcode(data, options, false);
    }

    /**
     * Transforms a JPEG file so that it displays upright without its EXIF orientation: the
     * transform is the orientation of the file's first EXIF segment, replacing the options' one,
     * and the orientation is reset.
     *
     * @param data the file
     * @param options the other settings of the transformation
     * @return the upright file
     * @throws IllegalArgumentException if the crop region does not fit the upright image
     * @throws MediaException.Malformed if the file is not a valid JPEG stream
     * @throws MediaException.Unsupported if the file uses a coding feature the decoder does not
     * support, or the transformation is impossible for it
     * @throws MediaException.LimitExceeded if the image exceeds the limits
     */
    public static byte[] autoOrient(byte[] data, JpegTransformOptions options) {
        return transcode(data, options, true);
    }

    /**
     * Transforms a JPEG file, taking the transform from its EXIF orientation when asked.
     *
     * @param data the file
     * @param options the transformation
     * @param autoOrient whether the transform is the EXIF orientation and the orientation is reset
     * @return the transformed file
     */
    private static byte[] transcode(byte[] data, JpegTransformOptions options, boolean autoOrient) {
        // TODO: arithmetic-coded output, 12-bit frames (rejected by the decoder), restart intervals in MCU
        //  rows, and jpegtran's -wipe, -drop and crop extension
        var reader = new JpegReader(data, 0, data.length, options.limits());
        var coefficients = reader.decodeCoefficients();
        if (autoOrient) {
            options = options.toBuilder().transform(ExifReader.orientation(reader.exif())).resetOrientation(true)
                    .build();
        }
        var frame = coefficients.frame();
        var colorSpace = reader.colorSpace();
        var frameComponents = frame.components;
        var count = frameComponents.length;
        var transform = options.transform();
        var grayscale = options.grayscale();
        var outputCount = grayscale && colorSpace == JpegColorSpace.YCBCR && count == 3 ? 1 : count;
        if (grayscale) {
            var first = frameComponents[0];
            var valid = (colorSpace == JpegColorSpace.YCBCR && count == 3 || colorSpace == JpegColorSpace.GRAY && count == 1)
                    && first.h == frame.hMax && first.v == frame.vMax;
            if (!valid) {
                throw new MediaException.Unsupported("jpeg grayscale conversion",
                        "only a YCbCr or gray JPEG with full-resolution luma converts to grayscale losslessly");
            }
        }
        var samplingX = new int[count];
        var samplingY = new int[count];
        var source = new CoefficientPlane[count];
        for (var i = 0; i < count; i++) {
            var component = frameComponents[i];
            samplingX[i] = component.h;
            samplingY[i] = component.v;
            source[i] = new CoefficientPlane(component.paddedBlocksWide, component.paddedBlocksHigh,
                    coefficients.coefficients(i));
        }
        var crop = options.crop()
                .map(region -> new int[]{region.x(), region.y(), region.width(), region.height()})
                .orElse(null);
        var plan = new LosslessTransform(transform, frame.width, frame.height, samplingX, samplingY, outputCount,
                crop, options.perfect(), options.trim());
        var quantTables = new int[4][];
        var components = new CoefficientWriter.Component[outputCount];
        var planes = plan.execute(source, options.limits());
        var transposed = transform.swapsDimensions();
        for (var i = 0; i < outputCount; i++) {
            var component = frameComponents[i];
            var slot = component.quantSelector;
            var table = coefficients.quantization(i).clone();
            if (transposed) {
                for (var row = 0; row < 8; row++) {
                    for (var column = 0; column < row; column++) {
                        var swap = table[row * 8 + column];
                        table[row * 8 + column] = table[column * 8 + row];
                        table[column * 8 + row] = swap;
                    }
                }
            }
            if (quantTables[slot] == null) {
                quantTables[slot] = table;
            } else if (!Arrays.equals(quantTables[slot], table)) {
                throw new MediaException.Unsupported("jpeg reused quantization slot",
                        "components sharing quantization slot " + slot + " were coded with different tables");
            }
            int id;
            int h;
            int v;
            if (outputCount == 1) {
                id = grayscale ? 1 : component.id;
                h = 1;
                v = 1;
            } else if (transposed) {
                id = component.id;
                h = component.v;
                v = component.h;
            } else {
                id = component.id;
                h = component.h;
                v = component.v;
            }
            var chroma = !grayscale && (colorSpace == JpegColorSpace.YCBCR || colorSpace == JpegColorSpace.YCCK)
                    && (i == 1 || i == 2);
            var huffman = chroma ? 1 : 0;
            components[i] = new CoefficientWriter.Component(id, h, v, slot, huffman, huffman, planes[i],
                    plan.blocksWide(i), plan.blocksHigh(i));
        }
        var outputSpace = grayscale ? JpegColorSpace.GRAY : colorSpace;
        var segments = segments(data);
        var markers = new ArrayList<MarkerSegment>();
        var jfifSeen = false;
        var jfifMajor = 1;
        var jfifMinor = 1;
        var densityUnit = 0;
        var densityX = 1;
        var densityY = 1;
        var copy = options.markerCopy();
        for (var segment : segments) {
            var marker = segment.marker();
            var bytes = segment.data();
            if (marker == 0xE0 && bytes.length >= 14 && segment.startsWith("JFIF\0")) {
                jfifSeen = true;
                jfifMajor = bytes[5] & 0xFF;
                jfifMinor = bytes[6] & 0xFF;
                densityUnit = bytes[7] & 0xFF;
                densityX = ByteArrays.u16be(bytes, 8);
                densityY = ByteArrays.u16be(bytes, 10);
            }
            var copied = switch (copy) {
                case NONE -> false;
                case COMMENTS -> marker == 0xFE;
                case ICC -> marker == 0xE2;
                case ALL -> true;
            };
            if (copied) {
                markers.add(segment);
            }
        }
        var outputMajor = 1;
        var outputMinor = 1;
        var outputUnit = 0;
        var outputX = 1;
        var outputY = 1;
        if (jfifSeen) {
            if (jfifMajor == 1) {
                outputMajor = jfifMajor;
                outputMinor = jfifMinor;
            }
            outputUnit = densityUnit;
            outputX = densityX;
            outputY = densityY;
        }
        var jfif = outputSpace == JpegColorSpace.GRAY || outputSpace == JpegColorSpace.YCBCR;
        var adobe = outputSpace == JpegColorSpace.RGB || outputSpace == JpegColorSpace.CMYK
                || outputSpace == JpegColorSpace.YCCK;
        var adobeTransform = outputSpace == JpegColorSpace.YCCK ? 2 : 0;
        if (!markers.isEmpty() && markers.getFirst().isExif()) {
            jfif = false;
            var resized = plan.outputWidth() != frame.width || plan.outputHeight() != frame.height;
            markers.set(0, editExif(markers.getFirst(), options, resized, plan.outputWidth(), plan.outputHeight()));
        } else {
            for (var i = 0; i < markers.size(); i++) {
                if (markers.get(i).isExif()) {
                    markers.set(i, editExif(markers.get(i), options, false, 0, 0));
                    break;
                }
            }
        }
        var header = new CoefficientWriter.Header(jfif, outputMajor, outputMinor, outputUnit, outputX, outputY, adobe,
                adobeTransform);
        var writer = new CoefficientWriter(plan.outputWidth(), plan.outputHeight(), components, quantTables,
                options.restartInterval());
        return writer.write(header, markers, options.progressive(), options.optimizeHuffman(),
                outputSpace == JpegColorSpace.YCBCR);
    }

    /**
     * Edits a copied EXIF segment: resets its orientation and transforms its thumbnail as the
     * options ask, and records a new image size in its {@code ExifImageWidth} and
     * {@code ExifImageHeight} tags.
     *
     * <p>An edit that fails, or that would not fit a marker segment, is skipped; a thumbnail that
     * cannot be transformed is removed rather than left with the wrong geometry.
     *
     * @param segment the copied segment
     * @param options the transformation
     * @param resized whether to record the new image size
     * @param width the new image width
     * @param height the new image height
     * @return the edited segment
     */
    private static MarkerSegment editExif(MarkerSegment segment, JpegTransformOptions options, boolean resized,
                                          int width, int height) {
        var data = segment.data();
        var tiff = Arrays.copyOfRange(data, JpegExif.PREFIX, data.length);
        var edited = tiff;
        try {
            if (options.resetOrientation() && ExifReader.orientation(edited) != Orientation.NORMAL) {
                edited = ExifWriter.withOrientation(edited, Orientation.NORMAL);
            }
            var thumbnailChange = options.transform() != Orientation.NORMAL || options.grayscale();
            if (options.transformThumbnail() && thumbnailChange) {
                var exif = ExifReader.parse(edited);
                var thumbnail = exif.thumbnail();
                if (thumbnail.isPresent()) {
                    var thumbnailOptions = options.toBuilder()
                            .noCrop()
                            .markerCopy(JpegMarkerCopy.NONE)
                            .trim(true)
                            .perfect(false)
                            .resetOrientation(false)
                            .transformThumbnail(false)
                            .restartInterval(0)
                            .build();
                    byte[] transformed;
                    try {
                        transformed = transcode(thumbnail.get(), thumbnailOptions);
                    } catch (MediaException | IllegalArgumentException exception) {
                        transformed = null;
                    }
                    var updated = transformed != null ? exif.withThumbnail(transformed) : exif.withoutThumbnail();
                    edited = ExifWriter.rewrite(edited, updated);
                }
            }
        } catch (MediaException | IllegalArgumentException exception) {
            edited = tiff;
        }
        if (resized) {
            edited = edited == tiff ? tiff.clone() : edited;
            adjustImageSize(edited, width, height);
        }
        if (edited.length + JpegExif.PREFIX > MAX_SEGMENT_DATA) {
            return segment;
        }
        var result = Arrays.copyOf(data, JpegExif.PREFIX + edited.length);
        System.arraycopy(edited, 0, result, JpegExif.PREFIX, edited.length);
        return new MarkerSegment(segment.marker(), result);
    }

    /**
     * Records a new image size in the {@code ExifImageWidth} and {@code ExifImageHeight} tags of
     * the Exif private directory, rewriting each as a single {@code LONG}.
     *
     * @implNote This implementation ports {@code adjust_exif_parameters}, which only follows 16-bit
     * offsets.
     * @param data the EXIF block, starting with its byte-order mark; modified in place
     * @param width the image width
     * @param height the image height
     */
    private static void adjustImageSize(byte[] data, int width, int height) {
        var length = data.length;
        if (length < 12) {
            return;
        }
        boolean motorola;
        if (data[0] == 0x49 && data[1] == 0x49) {
            motorola = false;
        } else if (data[0] == 0x4D && data[1] == 0x4D) {
            motorola = true;
        } else {
            return;
        }
        if (motorola ? data[2] != 0 || data[3] != 0x2A : data[3] != 0 || data[2] != 0x2A) {
            return;
        }
        int first;
        if (motorola) {
            if (data[4] != 0 || data[5] != 0) {
                return;
            }
            first = ByteArrays.u16be(data, 6);
        } else {
            if (data[7] != 0 || data[6] != 0) {
                return;
            }
            first = ByteArrays.u16le(data, 4);
        }
        if (first > length - 2) {
            return;
        }
        var tags = ByteArrays.u16(data, first, !motorola);
        if (tags == 0) {
            return;
        }
        first += 2;
        while (true) {
            if (first > length - 12) {
                return;
            }
            if (ByteArrays.u16(data, first, !motorola) == 0x8769) {
                break;
            }
            if (--tags == 0) {
                return;
            }
            first += 12;
        }
        int offset;
        if (motorola) {
            if (data[first + 8] != 0 || data[first + 9] != 0) {
                return;
            }
            offset = ByteArrays.u16be(data, first + 10);
        } else {
            if (data[first + 11] != 0 || data[first + 10] != 0) {
                return;
            }
            offset = ByteArrays.u16le(data, first + 8);
        }
        if (offset > length - 2) {
            return;
        }
        tags = ByteArrays.u16(data, offset, !motorola);
        if (tags < 2) {
            return;
        }
        offset += 2;
        do {
            if (offset > length - 12) {
                return;
            }
            var tag = ByteArrays.u16(data, offset, !motorola);
            if (tag == 0xA002 || tag == 0xA003) {
                var value = tag == 0xA002 ? width : height;
                if (motorola) {
                    data[offset + 2] = 0;
                    data[offset + 3] = 4;
                    data[offset + 4] = 0;
                    data[offset + 5] = 0;
                    data[offset + 6] = 0;
                    data[offset + 7] = 1;
                    data[offset + 8] = 0;
                    data[offset + 9] = 0;
                    data[offset + 10] = (byte) (value >> 8);
                    data[offset + 11] = (byte) value;
                } else {
                    data[offset + 2] = 4;
                    data[offset + 3] = 0;
                    data[offset + 4] = 1;
                    data[offset + 5] = 0;
                    data[offset + 6] = 0;
                    data[offset + 7] = 0;
                    data[offset + 8] = (byte) value;
                    data[offset + 9] = (byte) (value >> 8);
                    data[offset + 10] = 0;
                    data[offset + 11] = 0;
                }
            }
            offset += 12;
        } while (--tags != 0);
    }

    /**
     * Collects the comment and application segments of a file in order, from the start of the
     * file to its EOI marker, skipping entropy-coded data.
     *
     * @param data the file
     * @return the segments, in file order
     */
    private static List<MarkerSegment> segments(byte[] data) {
        var segments = new ArrayList<MarkerSegment>();
        var length = data.length;
        if (length < 2 || (data[0] & 0xFF) != 0xFF || (data[1] & 0xFF) != 0xD8) {
            return segments;
        }
        var position = 2;
        while (position < length) {
            if ((data[position] & 0xFF) != 0xFF) {
                position++;
                continue;
            }
            while (position < length && (data[position] & 0xFF) == 0xFF) {
                position++;
            }
            if (position >= length) {
                break;
            }
            var marker = data[position++] & 0xFF;
            if (marker == 0xD9) {
                break;
            }
            if (marker == 0 || marker == 0x01 || marker == 0xD8 || marker >= 0xD0 && marker <= 0xD7) {
                continue;
            }
            if (position + 2 > length) {
                break;
            }
            var size = ((data[position] & 0xFF) << 8) | (data[position + 1] & 0xFF);
            if (size < 2 || position + size > length) {
                break;
            }
            if (marker == 0xFE || marker >= 0xE0 && marker <= 0xEF) {
                segments.add(new MarkerSegment(marker, Arrays.copyOfRange(data, position + 2, position + size)));
            }
            position += size;
            if (marker == 0xDA) {
                while (position + 1 < length) {
                    if ((data[position] & 0xFF) == 0xFF) {
                        var next = data[position + 1] & 0xFF;
                        if (next == 0 || next >= 0xD0 && next <= 0xD7) {
                            position += 2;
                            continue;
                        }
                        if (next != 0xFF) {
                            break;
                        }
                    }
                    position++;
                }
            }
        }
        return segments;
    }
}
