package com.github.auties00.smalt.internal.tags;

import com.github.auties00.smalt.internal.io.ByteWriter;
import com.github.auties00.smalt.metadata.StandardTag;
import com.github.auties00.smalt.metadata.TrackTags;
import com.github.auties00.smalt.track.TrackDisposition;
import com.github.auties00.smalt.track.TrackKind;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Encodes and decodes the metadata of an MP4 track: the {@code name} box of its {@code udta}, the
 * {@code kind} boxes of the DASH role scheme, an iTunes {@code meta} box for its other tags, and
 * the enabled flag of its {@code tkhd}.
 */
public final class Mp4TrackMetadata {
    /**
     * The scheme of the {@code kind} boxes that store dispositions (ISO/IEC 23009-1 roles).
     */
    public static final String ROLE_SCHEME = "urn:mpeg:dash:role:2011";

    /**
     * The {@code tkhd} flag of an enabled track.
     */
    public static final int ENABLED = 1;

    /**
     * The roles written, in order, with the dispositions each one stores.
     */
    private static final List<Map.Entry<String, Set<TrackDisposition>>> ROLES = List.of(
            Map.entry("dub", EnumSet.of(TrackDisposition.DUB)),
            Map.entry("commentary", EnumSet.of(TrackDisposition.COMMENTARY)),
            Map.entry("forced-subtitle", EnumSet.of(TrackDisposition.FORCED)),
            Map.entry("caption", EnumSet.of(TrackDisposition.CAPTIONS, TrackDisposition.HEARING_IMPAIRED)),
            Map.entry("description", EnumSet.of(TrackDisposition.DESCRIPTIONS, TrackDisposition.VISUAL_IMPAIRED)),
            Map.entry("karaoke", EnumSet.of(TrackDisposition.KARAOKE)));

    private Mp4TrackMetadata() {
        throw new AssertionError();
    }

    /**
     * Returns the dispositions a {@code kind} box stores.
     *
     * @param scheme the scheme URI
     * @param value the role value, such as {@code commentary}
     * @return the dispositions, empty for another scheme or an unknown role
     */
    public static Set<TrackDisposition> dispositions(String scheme, String value) {
        if (!ROLE_SCHEME.equals(scheme)) {
            return Set.of();
        }
        for (var role : ROLES) {
            if (role.getKey().equals(value)) {
                return role.getValue();
            }
        }
        return Set.of();
    }

    /**
     * Returns the roles that store a set of dispositions, one {@code kind} box each.
     *
     * @param disposition the dispositions
     * @return the role values, in writing order
     */
    static List<String> roles(Set<TrackDisposition> disposition) {
        var result = new ArrayList<String>();
        for (var role : ROLES) {
            for (var value : role.getValue()) {
                if (disposition.contains(value)) {
                    result.add(role.getKey());
                    break;
                }
            }
        }
        return result;
    }

    /**
     * Encodes the children of a track's {@code udta} that store its tags: a {@code name} box, one
     * {@code kind} box per role, and a {@code meta} box holding an {@code ilst} with the other
     * entries.
     *
     * @param track the tags of the track
     * @return the boxes, possibly none
     */
    static byte[] userDataChildren(TrackTags track) {
        return userDataChildren(track, List.of());
    }

    /**
     * Encodes the children of a track's {@code udta} that store its tags, keeping opaque {@code ilst} items.
     *
     * @param track the tags of the track
     * @param opaque the opaque {@code ilst} items to keep, each a complete box
     * @return the boxes, possibly none
     */
    static byte[] userDataChildren(TrackTags track, List<byte[]> opaque) {
        var out = new ByteWriter(256);
        var name = track.name().orElse("");
        if (!name.isEmpty()) {
            Mp4Boxes.box(out, "name", name.getBytes(StandardCharsets.UTF_8));
        }
        for (var role : roles(track.disposition())) {
            var kind = new ByteWriter(64);
            kind.u32be(0);
            kind.bytes(ROLE_SCHEME.getBytes(StandardCharsets.US_ASCII));
            kind.u8(0);
            kind.bytes(role.getBytes(StandardCharsets.US_ASCII));
            kind.u8(0);
            Mp4Boxes.box(out, "kind", kind.toByteArray());
        }
        var others = track.tags().toBuilder().remove(StandardTag.TITLE).build();
        if (!others.entries().isEmpty() || !opaque.isEmpty()) {
            var meta = new ByteWriter(256);
            meta.u32be(0);
            meta.bytes(Mp4TagFile.handler());
            meta.bytes(Mp4TagFile.ilst(others, opaque, Map.of(), new HashSet<>()));
            Mp4Boxes.box(out, "meta", meta.toByteArray());
        }
        return out.toByteArray();
    }

    /**
     * Encodes the {@code udta} box of a track.
     *
     * @param track the tags of the track
     * @return the box, or an empty array when the track has no name, roles or other tags
     */
    public static byte[] userData(TrackTags track) {
        var children = userDataChildren(track);
        return children.length == 0 ? children : Mp4Boxes.box("udta", children);
    }

    /**
     * Decides which tracks are enabled.
     *
     * <p>A track without tags stays enabled; a track with tags is enabled when its dispositions hold
     * {@link TrackDisposition#DEFAULT}. When no video or no audio track would be enabled, the first
     * one of that kind is, since players present no disabled track.
     *
     * @param kinds the kind of every track, in movie order
     * @param tags the tags of every track, in movie order, {@code null} for a track without tags
     * @return whether each track is enabled
     */
    public static boolean[] enabled(List<TrackKind> kinds, List<TrackTags> tags) {
        var result = new boolean[kinds.size()];
        for (var index = 0; index < result.length; index++) {
            result[index] = tags.get(index) == null || tags.get(index).has(TrackDisposition.DEFAULT);
        }
        for (var kind : List.of(TrackKind.VIDEO, TrackKind.AUDIO)) {
            var first = -1;
            var any = false;
            for (var index = 0; index < result.length; index++) {
                if (kinds.get(index) == kind) {
                    if (first < 0) {
                        first = index;
                    }
                    any |= result[index];
                }
            }
            if (first >= 0 && !any) {
                result[first] = true;
            }
        }
        return result;
    }
}
