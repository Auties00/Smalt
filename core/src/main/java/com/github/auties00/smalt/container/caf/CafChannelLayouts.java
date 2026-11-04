package com.github.auties00.smalt.container.caf;

import com.github.auties00.smalt.audio.Ambisonics;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.container.internal.WaveFormat;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.io.ByteWriter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts between {@link ChannelLayout}s and the {@code AudioChannelLayout} of a CAF {@code chan} chunk: a layout
 * tag, a channel bitmap, or a description of every channel by its label.
 *
 * <p>The bitmap bits and the labels 1 to 18 name the speakers of the WAVE channel mask in the same order, so label
 * {@code n} is the {@code (n - 1)}-th {@link Speaker}; the surround labels ({@code Ls}, {@code Rs}) are the back
 * speakers unless the rear surround labels ({@code Rls}, {@code Rrs}) or the center surround label also appear,
 * which makes them the side speakers, as in the layout tags. Descriptions are written with the rear surround labels
 * for the back speakers and the surround direct labels for the side ones, which read back as the same speakers.
 */
final class CafChannelLayouts {
    /**
     * The tag of a layout described channel by channel.
     */
    static final int USE_DESCRIPTIONS = 0;

    /**
     * The tag of a layout described by its bitmap.
     */
    static final int USE_BITMAP = 1 << 16;

    /**
     * The tag of channels in order, without positions; the channel count is added.
     */
    private static final int DISCRETE_IN_ORDER = 147 << 16;

    /**
     * The tag of Higher Order Ambisonics in ACN order with SN3D normalization; the channel count is added.
     */
    private static final int HOA_ACN_SN3D = 190 << 16;

    /**
     * The tag of Higher Order Ambisonics in ACN order with N3D normalization; the channel count is added.
     */
    private static final int HOA_ACN_N3D = 191 << 16;

    /**
     * The tag of first-order Furse-Malham B-format, {@code W X Y Z}.
     */
    private static final int AMBISONIC_B_FORMAT = 107 << 16 | 4;

    /**
     * The label of a channel of unknown role.
     */
    private static final int LABEL_UNKNOWN = 0xFFFFFFFF;

    /**
     * The speakers of the layout tags this class reads, by tag.
     */
    private static final Map<Integer, List<Speaker>> TAGS = tags();

    /**
     * Prevents instantiation.
     */
    private CafChannelLayouts() {
        throw new AssertionError();
    }

    /**
     * Builds the table of layout tags.
     *
     * @return the speakers of each tag, in channel order
     */
    private static Map<Integer, List<Speaker>> tags() {
        var tags = new LinkedHashMap<Integer, List<Speaker>>();
        tag(tags, 100, "C");
        tag(tags, 101, "L R");
        tag(tags, 102, "L R");
        tag(tags, 103, "L R");
        tag(tags, 106, "L R");
        tag(tags, 108, "L R Ls Rs");
        tag(tags, 109, "L R Rls Rrs C");
        tag(tags, 110, "L R Rls Rrs C Cs");
        tag(tags, 111, "L R Rls Rrs C Cs Lw Rw");
        tag(tags, 112, "L R Rls Rrs Vhl Vhr Rlt Rrt");
        tag(tags, 113, "L R C");
        tag(tags, 114, "C L R");
        tag(tags, 115, "L R C Cs");
        tag(tags, 116, "C L R Cs");
        tag(tags, 117, "L R C Ls Rs");
        tag(tags, 118, "L R Ls Rs C");
        tag(tags, 119, "L C R Ls Rs");
        tag(tags, 120, "C L R Ls Rs");
        tag(tags, 121, "L R C LFE Ls Rs");
        tag(tags, 122, "L R Ls Rs C LFE");
        tag(tags, 123, "L C R Ls Rs LFE");
        tag(tags, 124, "C L R Ls Rs LFE");
        tag(tags, 125, "L R C LFE Lsd Rsd Cs");
        tag(tags, 126, "L R C LFE Ls Rs Lc Rc");
        tag(tags, 127, "C Lc Rc L R Ls Rs LFE");
        tag(tags, 128, "L R C LFE Lsd Rsd Rls Rrs");
        tag(tags, 129, "L R Ls Rs C LFE Lc Rc");
        tag(tags, 131, "L R Cs");
        tag(tags, 132, "L R Lsd Rsd");
        tag(tags, 133, "L R LFE");
        tag(tags, 134, "L R LFE Cs");
        tag(tags, 135, "L R LFE Ls Rs");
        tag(tags, 136, "L R C LFE");
        tag(tags, 137, "L R C LFE Cs");
        tag(tags, 138, "L R Ls Rs LFE");
        tag(tags, 139, "L R Lsd Rsd C Cs");
        tag(tags, 140, "L R Lsd Rsd C Rls Rrs");
        tag(tags, 141, "C L R Lsd Rsd Cs");
        tag(tags, 142, "C L R Lsd Rsd Cs LFE");
        tag(tags, 143, "C L R Lsd Rsd Rls Rrs");
        tag(tags, 144, "C L R Lsd Rsd Rls Rrs Cs");
        tag(tags, 148, "L R Ls Rs C Lc Rc");
        tag(tags, 149, "C LFE");
        tag(tags, 150, "L C R");
        tag(tags, 151, "L C R Cs");
        tag(tags, 152, "L C R LFE");
        tag(tags, 153, "L R Cs LFE");
        tag(tags, 154, "L C R Cs LFE");
        tag(tags, 183, "C L R Lsd Rsd Rls Rrs LFE");
        tag(tags, 184, "C L R Ls Rs LFE Vhl Vhr");
        return Collections.unmodifiableMap(tags);
    }

    /**
     * Adds a layout tag to the table.
     *
     * @param tags the table
     * @param number the upper 16 bits of the tag
     * @param speakers the speakers in channel order, by Core Audio abbreviation ({@code Lsd} and {@code Rsd} stand
     * for the surround speakers at the sides)
     */
    private static void tag(Map<Integer, List<Speaker>> tags, int number, String speakers) {
        var list = new ArrayList<Speaker>();
        for (var name : speakers.split(" ")) {
            list.add(switch (name) {
                case "L" -> Speaker.FRONT_LEFT;
                case "R" -> Speaker.FRONT_RIGHT;
                case "C" -> Speaker.FRONT_CENTER;
                case "LFE" -> Speaker.LOW_FREQUENCY;
                case "Ls", "Rls" -> Speaker.BACK_LEFT;
                case "Rs", "Rrs" -> Speaker.BACK_RIGHT;
                case "Lsd" -> Speaker.SIDE_LEFT;
                case "Rsd" -> Speaker.SIDE_RIGHT;
                case "Lc" -> Speaker.FRONT_LEFT_OF_CENTER;
                case "Rc" -> Speaker.FRONT_RIGHT_OF_CENTER;
                case "Cs" -> Speaker.BACK_CENTER;
                case "Lw" -> Speaker.WIDE_LEFT;
                case "Rw" -> Speaker.WIDE_RIGHT;
                case "Vhl" -> Speaker.TOP_FRONT_LEFT;
                case "Vhr" -> Speaker.TOP_FRONT_RIGHT;
                case "Rlt" -> Speaker.TOP_BACK_LEFT;
                case "Rrt" -> Speaker.TOP_BACK_RIGHT;
                default -> throw new AssertionError(name);
            });
        }
        tags.put(number << 16 | list.size(), List.copyOf(list));
    }

    /**
     * Reads the body of a {@code chan} chunk.
     *
     * @param chan the chunk body
     * @param channels the channel count of the stream
     * @return the layout, or {@code null} when the chunk is malformed, describes another channel count or uses a tag
     * this class does not read
     */
    static ChannelLayout read(byte[] chan, int channels) {
        if (chan.length < 12) {
            return null;
        }
        var tag = ByteArrays.i32be(chan, 0);
        var bitmap = ByteArrays.i32be(chan, 4);
        var descriptions = ByteArrays.i32be(chan, 8);
        if (tag == USE_DESCRIPTIONS) {
            if (descriptions != channels || chan.length - 12 < 20L * descriptions) {
                return null;
            }
            var labels = new int[descriptions];
            for (var index = 0; index < descriptions; index++) {
                labels[index] = ByteArrays.i32be(chan, 12 + 20 * index);
            }
            return fromLabels(labels);
        }
        if (tag == USE_BITMAP) {
            if (Integer.bitCount(bitmap & 0x3FFFF) != channels || (bitmap & ~0x3FFFF) != 0) {
                return null;
            }
            var speakers = new ArrayList<Speaker>(channels);
            for (var bit = 0; bit < 18; bit++) {
                if ((bitmap & 1 << bit) != 0) {
                    speakers.add(Speaker.values()[bit]);
                }
            }
            return new ChannelLayout(speakers);
        }
        if ((tag & 0xFFFF) != channels) {
            return null;
        }
        var known = TAGS.get(tag);
        if (known != null) {
            return new ChannelLayout(known);
        }
        if (tag == AMBISONIC_B_FORMAT) {
            return ChannelLayout.ofAmbisonics(Ambisonics.ofFurseMalham(4));
        }
        var family = tag & 0xFFFF0000;
        if ((family == HOA_ACN_SN3D || family == HOA_ACN_N3D) && isSquare(channels)) {
            var order = (int) Math.round(Math.sqrt(channels)) - 1;
            if (order <= Ambisonics.MAX_ORDER) {
                return ChannelLayout.ofAmbisonics(Ambisonics.of(order, family == HOA_ACN_SN3D
                        ? Ambisonics.Normalization.SN3D : Ambisonics.Normalization.N3D));
            }
        }
        if (family == DISCRETE_IN_ORDER || family == 0xFFFF0000) {
            return new ChannelLayout(Collections.nCopies(channels, Speaker.UNKNOWN));
        }
        return null;
    }

    /**
     * Returns whether a count is the square of an integer.
     *
     * @param count the count, positive
     * @return {@code true} for 1, 4, 9, 16 and so on
     */
    private static boolean isSquare(int count) {
        var root = (int) Math.round(Math.sqrt(count));
        return root * root == count;
    }

    /**
     * Returns the layout of channels described by their labels.
     *
     * @param labels the label of every channel, in order
     * @return the layout; a label that names no speaker gives {@link Speaker#UNKNOWN}, as does a speaker a previous
     * channel already took
     */
    private static ChannelLayout fromLabels(int[] labels) {
        var sides = false;
        for (var label : labels) {
            sides |= label == 33 || label == 34 || label == 9;
        }
        var speakers = new ArrayList<Speaker>(labels.length);
        for (var label : labels) {
            var speaker = switch (label) {
                case 5 -> sides ? Speaker.SIDE_LEFT : Speaker.BACK_LEFT;
                case 6 -> sides ? Speaker.SIDE_RIGHT : Speaker.BACK_RIGHT;
                case 33 -> Speaker.BACK_LEFT;
                case 34 -> Speaker.BACK_RIGHT;
                case 35 -> Speaker.WIDE_LEFT;
                case 36 -> Speaker.WIDE_RIGHT;
                case 37 -> Speaker.LOW_FREQUENCY_2;
                case 42 -> Speaker.FRONT_CENTER;
                default -> label >= 1 && label <= 18 ? Speaker.values()[label - 1] : Speaker.UNKNOWN;
            };
            speakers.add(speaker != Speaker.UNKNOWN && speakers.contains(speaker) ? Speaker.UNKNOWN : speaker);
        }
        return new ChannelLayout(speakers);
    }

    /**
     * Writes the body of a {@code chan} chunk for a layout.
     *
     * <p>A layout that a tag describes gets the tag, preferring the first one of the table; one whose speakers are in
     * WAVE mask order gets the bitmap; any other gets one description per channel, with the unknown label for the
     * channels of no position.
     *
     * @param layout the channel layout to describe
     * @return the chunk body
     */
    static byte[] write(ChannelLayout layout) {
        var channels = layout.channels();
        var tag = -1;
        var ambisonics = layout.ambisonics().orElse(null);
        if (ambisonics != null && ambisonics.channels() == channels) {
            if (layout.equals(ChannelLayout.ofAmbisonics(Ambisonics.ofFurseMalham(4)))) {
                tag = AMBISONIC_B_FORMAT;
            } else if (isSquare(channels) && channels <= (Ambisonics.MAX_ORDER + 1) * (Ambisonics.MAX_ORDER + 1)) {
                var order = (int) Math.round(Math.sqrt(channels)) - 1;
                if (layout.equals(ChannelLayout.ofAmbisonics(Ambisonics.of(order, Ambisonics.Normalization.SN3D)))) {
                    tag = HOA_ACN_SN3D | channels;
                } else if (layout.equals(ChannelLayout.ofAmbisonics(Ambisonics.of(order,
                        Ambisonics.Normalization.N3D)))) {
                    tag = HOA_ACN_N3D | channels;
                }
            }
        }
        if (tag < 0 && ambisonics == null) {
            for (var entry : TAGS.entrySet()) {
                if (entry.getValue().equals(layout.speakers())) {
                    tag = entry.getKey();
                    break;
                }
            }
        }
        var bitmap = ambisonics == null ? WaveFormat.channelMask(layout) : 0;
        var writer = new ByteWriter(12 + 20 * channels);
        if (tag >= 0) {
            writer.u32be(tag);
            writer.u32be(0);
            writer.u32be(0);
        } else if (bitmap != 0 && bitmap < 1 << 18) {
            writer.u32be(USE_BITMAP);
            writer.u32be(bitmap);
            writer.u32be(0);
        } else if (layout.speakers().stream().allMatch(speaker -> speaker == Speaker.UNKNOWN)) {
            writer.u32be(DISCRETE_IN_ORDER | channels);
            writer.u32be(0);
            writer.u32be(0);
        } else {
            writer.u32be(USE_DESCRIPTIONS);
            writer.u32be(0);
            writer.u32be(channels);
            for (var speaker : layout.speakers()) {
                writer.u32be(label(speaker));
                writer.u32be(0);
                writer.zeros(12);
            }
        }
        return writer.toByteArray();
    }

    /**
     * Returns the label of a speaker.
     *
     * @param speaker the speaker position to label
     * @return the label, the unknown label for a speaker no label names
     */
    private static int label(Speaker speaker) {
        return switch (speaker) {
            case BACK_LEFT -> 33;
            case BACK_RIGHT -> 34;
            case WIDE_LEFT -> 35;
            case WIDE_RIGHT -> 36;
            case LOW_FREQUENCY_2 -> 37;
            case UNKNOWN -> LABEL_UNKNOWN;
            default -> speaker.ordinal() <= Speaker.TOP_BACK_RIGHT.ordinal() ? speaker.ordinal() + 1 : LABEL_UNKNOWN;
        };
    }
}
