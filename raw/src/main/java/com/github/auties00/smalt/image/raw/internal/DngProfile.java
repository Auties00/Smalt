/* Ports the Digital Negative (DNG) Specification 1.7.1.0, Adobe. This product includes DNG technology under license
   by Adobe. */
package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.image.tiff.internal.IfdBuilder;
import com.github.auties00.smalt.internal.tiff.TiffTypes;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds the entries of a DNG camera profile as a DNG file converted from its source carries them over, in the byte
 * order the source stores them in, and writes them as a camera profile structure ({@code ExtraCameraProfiles}): a
 * byte order mark, the magic number {@code 0x4352} ("CR"), the offset of a TIFF directory and the directory, every
 * offset relative to the byte order mark.
 *
 * <p>Undefined data whose fields the specification leaves in the file's byte order (such as {@code RGBTables} or
 * {@code ProfileDynamicRange}) stays valid because the structure keeps the source's order.
 *
 * @param little whether the entries are little-endian
 * @param entries the entries, values in that byte order
 */
record DngProfile(boolean little, List<DngTag> entries) {
    /**
     * The magic number of a camera profile structure.
     */
    static final int MAGIC = 0x4352;

    /**
     * Reads the entries of a camera profile directory, every entry whose value can be read other than pointers to
     * other directories.
     *
     * @param ifd the camera profile directory
     * @return the profile
     */
    static DngProfile of(Ifd ifd) {
        var entries = new ArrayList<DngTag>();
        for (var index = 0; index < ifd.count(); index++) {
            var tag = ifd.tagAt(index);
            var type = ifd.type(tag);
            if (type == TiffTypes.IFD || type == TiffTypes.IFD8 || tag == TiffTree.SUB_IFDS || tag == TiffTree.EXIF_IFD
                    || tag == TiffTree.GPS_IFD) {
                continue;
            }
            var entry = DngTag.stored(ifd, tag);
            if (entry != null) {
                entries.add(entry);
            }
        }
        return new DngProfile(ifd.reader().little(), List.copyOf(entries));
    }

    /**
     * Writes the profile as a camera profile structure.
     *
     * @return the bytes, of even length
     */
    byte[] structure() {
        var ifd = new IfdBuilder(little, false);
        for (var entry : entries) {
            ifd.raw(entry.tag(), entry.type(), entry.count(), entry.value());
        }
        var directory = ifd.write(8, 0);
        var out = new byte[8 + directory.length];
        out[0] = out[1] = (byte) (little ? 'I' : 'M');
        out[little ? 2 : 3] = (byte) MAGIC;
        out[little ? 3 : 2] = (byte) (MAGIC >>> 8);
        out[little ? 4 : 7] = 8;
        System.arraycopy(directory, 0, out, 8, directory.length);
        return out;
    }
}
