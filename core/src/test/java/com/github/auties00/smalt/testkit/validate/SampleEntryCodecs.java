package com.github.auties00.smalt.testkit.validate;

import com.github.auties00.smalt.testkit.validate.IsoBmff.Box;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.github.auties00.smalt.testkit.validate.IsoBmff.u16;
import static com.github.auties00.smalt.testkit.validate.IsoBmff.u32;
import static com.github.auties00.smalt.testkit.validate.IsoBmff.u8;

/**
 * Derives the RFC 6381 {@code codecs} parameter of an ISOBMFF sample entry from its configuration box, for checking
 * the {@code CODECS} of an HLS playlist and the {@code codecs} of a DASH manifest against the segments: ISO/IEC
 * 14496-15 Annex E for AVC and HEVC, the VP codec ISO media file format binding, the AV1 codec ISO media file format
 * binding, and RFC 6381 section 3.3 for MPEG-4 audio.
 */
public final class SampleEntryCodecs {
    private SampleEntryCodecs() {
        throw new AssertionError();
    }

    /**
     * Returns the codecs parameter of a sample entry.
     *
     * @param data the file
     * @param entry the sample entry
     * @param handler the handler type of its track
     * @return the parameter, or an empty string when the entry's codec has none this class derives
     */
    public static String of(byte[] data, Box entry, String handler) {
        var type = entry.type();
        if (type.equals("encv") || type.equals("enca")) {
            // a protected entry has the codec of its original format
            var frma = entry.find("sinf", "frma");
            if (frma.isPresent() && frma.get().payloadSize() >= 4) {
                var at = (int) frma.get().payload();
                type = new String(new byte[]{data[at], data[at + 1], data[at + 2], data[at + 3]},
                        java.nio.charset.StandardCharsets.ISO_8859_1);
            }
        }
        var format = type;
        return switch (format) {
            case "avc1", "avc3" -> entry.child("avcC").filter(box -> box.payloadSize() >= 4)
                    .map(box -> String.format(Locale.ROOT, "%s.%02X%02X%02X", format, u8(data, box.payload() + 1),
                            u8(data, box.payload() + 2), u8(data, box.payload() + 3))).orElse(format);
            case "hvc1", "hev1" -> entry.child("hvcC").filter(box -> box.payloadSize() >= 13)
                    .map(box -> hevc(data, format, box.payload())).orElse(format);
            case "vp09", "vp08" -> entry.child("vpcC").filter(box -> box.payloadSize() >= 12)
                    .map(box -> vp(data, format, box.payload() + 4)).orElse(format);
            case "av01" -> entry.child("av1C").filter(box -> box.payloadSize() >= 4)
                    .map(box -> av1(data, box.payload())).orElse(format);
            case "mp4a" -> entry.child("esds").or(() -> entry.find("wave", "esds")).map(box -> mpeg4Audio(data, box))
                    .orElse(format);
            case "Opus", "fLaC", "ac-3", "ec-3", "ipcm", "fpcm", "alac", "wvtt", "mp4v" -> format;
            default -> "";
        };
    }

    /**
     * Returns whether two codecs parameters name the same codec configuration: equal ignoring case, or, for VP9 and
     * AV1, equal in the fields both carry when one is the short form.
     *
     * @param declared the parameter a manifest declares
     * @param derived the parameter derived from the segments
     * @return whether they agree
     */
    public static boolean agree(String declared, String derived) {
        if (declared.equalsIgnoreCase(derived)) {
            return true;
        }
        var a = declared.split("\\.");
        var b = derived.split("\\.");
        if (!a[0].equals(b[0]) || !List.of("vp09", "vp08", "av01").contains(a[0])) {
            return false;
        }
        var shortest = Math.min(a.length, b.length);
        if (shortest < 4) {
            return false;
        }
        for (var index = 0; index < shortest; index++) {
            if (!a[index].equalsIgnoreCase(b[index])) {
                return false;
            }
        }
        return true;
    }

    private static String hevc(byte[] data, String type, long p) {
        var first = u8(data, p + 1);
        var space = first >> 6;
        var tier = (first >> 5) & 1;
        var profile = first & 0x1F;
        var compatibility = Integer.reverse((int) u32(data, p + 2));
        var text = new StringBuilder(type).append('.');
        if (space > 0) {
            text.append((char) ('A' + space - 1));
        }
        text.append(profile).append('.').append(Integer.toHexString(compatibility).toUpperCase(Locale.ROOT))
                .append('.').append(tier == 1 ? 'H' : 'L').append(u8(data, p + 12));
        var constraints = new ArrayList<String>();
        for (var index = 0; index < 6; index++) {
            constraints.add(String.format(Locale.ROOT, "%02X", u8(data, p + 6 + index)));
        }
        while (!constraints.isEmpty() && constraints.getLast().equals("00")) {
            constraints.removeLast();
        }
        for (var constraint : constraints) {
            text.append('.').append(constraint);
        }
        return text.toString();
    }

    private static String vp(byte[] data, String type, long p) {
        var profile = u8(data, p);
        var level = u8(data, p + 1);
        var packed = u8(data, p + 2);
        var depth = packed >> 4;
        var subsampling = (packed >> 1) & 7;
        var range = packed & 1;
        return String.format(Locale.ROOT, "%s.%02d.%02d.%02d.%02d.%02d.%02d.%02d.%02d", type, profile, level, depth,
                subsampling, u8(data, p + 3), u8(data, p + 4), u8(data, p + 5), range);
    }

    private static String av1(byte[] data, long p) {
        var second = u8(data, p + 1);
        var third = u8(data, p + 2);
        var profile = second >> 5;
        var level = second & 0x1F;
        var tier = (third >> 7) != 0 ? 'H' : 'M';
        var high = (third >> 6) & 1;
        var twelve = (third >> 5) & 1;
        var depth = profile == 2 && high == 1 ? (twelve == 1 ? 12 : 10) : high == 1 ? 10 : 8;
        var monochrome = (third >> 4) & 1;
        var subsamplingX = (third >> 3) & 1;
        var subsamplingY = (third >> 2) & 1;
        var position = third & 3;
        return String.format(Locale.ROOT, "av01.%d.%02d%c.%02d.%d.%d%d%d", profile, level, tier, depth, monochrome,
                subsamplingX, subsamplingY, subsamplingX == 1 && subsamplingY == 1 ? position : 0);
    }

    private static String mpeg4Audio(byte[] data, Box esds) {
        var offset = esds.payload() + 4;
        var end = esds.end();
        var objectType = -1;
        while (offset < end) {
            var tag = u8(data, offset++);
            var length = 0;
            for (var i = 0; i < 4 && offset < end; i++) {
                var b = u8(data, offset++);
                length = length << 7 | b & 0x7F;
                if ((b & 0x80) == 0) {
                    break;
                }
            }
            if (tag == 3) {
                var flags = u8(data, offset + 2);
                offset += 3 + ((flags & 0x80) != 0 ? 2 : 0);
                if ((flags & 0x40) != 0) {
                    offset += 1 + u8(data, offset);
                }
                offset += (flags & 0x20) != 0 ? 2 : 0;
            } else if (tag == 4) {
                objectType = u8(data, offset);
                offset += 13;
            } else if (tag == 5) {
                if (objectType != 0x40 || length < 1) {
                    break;
                }
                var aot = u8(data, offset) >> 3;
                if (aot == 31 && length >= 2) {
                    aot = 32 + ((u16(data, offset) >> 5) & 0x3F);
                }
                return "mp4a.40." + aot;
            } else {
                offset += length;
            }
        }
        return objectType < 0 ? "mp4a" : String.format(Locale.ROOT, "mp4a.%02X", objectType);
    }
}
