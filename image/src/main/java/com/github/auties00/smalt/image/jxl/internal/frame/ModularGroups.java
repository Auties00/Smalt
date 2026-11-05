/* Ports jxl-oxide 0.12.6 (crates/jxl-frame: prepare_groups). Copyright Wonwoo Choi and the jxl-oxide contributors;
   MIT License. Modified for Smalt. */
package com.github.auties00.smalt.image.jxl.internal.frame;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.jxl.internal.bitstream.JxlBitReader;
import com.github.auties00.smalt.image.jxl.internal.header.FrameHeader;
import com.github.auties00.smalt.image.jxl.internal.modular.MaTree;
import com.github.auties00.smalt.image.jxl.internal.modular.ModularChannel;
import com.github.auties00.smalt.image.jxl.internal.modular.ModularImage;

import java.util.ArrayList;
import java.util.List;

/**
 * Decodes the parts of a frame's global modular image that are stored group by group (ISO/IEC
 * 18181-1 sections G.2.3 and G.4.2): the channels subsampled by eight or more in both directions
 * belong to the LF groups, the others to the pass group of the pass that completes their
 * resolution.
 *
 * <p>Each group stores a small modular image of its own over the rectangles of those channels;
 * once decoded and inverse transformed, its samples are copied into the global channels. Groups
 * touch disjoint rectangles, so they may be decoded concurrently.
 *
 * <p>The group grid of every channel is the frame's grid scaled by the channel's shifts, and the
 * pass of a channel is the one whose shift range contains the smaller of its two shifts.
 */
public final class ModularGroups {
    /**
     * The global modular image.
     */
    private final ModularImage image;

    /**
     * The frame header.
     */
    private final FrameHeader frame;

    /**
     * The global MA tree, or {@code null}.
     */
    private final MaTree globalTree;

    /**
     * The bit depth of the image samples.
     */
    private final int bitDepth;

    /**
     * The resource bounds.
     */
    private final MediaLimits limits;

    /**
     * The index of the first channel decoded by groups.
     */
    private final int start;

    /**
     * The LF group the shift of each grouped channel belongs to: -1 for LF groups, else the pass.
     */
    private final int[] passOf;

    /**
     * Prepares the grouped decoding of a global modular image.
     *
     * @param image the global modular image, its global prefix already decoded
     * @param start the number of channels already decoded
     * @param frame the frame header
     * @param globalTree the global MA tree, or {@code null}
     * @param bitDepth the bit depth of the samples
     * @param limits the resource bounds
     * @throws MediaException.Malformed if a channel cannot be split into groups
     */
    public ModularGroups(ModularImage image, int start, FrameHeader frame, MaTree globalTree, int bitDepth,
                         MediaLimits limits) {
        this.image = image;
        this.frame = frame;
        this.globalTree = globalTree;
        this.bitDepth = bitDepth;
        this.limits = limits;
        this.start = start;
        var channels = image.channels();
        this.passOf = new int[channels.size()];
        var shifts = frame.passShifts();
        var groupDim = frame.groupDim();
        for (var i = start; i < channels.size(); i++) {
            var channel = channels.get(i);
            if (channel.hshift < 0 || channel.vshift < 0) {
                throw new MediaException.Malformed("meta channel outside the global modular prefix");
            }
            if (channel.hshift >= 3 && channel.vshift >= 3) {
                if ((groupDim >> (channel.hshift - 3)) == 0 || (groupDim >> (channel.vshift - 3)) == 0) {
                    throw new MediaException.Malformed("channel shift too large for its LF group");
                }
                passOf[i] = -1;
                continue;
            }
            var shift = Math.min(channel.hshift, channel.vshift);
            var pass = -2;
            for (var entry : shifts.entrySet()) {
                if (shift >= entry.getValue()[0] && shift < entry.getValue()[1]) {
                    pass = entry.getKey();
                    break;
                }
            }
            if (pass < 0) {
                throw new MediaException.Malformed("no pass completes a modular channel");
            }
            if ((groupDim >> channel.hshift) == 0 || (groupDim >> channel.vshift) == 0) {
                throw new MediaException.Malformed("channel shift too large for its group");
            }
            passOf[i] = pass;
        }
    }

    /**
     * Decodes the modular data of an LF group.
     *
     * @param br the bitstream of the LF group section, positioned at its modular data
     * @param lfGroup the LF group index
     * @throws MediaException.Malformed if the data is invalid
     */
    public void decodeLfGroup(JxlBitReader br, int lfGroup) {
        var perRow = frame.lfGroupsPerRow();
        var gx = lfGroup % perRow;
        var gy = lfGroup / perRow;
        var groupDim = frame.groupDim();
        decodeRegion(br, -1, gx, gy, groupDim, 3, 1 + frame.numLfGroups() + lfGroup);
    }

    /**
     * Decodes the modular data of a pass group.
     *
     * @param br the bitstream of the pass group section, positioned at its modular data
     * @param pass the pass index
     * @param group the group index
     * @throws MediaException.Malformed if the data is invalid
     */
    public void decodePassGroup(JxlBitReader br, int pass, int group) {
        var perRow = frame.groupsPerRow();
        var gx = group % perRow;
        var gy = group / perRow;
        var stream = 1 + 3 * frame.numLfGroups() + 17 + pass * frame.numGroups() + group;
        decodeRegion(br, pass, gx, gy, frame.groupDim(), 0, stream);
    }

    /**
     * Decodes the channels of a pass (or of the LF groups) inside one group rectangle.
     *
     * @param br the bitstream
     * @param pass the pass, or -1 for the LF groups
     * @param gx the group column
     * @param gy the group row
     * @param groupDim the group dimension
     * @param extraShift 3 for LF groups, else 0
     * @param streamIndex the stream index of the group
     */
    private void decodeRegion(JxlBitReader br, int pass, int gx, int gy, int groupDim, int extraShift,
                              int streamIndex) {
        var channels = image.channels();
        var indices = new ArrayList<Integer>();
        var rects = new ArrayList<int[]>();
        var subChannels = new ArrayList<ModularChannel>();
        for (var i = start; i < channels.size(); i++) {
            if (passOf[i] != pass) {
                continue;
            }
            var channel = channels.get(i);
            var gw = groupDim >> (channel.hshift - extraShift);
            var gh = groupDim >> (channel.vshift - extraShift);
            var x0 = (long) gx * gw;
            var y0 = (long) gy * gh;
            if (x0 >= channel.width || y0 >= channel.height) {
                continue;
            }
            var w = (int) Math.min(gw, channel.width - x0);
            var h = (int) Math.min(gh, channel.height - y0);
            if (w == 0 || h == 0) {
                continue;
            }
            indices.add(i);
            rects.add(new int[]{(int) x0, (int) y0, w, h});
            subChannels.add(new ModularChannel(w, h, channel.hshift, channel.vshift));
        }
        if (subChannels.isEmpty()) {
            return;
        }
        var sub = ModularImage.read(br, subChannels, globalTree, bitDepth, limits, true);
        sub.decode(br, streamIndex, sub.channels().size());
        var decoded = sub.finish();
        for (var k = 0; k < indices.size(); k++) {
            var target = channels.get(indices.get(k));
            var rect = rects.get(k);
            var source = decoded.get(k);
            for (var y = 0; y < rect[3]; y++) {
                System.arraycopy(source.data, y * rect[2], target.data, (rect[1] + y) * target.width + rect[0], rect[2]);
            }
        }
    }

    /**
     * Returns whether the first passes of a frame complete every grouped channel that is not the residual of a
     * squeeze step.
     *
     * @param passes the number of leading passes decoded
     * @return whether the channels a progressive decode needs are all decoded after {@code passes} passes
     */
    public boolean completeAfter(int passes) {
        var channels = image.channels();
        for (var i = start; i < passOf.length; i++) {
            if (passOf[i] >= passes && !channels.get(i).residual) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the passes that hold modular data.
     *
     * @return the pass indices with at least one channel
     */
    public List<Integer> passes() {
        var list = new ArrayList<Integer>();
        for (var i = start; i < passOf.length; i++) {
            if (passOf[i] >= 0 && !list.contains(passOf[i])) {
                list.add(passOf[i]);
            }
        }
        return list;
    }
}
