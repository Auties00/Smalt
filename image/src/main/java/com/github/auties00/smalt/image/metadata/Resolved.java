package com.github.auties00.smalt.image.metadata;

import com.github.auties00.smalt.image.metadata.ImageMetadataEdit.Change;
import com.github.auties00.smalt.metadata.XmpPacket;

import java.util.List;
import java.util.Map;

/**
 * Holds the metadata an edit resolves to for one file: for each kind, whether the file's own
 * bytes are kept, removed, or replaced by a new value.
 *
 * @param exif        what happens to the EXIF block, with the new block when replaced
 * @param xmp         what happens to the XMP packet, with the new packet when replaced
 * @param iptc        what happens to the IPTC data, with the new data when replaced
 * @param icc         what happens to the ICC profile, with the new profile bytes when replaced
 * @param comments    what happens to the comments, with the new comments when replaced
 * @param text        what happens to the PNG text, with the new text when replaced
 * @param removeOther whether the metadata of no other kind is removed
 */
record Resolved(Change<byte[]> exif, Change<XmpPacket> xmp, Change<IptcData> iptc, Change<byte[]> icc,
                Change<List<String>> comments, Change<Map<String, String>> text, boolean removeOther) {
    /**
     * Returns whether the resolution changes nothing.
     *
     * @return {@code true} when every kind is kept and nothing else is removed
     */
    boolean keepsAll() {
        return exif instanceof Change.Keep && xmp instanceof Change.Keep && iptc instanceof Change.Keep
                && icc instanceof Change.Keep && comments instanceof Change.Keep && text instanceof Change.Keep
                && !removeOther;
    }

    /**
     * Returns whether the resolution adds or replaces anything.
     *
     * @return {@code true} when a kind is replaced
     */
    boolean replacesAny() {
        return exif instanceof Change.Replace || xmp instanceof Change.Replace || iptc instanceof Change.Replace
                || icc instanceof Change.Replace || comments instanceof Change.Replace
                || text instanceof Change.Replace;
    }

    /**
     * {@return the new EXIF block when replaced, otherwise {@code null}}
     */
    byte[] exifBytes() {
        return exif instanceof Change.Replace<byte[]>(var value) ? value : null;
    }

    /**
     * {@return the new XMP packet when replaced, otherwise {@code null}}
     */
    XmpPacket xmpPacket() {
        return xmp instanceof Change.Replace<XmpPacket>(var value) ? value : null;
    }

    /**
     * {@return the new IPTC data when replaced, otherwise {@code null}}
     */
    IptcData iptcData() {
        return iptc instanceof Change.Replace<IptcData>(var value) ? value : null;
    }

    /**
     * {@return the new ICC profile bytes when replaced, otherwise {@code null}}
     */
    byte[] iccProfile() {
        return icc instanceof Change.Replace<byte[]>(var value) ? value : null;
    }

    /**
     * {@return the new comments when replaced, otherwise an empty list}
     */
    List<String> commentList() {
        return comments instanceof Change.Replace<List<String>>(var value) ? value : List.of();
    }

    /**
     * {@return the new PNG text when replaced, otherwise an empty map}
     */
    Map<String, String> textEntries() {
        return text instanceof Change.Replace<Map<String, String>>(var value) ? value : Map.of();
    }
}
