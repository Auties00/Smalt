package com.github.auties00.smalt.image.jpeg.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.jpeg.JpegMarkerCopy;
import com.github.auties00.smalt.image.jpeg.JpegTransformOptions;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.limits.LimitChecks;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Carries the images a JPEG file holds after its primary image (the gain map of an Ultra HDR, ISO 21496-1 or Apple
 * file, a depth map, a stereo view, any image of a Multi-Picture Format index) through a lossless transform: each
 * image is transformed on its own, and the index that locates them, the {@code APP2 MPF} segment or the XMP
 * container directory of the primary image, is rewritten for the transformed sizes.
 *
 * <p>Instances are immutable and thread-safe.
 */
final class JpegSecondaries {
    /**
     * The RDF namespace, whose {@code li} elements are the items of the container directory.
     */
    private static final String RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";

    /**
     * The largest data length of a marker segment.
     */
    private static final int MAX_SEGMENT_DATA = 65533;

    /**
     * The transformed images, in file order, without the primary image.
     */
    private final List<byte[]> images;

    /**
     * Whether a Multi-Picture Format index of the primary image locates the images.
     */
    private final boolean index;

    /**
     * The bounds of the assembled file.
     */
    private final MediaLimits limits;

    private JpegSecondaries(List<byte[]> images, boolean index, MediaLimits limits) {
        this.images = images;
        this.index = index;
        this.limits = limits;
    }

    /**
     * Transforms the images a file holds after its primary image.
     *
     * <p>The images are carried only when the transform keeps the whole picture area (no crop, wipe or drop), when
     * the output keeps the segment that locates them ({@code APP2 MPF} under {@link JpegMarkerCopy#ICC} or
     * {@link JpegMarkerCopy#ALL}, the XMP container directory under {@link JpegMarkerCopy#ALL}), and when every image
     * that segment lists is present and transforms.
     *
     * @param data the file
     * @param options the transformation of the primary image
     * @param transform transforms one image, given as a file of its own
     * @return the transformed images, or {@code null} when the output carries none
     * @throws MediaException.Unsupported if {@link JpegTransformOptions#perfect()} is set and the transform is not
     * perfect for an image
     */
    static JpegSecondaries transform(byte[] data, JpegTransformOptions options, UnaryOperator<byte[]> transform) {
        if (options.crop().isPresent() || options.wipe().isPresent() || options.dropImage().isPresent()) {
            return null;
        }
        var headers = JpegContainer.headers(data, 0, data.length);
        JpegContainer.Segment mpf = null;
        JpegContainer.Segment xmp = null;
        for (var segment : headers) {
            if (mpf == null && segment.marker() == 0xE2 && segment.startsWith(data, JpegContainer.MPF_ID)) {
                mpf = segment;
            } else if (xmp == null && segment.marker() == 0xE1 && segment.startsWith(data, JpegXmp.XMP_ID)) {
                xmp = segment;
            }
        }
        var copy = options.markerCopy();
        var kept = mpf != null ? copy == JpegMarkerCopy.ICC || copy == JpegMarkerCopy.ALL : copy == JpegMarkerCopy.ALL;
        if (!kept) {
            return null;
        }
        var images = JpegContainer.images(data, JpegContainer.xmp(data, headers, options.limits()));
        if (images.size() < 2 || images.getFirst().offset() != 0) {
            return null;
        }
        if (mpf != null) {
            var entries = JpegContainer.entries(data, mpf);
            if (entries == null || entries.listed() != images.size()) {
                return null;
            }
        } else {
            if (xmp == null) {
                return null;
            }
            var text = new String(data, xmp.offset(), xmp.length(), StandardCharsets.ISO_8859_1);
            var directory = Directory.of(text);
            if (directory == null || directory.items().size() != images.size()) {
                return null;
            }
        }
        var transformed = new ArrayList<byte[]>(images.size() - 1);
        for (var image : images.subList(1, images.size())) {
            var bytes = Arrays.copyOfRange(data, image.offset(), image.offset() + image.length());
            try {
                transformed.add(transform.apply(bytes));
            } catch (MediaException.Unsupported exception) {
                if (options.perfect() && exception.format().equals(LosslessTransform.IMPERFECT)) {
                    throw exception;
                }
                return null;
            } catch (MediaException | IllegalArgumentException exception) {
                return null;
            }
        }
        return new JpegSecondaries(List.copyOf(transformed), mpf != null, options.limits());
    }

    /**
     * Rewrites the container directory of the primary image's XMP segment for the transformed images: the
     * {@code Item:Length} of each image after the primary one, and the {@code Item:Padding} of every image to zero,
     * as the images follow each other without padding. The directory items are the images in file order; items
     * after the last image (data that is not a JPEG image) are left as they are.
     *
     * @param segment the primary image's main XMP segment
     * @return the rewritten segment; the segment itself when it holds no directory with an item per image, or when
     * the rewritten one would not fit a marker segment
     */
    MarkerSegment xmp(MarkerSegment segment) {
        var text = new String(segment.data(), StandardCharsets.ISO_8859_1);
        var directory = Directory.of(text);
        if (directory == null || directory.items().size() <= images.size()) {
            return segment;
        }
        var result = new StringBuilder(text.length() + 16);
        var at = 0;
        for (var item = 0; item <= images.size(); item++) {
            var span = directory.items().get(item);
            result.append(text, at, span[0]);
            var body = replace(text.substring(span[0], span[1]), directory.prefix(), "Padding", "0");
            if (item > 0) {
                body = replace(body, directory.prefix(), "Length", Integer.toString(images.get(item - 1).length));
            }
            result.append(body);
            at = span[1];
        }
        result.append(text, at, text.length());
        var bytes = result.toString().getBytes(StandardCharsets.ISO_8859_1);
        return bytes.length > MAX_SEGMENT_DATA ? segment : new MarkerSegment(segment.marker(), bytes);
    }

    /**
     * Appends the transformed images to the transformed primary image and records their sizes and offsets in its
     * Multi-Picture Format index.
     *
     * @param primary the transformed primary image, holding the source's index segment unchanged
     * @return the file
     * @throws MediaException.LimitExceeded if the file exceeds the limits
     */
    byte[] assemble(byte[] primary) {
        var total = (long) primary.length;
        for (var image : images) {
            total += image.length;
        }
        LimitChecks.checkAllocation(limits, total);
        var output = Arrays.copyOf(primary, (int) total);
        var at = primary.length;
        for (var image : images) {
            System.arraycopy(image, 0, output, at, image.length);
            at += image.length;
        }
        if (index) {
            locate(output, primary.length);
        }
        return output;
    }

    /**
     * Records the size and offset of every image in the Multi-Picture Format index of the primary image.
     *
     * @param output the file, modified in place
     * @param primaryLength the length of the primary image
     */
    private void locate(byte[] output, int primaryLength) {
        JpegContainer.MpEntries entries = null;
        for (var segment : JpegContainer.headers(output, 0, primaryLength)) {
            if (segment.marker() == 0xE2 && segment.startsWith(output, JpegContainer.MPF_ID)) {
                entries = JpegContainer.entries(output, segment);
                break;
            }
        }
        if (entries == null || entries.fields().length != images.size() + 1) {
            throw new IllegalStateException("the copied Multi-Picture Format index is missing");
        }
        var position = 0L;
        for (var image = 0; image < entries.fields().length; image++) {
            var field = entries.fields()[image];
            var size = image == 0 ? primaryLength : images.get(image - 1).length;
            ByteArrays.put32(output, field + 4, size, entries.little());
            ByteArrays.put32(output, field + 8, image == 0 ? 0 : position - entries.header(), entries.little());
            position += size;
        }
    }

    /**
     * Replaces the value of a container item field, written as an attribute or as an element.
     *
     * @param item the text of the item
     * @param prefix the prefix of the item namespace
     * @param name the field name
     * @param value the new value
     * @return the text with the value replaced, the text itself when the item has no such field
     */
    private static String replace(String item, String prefix, String name, String value) {
        var qualified = Pattern.quote(prefix + ":" + name);
        var attribute = Pattern.compile("(" + qualified + "\\s*=\\s*([\"']))[^\"']*(\\2)").matcher(item);
        if (attribute.find()) {
            return attribute.replaceFirst("$1" + Matcher.quoteReplacement(value) + "$3");
        }
        var element = Pattern.compile("(<" + qualified + ">)[^<]*(</" + qualified + ">)").matcher(item);
        return element.find() ? element.replaceFirst("$1" + Matcher.quoteReplacement(value) + "$2") : item;
    }

    /**
     * The container directory of an XMP packet.
     *
     * @param prefix the prefix of the item namespace
     * @param items the start and end of the text of each directory item, in order
     */
    private record Directory(String prefix, List<int[]> items) {
        /**
         * Finds the container directory of an XMP packet.
         *
         * @param text the packet, one character per byte
         * @return the directory, or {@code null} when the packet declares none
         */
        static Directory of(String text) {
            var container = prefix(text, JpegContainer.CONTAINER);
            var item = prefix(text, JpegContainer.ITEM);
            var rdf = prefix(text, RDF);
            if (container == null || item == null || rdf == null) {
                return null;
            }
            var open = Pattern.compile("<" + Pattern.quote(container + ":Directory") + "[\\s>]").matcher(text);
            if (!open.find()) {
                return null;
            }
            var close = text.indexOf("</" + container + ":Directory>", open.end());
            if (close < 0) {
                return null;
            }
            var li = Pattern.compile("<" + Pattern.quote(rdf + ":li") + "[\\s/>]").matcher(text);
            li.region(open.end(), close);
            var starts = new ArrayList<Integer>();
            while (li.find()) {
                starts.add(li.start());
            }
            var items = new ArrayList<int[]>(starts.size());
            for (var i = 0; i < starts.size(); i++) {
                items.add(new int[]{starts.get(i), i + 1 < starts.size() ? starts.get(i + 1) : close});
            }
            return new Directory(item, items);
        }

        /**
         * {@return the prefix a packet declares for a namespace, or {@code null}}
         */
        private static String prefix(String text, String namespace) {
            var matcher = Pattern.compile("xmlns:([A-Za-z_][\\w.-]*)\\s*=\\s*([\"'])" + Pattern.quote(namespace)
                    + "\\2").matcher(text);
            return matcher.find() ? matcher.group(1) : null;
        }
    }
}
