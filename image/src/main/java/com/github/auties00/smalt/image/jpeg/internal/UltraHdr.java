/* Ports libultrahdr 1.4.0 (lib/src/jpegr.cpp, JpegR::appendGainMap; lib/src/multipictureformat.cpp). Copyright 2022
 * The Android Open Source Project; Apache License 2.0. Modified for Smalt. */
package com.github.auties00.smalt.image.jpeg.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.AuxiliaryImage;
import com.github.auties00.smalt.image.AuxiliaryImageType;
import com.github.auties00.smalt.image.gainmap.GainMapChannel;
import com.github.auties00.smalt.image.gainmap.GainMapMetadata;
import com.github.auties00.smalt.internal.gainmap.AppleGainMaps;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.metadata.XmpPacket;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.ToDoubleFunction;

/**
 * Reads and writes JPEG files with a gain map: Ultra HDR files (Google's Ultra HDR image format 1.1, with the
 * ISO 21496-1 metadata) and the Adobe gain map JPEG files, whose gain map is a secondary image of the
 * Multi-Picture Format index or of the XMP container directory.
 *
 * <p>This class is thread-safe.
 */
public final class UltraHdr {
    /**
     * The MPF type code of a baseline primary image.
     */
    private static final int BASELINE_PRIMARY = 0x030000;

    /**
     * The start of the {@code rdf:Description} element of {@link XmpPacket#toXml()}.
     */
    private static final String DESCRIPTION_START = "  <rdf:Description ";

    /**
     * The end of the {@code rdf:Description} element of {@link XmpPacket#toXml()}, its line feed included.
     */
    private static final String DESCRIPTION_END = "  </rdf:Description>\n";

    private UltraHdr() {
        throw new AssertionError();
    }

    /**
     * A secondary image of a file with its gain map metadata.
     *
     * @param image the location of the image in the file
     * @param metadata the gain map metadata of its header, or {@code null} when it is not a gain map
     * @param xmp the XMP packet of its header, or {@code null}
     */
    public record Secondary(JpegContainer.Image image, GainMapMetadata metadata, XmpPacket xmp) {
        /**
         * {@return whether the image is an Apple gain map, whose XMP declares {@code HDRGainMap:HDRGainMapVersion}}
         */
        public boolean isApple() {
            return xmp != null && xmp.text(AppleGainMaps.XMP_NAMESPACE, "HDRGainMapVersion").isPresent();
        }
    }

    /**
     * Lists the secondary images of a file.
     *
     * <p>The gain map metadata of an image comes from its ISO 21496-1 {@code APP2} segment, else from the
     * {@code hdrgm} properties of its XMP packet; metadata that does not parse leaves the image without it.
     *
     * @param data the file
     * @param limits the bounds of the XMP packets
     * @return the images after the primary one, in file order
     * @throws MediaException.Malformed if the file does not start with an SOI marker
     */
    public static List<Secondary> secondaries(byte[] data, MediaLimits limits) {
        var primary = JpegContainer.headers(data, 0, data.length);
        var images = JpegContainer.images(data, JpegContainer.xmp(data, primary, limits));
        var result = new ArrayList<Secondary>();
        for (var i = 1; i < images.size(); i++) {
            var image = images.get(i);
            result.add(new Secondary(image, metadata(data, image, limits), xmp(data, image, limits)));
        }
        return result;
    }

    /**
     * Reads the XMP packet of an image.
     *
     * @param data the file
     * @param image the location of the image in the file
     * @param limits the bounds of the packet
     * @return the packet, or {@code null}
     */
    private static XmpPacket xmp(byte[] data, JpegContainer.Image image, MediaLimits limits) {
        try {
            return JpegContainer.xmp(data, JpegContainer.headers(data, image.offset(), image.offset() + image.length()),
                    limits);
        } catch (MediaException.Malformed exception) {
            return null;
        }
    }

    /**
     * Reads the gain map metadata of an image.
     *
     * @param data the file
     * @param image the location of the image in the file
     * @param limits the bounds of the XMP packet
     * @return the metadata, or {@code null}
     */
    private static GainMapMetadata metadata(byte[] data, JpegContainer.Image image, MediaLimits limits) {
        List<JpegContainer.Segment> headers;
        try {
            headers = JpegContainer.headers(data, image.offset(), image.offset() + image.length());
        } catch (MediaException.Malformed exception) {
            return null;
        }
        var iso = JpegContainer.isoMetadata(data, headers);
        if (iso != null) {
            try {
                return GainMapMetadata.parse(iso);
            } catch (MediaException.Malformed | MediaException.Unsupported exception) {
                // a reader falls back to the XMP metadata
            }
        }
        var xmp = JpegContainer.xmp(data, headers, limits);
        if (xmp == null) {
            return null;
        }
        try {
            return GainMapMetadata.fromXmp(xmp).orElse(null);
        } catch (MediaException.Malformed exception) {
            return null;
        }
    }

    /**
     * Describes a secondary image as an auxiliary image.
     *
     * @param index the index of the image in the list
     * @param secondary the image
     * @param width the declared width
     * @param height the declared height
     * @return the description
     */
    public static AuxiliaryImage describe(int index, Secondary secondary, int width, int height) {
        var image = secondary.image();
        var semantic = image.semantic();
        var identifier = image.type() >= 0
                ? String.format(Locale.ROOT, "mpf:%06X", image.type()) + (semantic == null ? "" : "/" + semantic)
                : "container:" + (semantic == null ? "" : semantic);
        return new AuxiliaryImage(index, type(secondary), identifier, width, height);
    }

    /**
     * {@return the role of a secondary image}
     */
    private static AuxiliaryImageType type(Secondary secondary) {
        var image = secondary.image();
        if (secondary.metadata() != null || secondary.isApple() || "GainMap".equalsIgnoreCase(image.semantic())) {
            return AuxiliaryImageType.GAIN_MAP;
        }
        if ("Depth".equalsIgnoreCase(image.semantic())) {
            return AuxiliaryImageType.DEPTH;
        }
        return switch (image.type()) {
            case 0x010001, 0x010002 -> AuxiliaryImageType.THUMBNAIL;
            case 0x020001, 0x020003, BASELINE_PRIMARY -> AuxiliaryImageType.VIEW;
            case 0x020002 -> AuxiliaryImageType.DISPARITY;
            default -> AuxiliaryImageType.OTHER;
        };
    }

    /**
     * Joins a primary image and a gain map image into an Ultra HDR file.
     *
     * <p>The primary image gains, after its SOI marker and JFIF {@code APP0} segment, an XMP packet with
     * {@code hdrgm:Version} and the container directory of the two images, an ISO 21496-1 {@code APP2} segment
     * with the two version fields, and a Multi-Picture Format index of the two images. The gain map image gains
     * an XMP packet with the {@code hdrgm} metadata (when the metadata applies in the base color space, which
     * the XMP form cannot otherwise say) and an ISO 21496-1 {@code APP2} segment with the full metadata.
     *
     * <p>A packet of the caller is written in the XMP packet of its image, as a description of its own after
     * the Ultra HDR one, without its {@code hdrgm} and {@code Container} properties, which the Ultra HDR
     * description replaces. When the packet of an image does not fit one segment, the largest properties of
     * the caller move to an Extended XMP packet; the Ultra HDR description stays in the main packet, where
     * readers look for it.
     *
     * @param primary the JPEG file of the primary image, without an XMP segment
     * @param gainMap the JPEG file of the gain map image, without an XMP segment
     * @param metadata the gain map metadata
     * @param primaryXmp the caller's XMP packet of the primary image, or {@code null}
     * @param gainMapXmp the caller's XMP packet of the gain map image, or {@code null}
     * @return the file
     * @throws MediaException.LimitExceeded if a packet cannot be split to fit its segments
     */
    public static byte[] write(byte[] primary, byte[] gainMap, GainMapMetadata metadata, XmpPacket primaryXmp,
                               XmpPacket gainMapXmp) {
        var secondary = new ByteArrayOutputStream(gainMap.length + 1024);
        var gainMapHeader = insertionPoint(gainMap);
        secondary.write(gainMap, 0, gainMapHeader);
        var callerGainMap = callerPacket(gainMapXmp);
        if (metadata.useBaseColorSpace()) {
            xmp(secondary, secondaryDescription(metadata), callerGainMap);
        } else if (callerGainMap != null) {
            xmp(secondary, "", callerGainMap);
        }
        segment(secondary, 0xE2, JpegContainer.ISO_ID, metadata.toByteArray());
        secondary.write(gainMap, gainMapHeader, gainMap.length - gainMapHeader);
        var gainMapFile = secondary.toByteArray();
        var head = new ByteArrayOutputStream();
        var primaryHeader = insertionPoint(primary);
        head.write(primary, 0, primaryHeader);
        xmp(head, primaryDescription(gainMapFile.length), callerPacket(primaryXmp));
        segment(head, 0xE2, JpegContainer.ISO_ID, new byte[4]);
        var mpfMarker = head.size();
        var mpf = new byte[JpegContainer.MPF_ID.length + 8 + 2 + 3 * 12 + 4 + 2 * 16];
        var mpfSegmentSize = 4 + mpf.length;
        var primarySize = (long) mpfMarker + mpfSegmentSize + primary.length - primaryHeader;
        var tiffHeader = mpfMarker + 4 + JpegContainer.MPF_ID.length;
        writeMpf(mpf, primarySize, gainMapFile.length, primarySize - tiffHeader);
        segment(head, 0xE2, new byte[0], mpf);
        var out = new ByteArrayOutputStream((int) primarySize + gainMapFile.length);
        out.writeBytes(head.toByteArray());
        out.write(primary, primaryHeader, primary.length - primaryHeader);
        out.writeBytes(gainMapFile);
        return out.toByteArray();
    }

    /**
     * {@return the properties of a caller's packet that an Ultra HDR file can carry: all but its {@code hdrgm}
     * and {@code Container} properties and its stale {@code xmpNote:HasExtendedXMP}; {@code null} when none
     * remain}
     *
     * @param packet the caller's packet, or {@code null}
     */
    private static XmpPacket callerPacket(XmpPacket packet) {
        if (packet == null) {
            return null;
        }
        var kept = packet.withoutNamespace(GainMapMetadata.XMP_NAMESPACE).withoutNamespace(JpegContainer.CONTAINER)
                .without(XmpPacket.XMP_NOTE, JpegXmp.HAS_EXTENDED);
        return kept.isEmpty() ? null : kept;
    }

    /**
     * Writes the XMP segments of an image: its Ultra HDR description, followed by a description of the
     * caller's properties.
     *
     * @param out the output
     * @param description the Ultra HDR {@code rdf:Description} element, or an empty string for none
     * @param caller the caller's properties, or {@code null}
     * @throws MediaException.LimitExceeded if the packet cannot be split to fit its segments
     */
    private static void xmp(ByteArrayOutputStream out, String description, XmpPacket caller) {
        if (caller == null) {
            segment(out, 0xE1, JpegXmp.XMP_ID, packet(description).getBytes(StandardCharsets.UTF_8));
            return;
        }
        var payloads = JpegXmp.payloads(caller, properties -> packet(description + description(properties))
                .getBytes(StandardCharsets.UTF_8));
        for (var payload : payloads) {
            segment(out, 0xE1, new byte[0], payload);
        }
    }

    /**
     * {@return the {@code rdf:Description} element of a packet's canonical serialization, which declares the
     * namespaces of its properties, or an empty string for a packet without properties}
     *
     * <p>A prefix of the Ultra HDR description ({@code hdrgm}, {@code Container}, {@code Item}) that the
     * packet binds to another namespace is renamed, since readers of Ultra HDR files match these prefixes
     * literally.
     *
     * @param packet the packet to describe
     */
    private static String description(XmpPacket packet) {
        if (packet.isEmpty()) {
            return "";
        }
        var xml = packet.toXml();
        var start = xml.indexOf(DESCRIPTION_START);
        var end = xml.lastIndexOf(DESCRIPTION_END);
        if (start < 0 || end < start) {
            throw new IllegalStateException("unexpected XMP serialization");
        }
        var text = xml.substring(start, end + DESCRIPTION_END.length());
        text = renamePrefix(text, "hdrgm", GainMapMetadata.XMP_NAMESPACE);
        text = renamePrefix(text, "Container", JpegContainer.CONTAINER);
        return renamePrefix(text, "Item", JpegContainer.ITEM);
    }

    /**
     * {@return a canonical {@code rdf:Description} element with a prefix renamed to an unused one when the
     * element binds it to a namespace other than {@code namespace}}
     *
     * <p>The canonical form declares every prefix on the start tag and names properties only in element
     * tags, and it escapes {@code <} in text, so the prefix appears nowhere else.
     *
     * @param description the element, as {@link #description(XmpPacket)} cuts it
     * @param prefix the reserved prefix
     * @param namespace the namespace the prefix stands for in an Ultra HDR file
     */
    private static String renamePrefix(String description, String prefix, String namespace) {
        var declaration = "xmlns:" + prefix + "=\"";
        var startTag = description.substring(0, description.indexOf(">\n") + 1);
        var at = startTag.indexOf(declaration);
        if (at < 0 || startTag.startsWith(namespace + "\"", at + declaration.length())) {
            return description;
        }
        var counter = 1;
        while (startTag.contains("xmlns:" + prefix + counter + "=\"")) {
            counter++;
        }
        var renamed = prefix + counter;
        return description.replace(declaration, "xmlns:" + renamed + "=\"")
                .replace("<" + prefix + ":", "<" + renamed + ":")
                .replace("</" + prefix + ":", "</" + renamed + ":");
    }

    /**
     * {@return an XMP packet of {@code rdf:Description} elements}
     *
     * @param descriptions the elements, each ending with a line feed
     */
    private static String packet(String descriptions) {
        return "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"Smalt\">\n"
                + " <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n" + descriptions
                + " </rdf:RDF>\n</x:xmpmeta>";
    }

    /**
     * {@return the index after the SOI marker and a JFIF {@code APP0} segment that follows it}
     */
    private static int insertionPoint(byte[] jpeg) {
        var at = 2;
        if (jpeg.length >= 6 && (jpeg[2] & 0xFF) == 0xFF && (jpeg[3] & 0xFF) == 0xE0) {
            at += 2 + ByteArrays.u16(jpeg, 4, false);
        }
        return at;
    }

    /**
     * Writes a marker segment.
     *
     * @param out the output
     * @param marker the marker code
     * @param id the identifier that starts the payload
     * @param payload the rest of the payload
     * @throws MediaException.LimitExceeded if the payload exceeds a segment
     */
    private static void segment(ByteArrayOutputStream out, int marker, byte[] id, byte[] payload) {
        var length = 2 + id.length + payload.length;
        if (length > 0xFFFF) {
            throw new MediaException.LimitExceeded("the gain map metadata exceeds a JPEG segment");
        }
        out.write(0xFF);
        out.write(marker);
        out.write(length >>> 8);
        out.write(length);
        out.writeBytes(id);
        out.writeBytes(payload);
    }

    /**
     * Fills a big-endian Multi-Picture Format index of two images.
     *
     * @param mpf the payload, after the segment length, of the right size
     * @param primarySize the size of the primary image
     * @param secondarySize the size of the gain map image
     * @param secondaryOffset the offset of the gain map image from the TIFF header
     */
    private static void writeMpf(byte[] mpf, long primarySize, long secondarySize, long secondaryOffset) {
        System.arraycopy(JpegContainer.MPF_ID, 0, mpf, 0, 4);
        var tiff = 4;
        mpf[tiff] = 'M';
        mpf[tiff + 1] = 'M';
        mpf[tiff + 3] = 0x2A;
        ByteArrays.put32(mpf, tiff + 4, 8, false);
        var ifd = tiff + 8;
        ByteArrays.put16(mpf, ifd, 3, false);
        var entry = ifd + 2;
        ByteArrays.put16(mpf, entry, 0xB000, false);
        ByteArrays.put16(mpf, entry + 2, 7, false);
        ByteArrays.put32(mpf, entry + 4, 4, false);
        mpf[entry + 8] = '0';
        mpf[entry + 9] = '1';
        mpf[entry + 10] = '0';
        mpf[entry + 11] = '0';
        entry += 12;
        ByteArrays.put16(mpf, entry, 0xB001, false);
        ByteArrays.put16(mpf, entry + 2, 4, false);
        ByteArrays.put32(mpf, entry + 4, 1, false);
        ByteArrays.put32(mpf, entry + 8, 2, false);
        entry += 12;
        var entries = entry + 12 + 4;
        ByteArrays.put16(mpf, entry, 0xB002, false);
        ByteArrays.put16(mpf, entry + 2, 7, false);
        ByteArrays.put32(mpf, entry + 4, 32, false);
        ByteArrays.put32(mpf, entry + 8, entries - tiff, false);
        ByteArrays.put32(mpf, entry + 12, 0, false);
        ByteArrays.put32(mpf, entries, BASELINE_PRIMARY, false);
        ByteArrays.put32(mpf, entries + 4, primarySize, false);
        ByteArrays.put32(mpf, entries + 8, 0, false);
        ByteArrays.put32(mpf, entries + 16, 0, false);
        ByteArrays.put32(mpf, entries + 20, secondarySize, false);
        ByteArrays.put32(mpf, entries + 24, secondaryOffset, false);
    }

    /**
     * {@return the Ultra HDR {@code rdf:Description} element of the primary image}
     *
     * @param gainMapLength the size of the gain map image
     */
    private static String primaryDescription(int gainMapLength) {
        return "  <rdf:Description rdf:about=\"\" xmlns:Container=\"" + JpegContainer.CONTAINER + "\"\n"
                + "    xmlns:Item=\"" + JpegContainer.ITEM + "\" xmlns:hdrgm=\"" + GainMapMetadata.XMP_NAMESPACE
                + "\"\n    hdrgm:Version=\"1.0\">\n"
                + "   <Container:Directory>\n    <rdf:Seq>\n"
                + "     <rdf:li rdf:parseType=\"Resource\">\n"
                + "      <Container:Item Item:Semantic=\"Primary\" Item:Mime=\"image/jpeg\"/>\n"
                + "     </rdf:li>\n"
                + "     <rdf:li rdf:parseType=\"Resource\">\n"
                + "      <Container:Item Item:Semantic=\"GainMap\" Item:Mime=\"image/jpeg\" Item:Length=\""
                + gainMapLength + "\"/>\n"
                + "     </rdf:li>\n"
                + "    </rdf:Seq>\n   </Container:Directory>\n  </rdf:Description>\n";
    }

    /**
     * {@return the Ultra HDR {@code rdf:Description} element of the gain map image}
     *
     * @param metadata the gain map metadata
     */
    private static String secondaryDescription(GainMapMetadata metadata) {
        var baseIsHdr = metadata.isBaseBrighter();
        var attributes = new StringBuilder();
        var elements = new StringBuilder();
        attributes.append("hdrgm:Version=\"1.0\"");
        property(metadata, "GainMapMin", GainMapChannel::minimum, attributes, elements);
        property(metadata, "GainMapMax", GainMapChannel::maximum, attributes, elements);
        property(metadata, "Gamma", GainMapChannel::gamma, attributes, elements);
        property(metadata, "OffsetSDR", baseIsHdr ? GainMapChannel::alternateOffset : GainMapChannel::baseOffset,
                attributes, elements);
        property(metadata, "OffsetHDR", baseIsHdr ? GainMapChannel::baseOffset : GainMapChannel::alternateOffset,
                attributes, elements);
        attributes.append("\n    hdrgm:HDRCapacityMin=\"")
                .append(format(Math.min(metadata.baseHeadroom(), metadata.alternateHeadroom())))
                .append("\"\n    hdrgm:HDRCapacityMax=\"")
                .append(format(Math.max(metadata.baseHeadroom(), metadata.alternateHeadroom())))
                .append("\"\n    hdrgm:BaseRenditionIsHDR=\"").append(baseIsHdr ? "True" : "False").append('"');
        return elements.isEmpty()
                ? "  <rdf:Description rdf:about=\"\" xmlns:hdrgm=\"" + GainMapMetadata.XMP_NAMESPACE + "\"\n    "
                + attributes + "/>\n"
                : "  <rdf:Description rdf:about=\"\" xmlns:hdrgm=\"" + GainMapMetadata.XMP_NAMESPACE + "\"\n    "
                + attributes + ">\n" + elements + "  </rdf:Description>\n";
    }

    /**
     * Writes a per-channel property, as an attribute for shared values and an {@code rdf:Seq} element for three.
     *
     * @param metadata the gain map metadata
     * @param name the property name
     * @param accessor the value of one channel
     * @param attributes the attributes written so far
     * @param elements the elements written so far
     */
    private static void property(GainMapMetadata metadata, String name, ToDoubleFunction<GainMapChannel> accessor,
                                 StringBuilder attributes, StringBuilder elements) {
        if (!metadata.isMultichannel()) {
            attributes.append("\n    hdrgm:").append(name).append("=\"")
                    .append(format(accessor.applyAsDouble(metadata.channel(0)))).append('"');
            return;
        }
        elements.append("   <hdrgm:").append(name).append(">\n    <rdf:Seq>\n");
        for (var channel = 0; channel < 3; channel++) {
            elements.append("     <rdf:li>").append(format(accessor.applyAsDouble(metadata.channel(channel))))
                    .append("</rdf:li>\n");
        }
        elements.append("    </rdf:Seq>\n   </hdrgm:").append(name).append(">\n");
    }

    /**
     * {@return a real number as an XMP {@code Real}: the shortest decimal digits that read back to the same double,
     * without an exponent}
     */
    private static String format(double value) {
        var text = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
        return text.indexOf('.') < 0 ? text + ".0" : text;
    }
}
