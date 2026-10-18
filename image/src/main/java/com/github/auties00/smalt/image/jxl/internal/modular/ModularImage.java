/* Ports jxl-oxide 0.12.6 (crates/jxl-modular) and libjxl (lib/jxl/modular/encoding/encoding.cc:
   ModularGenericDecompress). Copyright Wonwoo Choi and the jxl-oxide contributors, and the JPEG XL Project Authors;
   MIT License and BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.image.jxl.internal.modular;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.jxl.internal.bitstream.JxlBitReader;
import com.github.auties00.smalt.internal.limits.LimitChecks;

import java.util.ArrayList;
import java.util.List;

/**
 * Decodes a modular image (ISO/IEC 18181-1 annex H): a set of integer channels coded with a
 * meta-adaptive tree after a list of transforms.
 *
 * <p>{@link #read} reads the local header (whether the global tree is used, the weighted
 * predictor parameters and the transforms), applies the transforms forward to the channel list
 * to learn the size of every channel to decode, and reads the local tree when the global one is
 * not used. {@link #decode(JxlBitReader, int, int)} then decodes a prefix of the channels from one
 * entropy stream; the remaining channels of a frame are decoded group by group into the same
 * channel arrays. {@link #finish()} applies the inverse transforms and returns the channels in
 * their original order.
 *
 * <p>Instances are not thread-safe.
 *
 * <p>Channels whose size is zero are skipped, and the previous channels a tree may read are those
 * of the same size and shifts.
 */
public final class ModularImage {
    /**
     * The largest number of transforms accepted.
     */
    private static final int MAX_TRANSFORMS = 512;

    /**
     * The largest number of channels after the transforms.
     */
    private static final int MAX_CHANNELS = 1 << 16;

    /**
     * The weighted predictor parameters.
     */
    private final WpHeader wp;

    /**
     * The transforms, in the order the encoder applied them.
     */
    private final List<ModularTransform> transforms;

    /**
     * The tree of this image, local or global.
     */
    private final MaTree tree;

    /**
     * The channels after the transforms, meta channels first.
     */
    private final List<ModularChannel> channels;

    /**
     * The number of leading meta channels.
     */
    private final int nbMeta;

    /**
     * The bit depth of the image samples.
     */
    private final int bitDepth;

    /**
     * Creates an image.
     *
     * @param wp the weighted predictor parameters
     * @param transforms the transforms, in the order the encoder applied them
     * @param tree the MA tree of the channels
     * @param channels the transformed channels
     * @param nbMeta the number of meta channels
     * @param bitDepth the bit depth
     */
    private ModularImage(WpHeader wp, List<ModularTransform> transforms, MaTree tree, List<ModularChannel> channels,
                         int nbMeta, int bitDepth) {
        this.wp = wp;
        this.transforms = transforms;
        this.tree = tree;
        this.channels = channels;
        this.nbMeta = nbMeta;
        this.bitDepth = bitDepth;
    }

    /**
     * Reads the header of a modular image and prepares its channels.
     *
     * @param br the bitstream
     * @param original the channels before the transforms; their geometry is used, their samples
     * (when present) are ignored
     * @param globalTree the global tree, or {@code null} when the frame has none
     * @param bitDepth the bit depth of the samples
     * @param limits the resource bounds
     * @param allocate whether to allocate the samples of the transformed channels now
     * @return the image, ready to decode
     * @throws MediaException.Malformed if the header or a transform is invalid
     * @throws MediaException.LimitExceeded if the channels exceed the limits
     */
    public static ModularImage read(JxlBitReader br, List<ModularChannel> original, MaTree globalTree, int bitDepth,
                                    MediaLimits limits, boolean allocate) {
        var useGlobal = br.readBool();
        var wp = WpHeader.read(br);
        var count = br.readU32(0, 0, 1, 0, 2, 4, 18, 8);
        if (count > MAX_TRANSFORMS) {
            throw new MediaException.Malformed("too many modular transforms: " + count);
        }
        var transforms = new ArrayList<ModularTransform>(count);
        for (var i = 0; i < count; i++) {
            transforms.add(ModularTransform.read(br));
        }
        var channels = new ArrayList<ModularChannel>(original.size() + 4);
        for (var channel : original) {
            channels.add(channel.copyInfo());
        }
        var nbMeta = 0;
        for (var transform : transforms) {
            nbMeta = transform.applyForward(channels, nbMeta);
            if (channels.size() > MAX_CHANNELS) {
                throw new MediaException.Malformed("too many modular channels");
            }
        }
        var samples = 0L;
        for (var channel : channels) {
            samples += (long) channel.width * channel.height;
        }
        LimitChecks.checkAllocation(limits, samples * 4);
        MaTree tree;
        if (useGlobal) {
            if (globalTree == null) {
                throw new MediaException.Malformed("modular image uses a missing global tree");
            }
            tree = globalTree;
        } else {
            tree = MaTree.read(br, Math.min(1024 + samples, 1 << 20));
        }
        if (allocate) {
            for (var channel : channels) {
                channel.allocate();
            }
        }
        return new ModularImage(wp, transforms, tree, channels, nbMeta, bitDepth);
    }

    /**
     * Returns the channels after the transforms.
     *
     * @return the live channel list
     */
    public List<ModularChannel> channels() {
        return channels;
    }

    /**
     * Returns the number of leading channels decoded with the image itself rather than group by
     * group: the meta channels and the following channels that fit in one group.
     *
     * @param groupDim the group dimension
     * @return the channel count
     */
    public int globalPrefix(int groupDim) {
        var i = 0;
        while (i < channels.size()) {
            var channel = channels.get(i);
            if (i >= nbMeta && (channel.width > groupDim || channel.height > groupDim)) {
                break;
            }
            i++;
        }
        return i;
    }

    /**
     * Decodes the first channels from one entropy stream.
     *
     * @param br the bitstream
     * @param streamIndex the stream index, a property of the tree
     * @param end the number of leading channels to decode
     * @throws MediaException.Malformed if the stream is invalid
     */
    public void decode(JxlBitReader br, int streamIndex, int end) {
        var distMultiplier = 0;
        for (var i = 0; i < end; i++) {
            distMultiplier = Math.max(distMultiplier, channels.get(i).width);
        }
        var decoder = tree.decoder().copy();
        decoder.begin(br);
        var prevBuffer = new int[end][];
        for (var i = 0; i < end; i++) {
            var channel = channels.get(i);
            if (channel.width == 0 || channel.height == 0) {
                continue;
            }
            channel.allocate();
            var prevCount = 0;
            for (var j = i - 1; j >= 0; j--) {
                var other = channels.get(j);
                if (other.width != 0 && other.height != 0 && other.sameGeometry(channel)) {
                    prevBuffer[prevCount++] = other.data;
                }
            }
            var pruned = tree.prune(i, streamIndex, prevCount);
            var prev = new int[Math.min(prevCount, pruned.maxPrev())][];
            System.arraycopy(prevBuffer, 0, prev, 0, prev.length);
            ChannelDecoder.decode(br, decoder, pruned, wp, channel.data, channel.width, channel.height, prev,
                    distMultiplier);
        }
        decoder.checkFinalState();
    }

    /**
     * Applies the inverse transforms.
     *
     * @return the channels in their original order and geometry
     */
    public List<ModularChannel> finish() {
        for (var i = transforms.size() - 1; i >= 0; i--) {
            transforms.get(i).inverse(channels, bitDepth, wp);
        }
        for (var channel : channels) {
            channel.allocate();
        }
        return channels;
    }
}
