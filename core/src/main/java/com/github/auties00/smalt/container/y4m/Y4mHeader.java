package com.github.auties00.smalt.container.y4m;

import com.github.auties00.smalt.image.ChromaSiting;
import com.github.auties00.smalt.video.RawPixelFormat;
import com.github.auties00.smalt.video.RawVideoFormat;

/**
 * Maps the {@code C} colorspace tags of YUV4MPEG2 headers to raw video formats and back.
 */
final class Y4mHeader {
    private Y4mHeader() {
        throw new AssertionError();
    }

    /**
     * Reads a colorspace tag, without its {@code C}.
     *
     * @param tag the tag, for example {@code 420jpeg}, {@code 422p10} or {@code mono}
     * @return the format, or {@code null} for an unknown tag
     */
    static RawVideoFormat format(String tag) {
        if (tag.startsWith("mono")) {
            var depth = depth(tag.substring(4));
            return depth < 0 ? null : RawVideoFormat.of(RawPixelFormat.GRAY, depth);
        }
        if (tag.equals("444alpha")) {
            return RawVideoFormat.of(RawPixelFormat.YUVA444P);
        }
        RawPixelFormat pixelFormat;
        String rest;
        if (tag.startsWith("420")) {
            pixelFormat = RawPixelFormat.YUV420P;
            rest = tag.substring(3);
            if (rest.equals("jpeg") || rest.equals("mpeg2") || rest.equals("paldv")) {
                rest = "";
            }
        } else if (tag.startsWith("422")) {
            pixelFormat = RawPixelFormat.YUV422P;
            rest = tag.substring(3);
        } else if (tag.startsWith("444")) {
            pixelFormat = RawPixelFormat.YUV444P;
            rest = tag.substring(3);
        } else if (tag.startsWith("411")) {
            pixelFormat = RawPixelFormat.YUV411P;
            rest = tag.substring(3);
        } else {
            return null;
        }
        if (rest.isEmpty()) {
            return RawVideoFormat.of(pixelFormat);
        }
        if (!rest.startsWith("p") || pixelFormat == RawPixelFormat.YUV411P) {
            return null;
        }
        var depth = depth(rest.substring(1));
        return depth <= 8 ? null : RawVideoFormat.of(pixelFormat, depth);
    }

    /**
     * Reads the bit depth suffix of a tag.
     *
     * @param suffix the digits, or empty for 8 bits
     * @return the depth from 8 to 16, or {@code -1}
     */
    private static int depth(String suffix) {
        if (suffix.isEmpty()) {
            return 8;
        }
        if (suffix.length() > 2 || !suffix.chars().allMatch(Character::isDigit)) {
            return -1;
        }
        var depth = Integer.parseInt(suffix);
        return depth >= 8 && depth <= 16 ? depth : -1;
    }

    /**
     * Returns the chroma siting a 4:2:0 colorspace tag names.
     *
     * @param tag the tag, without its {@code C}
     * @return the siting: left for {@code 420mpeg2}, top left for {@code 420paldv}, center otherwise
     */
    static ChromaSiting siting(String tag) {
        return switch (tag) {
            case "420mpeg2" -> ChromaSiting.LEFT;
            case "420paldv" -> ChromaSiting.TOP_LEFT;
            default -> ChromaSiting.CENTER;
        };
    }

    /**
     * Returns the colorspace tag of a format.
     *
     * @param format the raw video format of the track
     * @param siting the chroma siting of a 4:2:0 format
     * @return the tag without its {@code C}, or {@code null} when YUV4MPEG2 cannot carry the format: anything but
     * tightly packed, top-down, little-endian planar YUV 4:2:0, 4:2:2, 4:4:4, 4:1:1, 4:4:4 with alpha at 8 bits, and
     * gray
     */
    static String tag(RawVideoFormat format, ChromaSiting siting) {
        if (format.rowAlignment() != 1 || format.bottomUp() || format.bigEndian()) {
            return null;
        }
        var depth = format.bitDepth();
        var suffix = depth == 8 ? "" : "p" + depth;
        return switch (format.pixelFormat()) {
            case YUV420P -> depth != 8 ? "420" + suffix : switch (siting) {
                case LEFT -> "420mpeg2";
                case TOP_LEFT -> "420paldv";
                default -> "420jpeg";
            };
            case YUV422P -> "422" + suffix;
            case YUV444P -> "444" + suffix;
            case YUV411P -> depth == 8 ? "411" : null;
            case YUVA444P -> depth == 8 ? "444alpha" : null;
            case GRAY -> "mono" + (depth == 8 ? "" : Integer.toString(depth));
            default -> null;
        };
    }
}
