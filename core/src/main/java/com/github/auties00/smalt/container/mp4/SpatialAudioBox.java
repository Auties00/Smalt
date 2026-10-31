package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.audio.Ambisonics;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.internal.heif.BoxCursor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads and writes the spatial audio box ({@code SA3D}) of the Google Spatial Audio RFC, which an audio sample
 * entry carries to declare a periphonic ambisonic sound field in ACN order with SN3D normalization, optionally
 * with a head-locked stereo pair.
 *
 * <p>The box holds a version byte (0), a byte whose high bit flags head-locked stereo and whose low seven bits
 * are the ambisonic type (0, periphonic), the order (32 bits), the channel ordering (0, ACN), the normalization
 * (0, SN3D), the channel count (32 bits) and one 32-bit entry per channel of the track, in channel order: the ACN
 * of the component the channel carries, or {@code (order + 1)^2} and {@code (order + 1)^2 + 1} for the
 * head-locked left and right channels.
 */
final class SpatialAudioBox {
    /**
     * The flag of the type byte that declares a head-locked stereo pair.
     */
    private static final int HEAD_LOCKED_STEREO = 0x80;

    private SpatialAudioBox() {
    }

    /**
     * Parses the payload of a box.
     *
     * @param body the box payload
     * @return the layout, or {@code null} when the box declares a version, type, ordering or normalization this
     * parser does not know or an inconsistent channel map
     */
    static ChannelLayout parse(BoxCursor body) {
        if (body.remaining() < 12 || body.u8() != 0) {
            return null;
        }
        var type = body.u8();
        var headLocked = (type & HEAD_LOCKED_STEREO) != 0;
        var order = body.u32();
        var ordering = body.u8();
        var normalization = body.u8();
        var channels = body.u32();
        if ((type & ~HEAD_LOCKED_STEREO) != 0 || ordering != 0 || normalization != 0 || order > Ambisonics.MAX_ORDER
                || channels == 0 || channels > body.remaining() / 4) {
            return null;
        }
        var count = (int) ((order + 1) * (order + 1));
        if (channels != count + (headLocked ? 2 : 0)) {
            return null;
        }
        var speakers = new ArrayList<Speaker>((int) channels);
        var components = new ArrayList<Integer>(count);
        var seen = new boolean[(int) channels];
        for (var channel = 0; channel < channels; channel++) {
            var entry = body.u32();
            if (entry >= seen.length || seen[(int) entry]) {
                return null;
            }
            seen[(int) entry] = true;
            if (entry < count) {
                speakers.add(Speaker.UNKNOWN);
                components.add((int) entry);
            } else {
                speakers.add(entry == count ? Speaker.FRONT_LEFT : Speaker.FRONT_RIGHT);
            }
        }
        return new ChannelLayout(speakers, Optional.of(new Ambisonics(Ambisonics.Normalization.SN3D, components)));
    }

    /**
     * Writes the box of a layout when the box can describe it.
     *
     * <p>The layout must carry every component of its order with SN3D normalization, and besides the components
     * at most the pair of front left and front right channels, which become the head-locked stereo pair; other
     * layouts are left without a box.
     *
     * @param writer the destination, inside the sample entry
     * @param layout the channel layout of the track, or {@code null}
     * @param channels the channel count of the sample entry
     */
    static void write(BoxWriter writer, ChannelLayout layout, int channels) {
        if (layout == null || layout.channels() != channels || layout.ambisonics().isEmpty()) {
            return;
        }
        var ambisonics = layout.ambisonics().get();
        if (ambisonics.normalization() != Ambisonics.Normalization.SN3D || !ambisonics.isFull()) {
            return;
        }
        var speakers = layout.speakers();
        var extra = speakers.size() - ambisonics.channels();
        var headLocked = extra == 2 && speakers.contains(Speaker.FRONT_LEFT) && speakers.contains(Speaker.FRONT_RIGHT);
        if (extra != 0 && !headLocked) {
            return;
        }
        var order = ambisonics.order();
        var count = (order + 1) * (order + 1);
        writer.begin(BoxTypes.SA3D);
        writer.u8(0);
        writer.u8(headLocked ? HEAD_LOCKED_STEREO : 0);
        writer.u32(order);
        writer.u8(0);
        writer.u8(0);
        writer.u32(channels);
        List<Integer> components = ambisonics.components();
        var k = 0;
        for (var speaker : speakers) {
            writer.u32(switch (speaker) {
                case UNKNOWN -> components.get(k++);
                case FRONT_LEFT -> count;
                default -> count + 1;
            });
        }
        writer.end();
    }
}
