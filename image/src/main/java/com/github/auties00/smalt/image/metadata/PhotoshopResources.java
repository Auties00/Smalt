package com.github.auties00.smalt.image.metadata;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Reads and writes Photoshop image resource blocks, the payload of a JPEG {@code APP13
 * Photoshop 3.0} segment that carries IPTC-IIM data.
 *
 * <p>Each resource is a signature ({@code 8BIM}), a 16-bit identifier, a Pascal name padded to an
 * even size, a 32-bit size and the data padded to an even size. Resource {@code 0x0404} holds the
 * IIM data and {@code 0x0425} its MD5 digest, which a rewrite recomputes.
 */
final class PhotoshopResources {
    /**
     * The identifier of the IPTC-NAA resource.
     */
    static final int IPTC = 0x0404;

    /**
     * The identifier of the resource holding the MD5 digest of the IPTC-NAA resource.
     */
    static final int IPTC_DIGEST = 0x0425;

    /**
     * The largest number of resources read.
     */
    private static final int MAX_RESOURCES = 1 << 12;

    /**
     * Prevents instantiation.
     */
    private PhotoshopResources() {
        throw new AssertionError();
    }

    /**
     * Parses resources.
     *
     * <p>Parsing stops at the first malformed resource; the resources before it are kept.
     *
     * @param data   the bytes
     * @param offset the index of the first resource
     * @param end    the end of the resources
     * @return the resources in order
     */
    static List<Resource> parse(byte[] data, int offset, int end) {
        var result = new ArrayList<Resource>();
        var position = offset;
        while (end - position >= 12 && result.size() < MAX_RESOURCES) {
            var signature = new String(data, position, 4, StandardCharsets.ISO_8859_1);
            if (!signature.equals("8BIM") && !signature.equals("MeSa") && !signature.equals("PHUT")
                    && !signature.equals("AgHg") && !signature.equals("DCSR")) {
                break;
            }
            var id = ((data[position + 4] & 0xFF) << 8) | (data[position + 5] & 0xFF);
            var nameLength = data[position + 6] & 0xFF;
            var nameField = (1 + nameLength + 1) & ~1;
            var sizeAt = position + 6 + nameField;
            if (sizeAt + 4 > end) {
                break;
            }
            var name = Arrays.copyOfRange(data, position + 7, Math.min(end, position + 7 + nameLength));
            var size = ((data[sizeAt] & 0xFFL) << 24) | ((data[sizeAt + 1] & 0xFFL) << 16) | ((data[sizeAt + 2] & 0xFFL) << 8)
                    | (data[sizeAt + 3] & 0xFFL);
            var start = sizeAt + 4;
            if (size > end - start) {
                break;
            }
            result.add(new Resource(signature, id, name, Arrays.copyOfRange(data, start, start + (int) size)));
            position = start + (int) ((size + 1) & ~1L);
        }
        return result;
    }

    /**
     * Encodes resources.
     *
     * @param resources the resources
     * @return the bytes
     */
    static byte[] encode(List<Resource> resources) {
        var out = new ByteArrayOutputStream();
        for (var resource : resources) {
            out.writeBytes(resource.signature().getBytes(StandardCharsets.ISO_8859_1));
            out.write(resource.id() >>> 8);
            out.write(resource.id());
            var name = resource.name().length > 255 ? Arrays.copyOf(resource.name(), 255) : resource.name();
            out.write(name.length);
            out.writeBytes(name);
            if (((1 + name.length) & 1) != 0) {
                out.write(0);
            }
            var data = resource.data();
            out.write(data.length >>> 24);
            out.write(data.length >>> 16);
            out.write(data.length >>> 8);
            out.write(data.length);
            out.writeBytes(data);
            if ((data.length & 1) != 0) {
                out.write(0);
            }
        }
        return out.toByteArray();
    }

    /**
     * Returns the IIM data of the resources.
     *
     * @param resources the resources
     * @return the data, or {@code null} when there is no IPTC-NAA resource
     */
    static IptcData iptc(List<Resource> resources) {
        for (var resource : resources) {
            if (resource.signature().equals("8BIM") && resource.id() == IPTC) {
                return IptcData.parse(resource.data());
            }
        }
        return null;
    }

    /**
     * Replaces or removes the IPTC-NAA resource and its digest.
     *
     * @param resources the resources
     * @param iptc      the new IIM data, or {@code null} to remove it
     * @return the new resources, the digest recomputed when it was present
     */
    static List<Resource> withIptc(List<Resource> resources, IptcData iptc) {
        var result = new ArrayList<Resource>();
        var bytes = iptc != null ? iptc.toBytes() : null;
        var written = false;
        for (var resource : resources) {
            if (!resource.signature().equals("8BIM")) {
                result.add(resource);
                continue;
            }
            switch (resource.id()) {
                case IPTC -> {
                    if (bytes != null && !written) {
                        result.add(new Resource("8BIM", IPTC, resource.name(), bytes));
                        written = true;
                    }
                }
                case IPTC_DIGEST -> {
                    if (bytes != null) {
                        result.add(new Resource("8BIM", IPTC_DIGEST, resource.name(), md5(bytes)));
                    }
                }
                default -> result.add(resource);
            }
        }
        if (bytes != null && !written) {
            result.add(new Resource("8BIM", IPTC, new byte[0], bytes));
        }
        return result;
    }

    /**
     * Computes the MD5 digest of IIM data.
     *
     * @param data the data
     * @return the digest
     */
    private static byte[] md5(byte[] data) {
        try {
            return MessageDigest.getInstance("MD5").digest(data);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("MD5 is a required algorithm", exception);
        }
    }

    /**
     * Holds one resource.
     *
     * @param signature the signature, usually {@code 8BIM}
     * @param id        the identifier
     * @param name      the name bytes
     * @param data      the data
     */
    record Resource(String signature, int id, byte[] name, byte[] data) {
    }
}
