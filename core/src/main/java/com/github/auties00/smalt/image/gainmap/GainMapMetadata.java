package com.github.auties00.smalt.image.gainmap;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.metadata.XmpPacket;
import com.github.auties00.smalt.metadata.XmpValue;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Holds the metadata of a gain map (ISO 21496-1): the headrooms of the two renditions and how the gain map
 * samples of each channel map to gains.
 *
 * <p>A gain map turns the image it accompanies, the base rendition, into an alternate rendition. The headroom
 * of a rendition is the base-2 logarithm of its peak luminance relative to the standard-dynamic-range white:
 * 0 for a standard-dynamic-range rendition, 2 for one that reaches four times that white. The gain map
 * stores, in each channel, the log2 ratio of the brighter rendition to the dimmer one. A display of headroom
 * {@code h} shows a rendition between the two, weighted by {@link #weight(double)}: the linear-light value
 * {@code b} of a base pixel becomes
 * {@snippet :
 * double weight = metadata.weight(h);            // 0 at the base headroom, 1 at the alternate one
 * double sign = metadata.isBaseBrighter() ? -1 : 1;
 * GainMapChannel channel = metadata.channel(c);
 * double value = (b + channel.baseOffset())
 *         * Math.pow(2, sign * weight * channel.log2Gain(sample)) - channel.alternateOffset();
 * }
 * where {@code sample} is the normalized gain map sample of the pixel.
 *
 * <p>The metadata has two serialized forms: the binary {@code GainMapMetadata} structure of ISO 21496-1
 * ({@link #parse(byte[])}, {@link #toByteArray()}), which JPEG {@code APP2} segments, the HEIF and AVIF
 * {@code tmap} derived image item and the JPEG XL {@code jhgm} box carry, and the {@code hdrgm} XMP properties
 * of the Adobe gain map specification ({@link #fromXmp(XmpPacket)}, {@link #toXmp()}), which Ultra HDR and
 * Adobe JPEG files carry.
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @param baseHeadroom the headroom of the base rendition, as a base-2 logarithm, finite and not negative
 * @param alternateHeadroom the headroom of the alternate rendition, as a base-2 logarithm, finite and not
 * negative
 * @param channels the metadata of the red, green and blue channels, or a single entry that applies to all
 * three
 * @param useBaseColorSpace whether the gain map applies in the color space of the base rendition rather than
 * that of the alternate one
 */
public record GainMapMetadata(double baseHeadroom, double alternateHeadroom, List<GainMapChannel> channels,
                              boolean useBaseColorSpace) {
    /**
     * The XMP namespace of the Adobe gain map properties, prefix {@code hdrgm}.
     */
    public static final String XMP_NAMESPACE = "http://ns.adobe.com/hdr-gain-map/1.0/";

    /**
     * The version of the Adobe gain map specification written in {@code hdrgm:Version}.
     */
    private static final String XMP_VERSION = "1.0";

    /**
     * The flag of three channels.
     */
    private static final int MULTICHANNEL = 0x80;

    /**
     * The flag of the base color space.
     */
    private static final int BASE_COLOR_SPACE = 0x40;

    /**
     * The flag of a common denominator.
     */
    private static final int COMMON_DENOMINATOR = 0x08;

    /**
     * The flag of the backward direction.
     */
    private static final int BACKWARD_DIRECTION = 0x04;

    /**
     * Validates the metadata and copies the channel list.
     *
     * @throws IllegalArgumentException if a headroom is negative or not finite, or the list does not hold one
     * or three channels
     * @throws NullPointerException if a channel is {@code null}
     */
    public GainMapMetadata {
        if (!(baseHeadroom >= 0) || !(alternateHeadroom >= 0) || Double.isInfinite(baseHeadroom)
                || Double.isInfinite(alternateHeadroom)) {
            throw new IllegalArgumentException("invalid gain map headrooms " + baseHeadroom + " and "
                    + alternateHeadroom);
        }
        channels = List.copyOf(channels);
        if (channels.size() != 1 && channels.size() != 3) {
            throw new IllegalArgumentException("a gain map has 1 or 3 channels, not " + channels.size());
        }
    }

    /**
     * Returns the metadata of a gain map whose channels share one description, applied in the base color space.
     *
     * @param baseHeadroom the headroom of the base rendition, as a base-2 logarithm
     * @param alternateHeadroom the headroom of the alternate rendition, as a base-2 logarithm
     * @param channel the description of every channel
     * @return the metadata
     * @throws IllegalArgumentException if a headroom is negative or not finite
     */
    public static GainMapMetadata of(double baseHeadroom, double alternateHeadroom, GainMapChannel channel) {
        return new GainMapMetadata(baseHeadroom, alternateHeadroom, List.of(channel), true);
    }

    /**
     * Returns the metadata of a channel.
     *
     * @param component the channel, 0 for red, 1 for green, 2 for blue
     * @return the channel's metadata, the single entry of {@link #channels()} when it holds only one
     * @throws IndexOutOfBoundsException if {@code component} is not from 0 to 2
     */
    public GainMapChannel channel(int component) {
        Objects.checkIndex(component, 3);
        return channels.get(channels.size() == 1 ? 0 : component);
    }

    /**
     * {@return whether the channels have their own descriptions}
     */
    public boolean isMultichannel() {
        return channels.size() == 3;
    }

    /**
     * {@return whether the base rendition has the larger headroom, so that the gain map dims it toward the
     * alternate rendition}
     */
    public boolean isBaseBrighter() {
        return alternateHeadroom < baseHeadroom;
    }

    /**
     * Returns how much of the gain map a display of a given headroom applies.
     *
     * <p>The weight is {@code (headroom - baseHeadroom) / (alternateHeadroom - baseHeadroom)} clamped to the
     * range 0 to 1: 0 shows the base rendition, 1 the alternate one. It is 0 when both renditions have the same
     * headroom.
     *
     * @param headroom the headroom of the display, as a base-2 logarithm; a NaN gives 0
     * @return the weight, from 0 to 1
     */
    public double weight(double headroom) {
        if (alternateHeadroom == baseHeadroom || Double.isNaN(headroom)) {
            return 0;
        }
        var weight = (headroom - baseHeadroom) / (alternateHeadroom - baseHeadroom);
        return weight >= 1 ? 1 : weight > 0 ? weight : 0;
    }

    /**
     * Parses the binary {@code GainMapMetadata} structure of ISO 21496-1.
     *
     * <p>The structure starts with its {@code minimum_version} and {@code writer_version} fields; the
     * {@code version} byte that the HEIF {@code tmap} item puts before it is not part of it. The fractions may
     * have their own denominators or share a common one. Bytes after the structure are an error when
     * {@code writer_version} is 0, and are ignored when it is later.
     *
     * @param data the structure
     * @return the metadata
     * @throws MediaException.Malformed if the structure is truncated, a denominator is 0, a gamma is 0, a
     * maximum is less than its minimum, or bytes follow a version 0 structure
     * @throws MediaException.Unsupported if {@code minimum_version} is not 0, or the backward direction flag is
     * set
     */
    public static GainMapMetadata parse(byte[] data) {
        Objects.requireNonNull(data, "data");
        var reader = new Reader(data);
        var minimumVersion = reader.u16();
        if (minimumVersion != 0) {
            throw new MediaException.Unsupported("gain map", "ISO 21496-1 minimum version " + minimumVersion);
        }
        var writerVersion = reader.u16();
        var flags = reader.u8();
        if ((flags & BACKWARD_DIRECTION) != 0) {
            // TODO: apply gain maps with the backward direction flag of ISO 21496-1 once a specification text
            //  or a sample defines its arithmetic
            throw new MediaException.Unsupported("gain map", "the ISO 21496-1 backward direction");
        }
        var count = (flags & MULTICHANNEL) != 0 ? 3 : 1;
        var common = (flags & COMMON_DENOMINATOR) != 0;
        var denominator = common ? reader.u32() : 0;
        var baseHeadroom = reader.fraction(reader.u32(), common ? denominator : reader.u32(), "base headroom");
        var alternateHeadroom = reader.fraction(reader.u32(), common ? denominator : reader.u32(),
                "alternate headroom");
        var channels = new GainMapChannel[count];
        for (var channel = 0; channel < count; channel++) {
            var minimum = reader.fraction(reader.s32(), common ? denominator : reader.u32(), "gain map minimum");
            var maximum = reader.fraction(reader.s32(), common ? denominator : reader.u32(), "gain map maximum");
            var gamma = reader.fraction(reader.u32(), common ? denominator : reader.u32(), "gain map gamma");
            var baseOffset = reader.fraction(reader.s32(), common ? denominator : reader.u32(), "base offset");
            var alternateOffset = reader.fraction(reader.s32(), common ? denominator : reader.u32(),
                    "alternate offset");
            if (gamma == 0) {
                throw new MediaException.Malformed("gain map: a gamma is 0");
            }
            if (maximum < minimum) {
                throw new MediaException.Malformed("gain map: a maximum is less than its minimum");
            }
            channels[channel] = new GainMapChannel(minimum, maximum, gamma, baseOffset, alternateOffset);
        }
        if (writerVersion == 0 && reader.remaining() != 0) {
            throw new MediaException.Malformed("gain map: " + reader.remaining()
                    + " bytes follow the version 0 metadata");
        }
        return new GainMapMetadata(baseHeadroom, alternateHeadroom, List.of(channels), (flags & BASE_COLOR_SPACE) != 0);
    }

    /**
     * Serializes the metadata as the binary {@code GainMapMetadata} structure of ISO 21496-1, version 0.
     *
     * <p>Each value is written as the closest fraction of 32-bit fields, each with its own denominator.
     *
     * @return the structure
     * @throws IllegalArgumentException if a value is too large for a 32-bit fraction
     */
    public byte[] toByteArray() {
        var out = new ByteArrayOutputStream(5 + 16 + 40 * channels.size());
        u16(out, 0);
        u16(out, 0);
        out.write((isMultichannel() ? MULTICHANNEL : 0) | (useBaseColorSpace ? BASE_COLOR_SPACE : 0));
        fraction(out, Fractions.unsigned(baseHeadroom));
        fraction(out, Fractions.unsigned(alternateHeadroom));
        for (var channel : channels) {
            fraction(out, Fractions.signed(channel.minimum()));
            fraction(out, Fractions.signed(channel.maximum()));
            fraction(out, Fractions.unsigned(channel.gamma()));
            fraction(out, Fractions.signed(channel.baseOffset()));
            fraction(out, Fractions.signed(channel.alternateOffset()));
        }
        return out.toByteArray();
    }

    /**
     * Reads the {@code hdrgm} properties of the Adobe gain map specification from an XMP packet.
     *
     * <p>{@code hdrgm:Version}, {@code hdrgm:GainMapMax} and {@code hdrgm:HDRCapacityMax} are required;
     * {@code hdrgm:GainMapMin} defaults to 0, {@code hdrgm:Gamma} to 1, {@code hdrgm:OffsetSDR} and
     * {@code hdrgm:OffsetHDR} to 1/64, {@code hdrgm:HDRCapacityMin} to 0 and {@code hdrgm:BaseRenditionIsHDR}
     * to {@code False}. The per-channel properties are a single real or an {@code rdf:Seq} of three. When the
     * base rendition is the HDR one, its headroom is {@code HDRCapacityMax} and its offset {@code OffsetHDR}.
     * The gain map applies in the base color space.
     *
     * @param xmp the packet
     * @return the metadata, or empty when the packet has no {@code hdrgm:Version} or no
     * {@code hdrgm:GainMapMax}, as the XMP of the primary image of an Ultra HDR file
     * @throws MediaException.Malformed if a property is not a number, a per-channel property has another count
     * than 1 or 3, {@code hdrgm:HDRCapacityMax} is missing, or the values are out of range
     */
    public static Optional<GainMapMetadata> fromXmp(XmpPacket xmp) {
        Objects.requireNonNull(xmp, "xmp");
        if (xmp.text(XMP_NAMESPACE, "Version").isEmpty() || xmp.get(XMP_NAMESPACE, "GainMapMax").isEmpty()) {
            return Optional.empty();
        }
        var maximum = reals(xmp, "GainMapMax", Double.NaN);
        var minimum = reals(xmp, "GainMapMin", 0);
        var gamma = reals(xmp, "Gamma", 1);
        var offsetSdr = reals(xmp, "OffsetSDR", GainMapChannel.DEFAULT_OFFSET);
        var offsetHdr = reals(xmp, "OffsetHDR", GainMapChannel.DEFAULT_OFFSET);
        if (xmp.get(XMP_NAMESPACE, "HDRCapacityMax").isEmpty()) {
            throw new MediaException.Malformed("gain map: hdrgm:HDRCapacityMax is missing");
        }
        var capacityMax = reals(xmp, "HDRCapacityMax", Double.NaN)[0];
        var capacityMin = reals(xmp, "HDRCapacityMin", 0)[0];
        var baseIsHdr = xmp.text(XMP_NAMESPACE, "BaseRenditionIsHDR")
                .map(text -> parseBoolean(text.strip()))
                .orElse(false);
        var count = Math.max(Math.max(maximum.length, minimum.length), Math.max(gamma.length,
                Math.max(offsetSdr.length, offsetHdr.length)));
        var channels = new GainMapChannel[count];
        try {
            for (var channel = 0; channel < count; channel++) {
                var sdr = value(offsetSdr, channel);
                var hdr = value(offsetHdr, channel);
                channels[channel] = new GainMapChannel(value(minimum, channel), value(maximum, channel),
                        value(gamma, channel), baseIsHdr ? hdr : sdr, baseIsHdr ? sdr : hdr);
            }
            return Optional.of(new GainMapMetadata(baseIsHdr ? capacityMax : capacityMin,
                    baseIsHdr ? capacityMin : capacityMax, List.of(channels), true));
        } catch (IllegalArgumentException exception) {
            throw new MediaException.Malformed("gain map: invalid hdrgm values: " + exception.getMessage());
        }
    }

    /**
     * Serializes the metadata as the {@code hdrgm} properties of the Adobe gain map specification.
     *
     * <p>The per-channel properties are single reals when the channels share one description and
     * {@code rdf:Seq} arrays of three otherwise. The packet cannot say that the gain map applies in the
     * alternate color space; {@link #toByteArray()} can.
     *
     * @return the packet, holding only {@code hdrgm} properties
     */
    public XmpPacket toXmp() {
        var baseIsHdr = isBaseBrighter();
        var xmp = XmpPacket.empty()
                .with(XMP_NAMESPACE, "Version", XMP_VERSION)
                .with(XMP_NAMESPACE, "GainMapMin", reals(GainMapChannel::minimum))
                .with(XMP_NAMESPACE, "GainMapMax", reals(GainMapChannel::maximum))
                .with(XMP_NAMESPACE, "Gamma", reals(GainMapChannel::gamma))
                .with(XMP_NAMESPACE, "OffsetSDR", reals(baseIsHdr ? GainMapChannel::alternateOffset
                        : GainMapChannel::baseOffset))
                .with(XMP_NAMESPACE, "OffsetHDR", reals(baseIsHdr ? GainMapChannel::baseOffset
                        : GainMapChannel::alternateOffset))
                .with(XMP_NAMESPACE, "HDRCapacityMin", format(Math.min(baseHeadroom, alternateHeadroom)))
                .with(XMP_NAMESPACE, "HDRCapacityMax", format(Math.max(baseHeadroom, alternateHeadroom)));
        return xmp.with(XMP_NAMESPACE, "BaseRenditionIsHDR", baseIsHdr ? "True" : "False");
    }

    /**
     * Formats a channel value of every channel as an XMP value.
     *
     * @param accessor the value of one channel
     * @return a text when the channels share one description, otherwise an ordered array of three
     */
    private XmpValue reals(java.util.function.ToDoubleFunction<GainMapChannel> accessor) {
        if (!isMultichannel()) {
            return XmpValue.text(format(accessor.applyAsDouble(channels.getFirst())));
        }
        return XmpValue.ordered(format(accessor.applyAsDouble(channels.get(0))),
                format(accessor.applyAsDouble(channels.get(1))), format(accessor.applyAsDouble(channels.get(2))));
    }

    /**
     * {@return a real number as an XMP {@code Real}: the shortest decimal digits that read back to the same double,
     * without an exponent}
     */
    private static String format(double value) {
        var text = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
        return text.indexOf('.') < 0 ? text + ".0" : text;
    }

    /**
     * Reads a per-channel {@code hdrgm} property.
     *
     * @param xmp the packet
     * @param name the property name
     * @param fallback the value of an absent property, or NaN when it is required
     * @return one or three values
     * @throws MediaException.Malformed if a value is not a number or the count is not 1 or 3
     */
    private static double[] reals(XmpPacket xmp, String name, double fallback) {
        var texts = xmp.texts(XMP_NAMESPACE, name);
        if (texts.isEmpty()) {
            if (Double.isNaN(fallback)) {
                throw new MediaException.Malformed("gain map: hdrgm:" + name + " is empty");
            }
            return new double[]{fallback};
        }
        if (texts.size() != 1 && texts.size() != 3) {
            throw new MediaException.Malformed("gain map: hdrgm:" + name + " has " + texts.size() + " values");
        }
        var values = new double[texts.size()];
        for (var i = 0; i < values.length; i++) {
            try {
                values[i] = Double.parseDouble(texts.get(i).strip());
            } catch (NumberFormatException exception) {
                throw new MediaException.Malformed("gain map: hdrgm:" + name + " is not a number: " + texts.get(i));
            }
            if (!Double.isFinite(values[i])) {
                throw new MediaException.Malformed("gain map: hdrgm:" + name + " is not finite");
            }
        }
        return values;
    }

    /**
     * {@return the value of a channel: its own, or the single shared one}
     */
    private static double value(double[] values, int channel) {
        return values[values.length == 1 ? 0 : channel];
    }

    /**
     * Parses an XMP boolean.
     *
     * @param text the text, {@code True} or {@code False} in any case
     * @return the boolean
     * @throws MediaException.Malformed if the text is neither
     */
    private static boolean parseBoolean(String text) {
        if (text.equalsIgnoreCase("true")) {
            return true;
        }
        if (text.equalsIgnoreCase("false")) {
            return false;
        }
        throw new MediaException.Malformed("gain map: hdrgm:BaseRenditionIsHDR is not a boolean: " + text);
    }

    /**
     * Writes a 16-bit big-endian value.
     *
     * @param out the output
     * @param value the real number
     */
    private static void u16(ByteArrayOutputStream out, int value) {
        out.write(value >>> 8);
        out.write(value);
    }

    /**
     * Writes a fraction as its 32-bit big-endian numerator and denominator.
     *
     * @param out the output
     * @param fraction the numerator in the high 32 bits and the denominator in the low 32 bits
     */
    private static void fraction(ByteArrayOutputStream out, long fraction) {
        for (var shift = 56; shift >= 0; shift -= 8) {
            out.write((int) (fraction >>> shift));
        }
    }

    /**
     * Reads the big-endian fields of the binary structure.
     */
    private static final class Reader {
        /**
         * The structure.
         */
        private final byte[] data;

        /**
         * The offset of the next field.
         */
        private int position;

        /**
         * Constructs a reader at the start of a structure.
         *
         * @param data the structure
         */
        Reader(byte[] data) {
            this.data = data;
        }

        /**
         * {@return the next unsigned byte}
         */
        int u8() {
            require(1);
            return data[position++] & 0xFF;
        }

        /**
         * {@return the next unsigned 16-bit field}
         */
        int u16() {
            require(2);
            var value = (data[position] & 0xFF) << 8 | data[position + 1] & 0xFF;
            position += 2;
            return value;
        }

        /**
         * {@return the next unsigned 32-bit field}
         */
        long u32() {
            return s32() & 0xFFFFFFFFL;
        }

        /**
         * {@return the next signed 32-bit field}
         */
        long s32() {
            require(4);
            var value = (data[position] & 0xFF) << 24 | (data[position + 1] & 0xFF) << 16
                    | (data[position + 2] & 0xFF) << 8 | data[position + 3] & 0xFF;
            position += 4;
            return value;
        }

        /**
         * Divides a numerator by its denominator.
         *
         * @param numerator the numerator field
         * @param denominator the denominator field
         * @param what the field, named in failure messages
         * @return the value
         * @throws MediaException.Malformed if the denominator is 0
         */
        double fraction(long numerator, long denominator, String what) {
            if (denominator == 0) {
                throw new MediaException.Malformed("gain map: the " + what + " denominator is 0");
            }
            return (double) numerator / denominator;
        }

        /**
         * {@return the number of bytes after the last field read}
         */
        int remaining() {
            return data.length - position;
        }

        /**
         * Checks that a field is complete.
         *
         * @param bytes the field size
         * @throws MediaException.Malformed if the structure ends first
         */
        private void require(int bytes) {
            if (data.length - position < bytes) {
                throw new MediaException.Malformed("gain map: the ISO 21496-1 metadata is truncated");
            }
        }
    }
}
