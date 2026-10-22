package com.github.auties00.smalt.testkit.validate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Parses the box tree of an ISO base media file (ISO/IEC 14496-12, and QuickTime's variant of it), independently of
 * Smalt's demuxer, for the validators.
 *
 * <p>The parser descends into the container boxes, the sample description, the sample entries of video and audio
 * tracks and the data reference; every other box is a leaf. A box whose size does not fit its parent is reported to
 * the findings and ends the parent's children.
 */
public final class IsoBmff {
    /** The boxes whose payload is a sequence of boxes. */
    private static final Set<String> CONTAINERS = Set.of("moov", "trak", "edts", "mdia", "minf", "dinf", "stbl",
            "mvex", "moof", "traf", "mfra", "udta", "tref", "sinf", "schi", "rinf", "gmhd", "wave", "ilst", "iprp",
            "ipco", "meco", "strk", "strd");

    /** The sample entry types of audio, whose fixed part follows the QuickTime versions. */
    private static final Set<String> AUDIO_HANDLERS = Set.of("soun");

    /** The handlers whose sample entries have the visual layout. */
    private static final Set<String> VIDEO_HANDLERS = Set.of("vide", "pict", "auxv");

    private IsoBmff() {
        throw new AssertionError();
    }

    /**
     * A box.
     *
     * @param type the four-character type
     * @param offset the offset of the box's first byte in the file
     * @param header the size of the header (8, 16 for a 64-bit size, plus 16 for a {@code uuid})
     * @param size the size of the whole box
     * @param children the boxes inside it, empty for a leaf
     * @param childrenOffset the offset of the first child, or of the payload end for a leaf
     */
    public record Box(String type, long offset, int header, long size, List<Box> children, long childrenOffset) {
        /** {@return the offset of the payload's first byte} */
        public long payload() {
            return offset + header;
        }

        /** {@return the offset just past the box} */
        public long end() {
            return offset + size;
        }

        /** {@return the size of the payload} */
        public long payloadSize() {
            return size - header;
        }

        /**
         * Returns the first child of a type.
         *
         * @param childType the type
         * @return the child, or empty when absent
         */
        public Optional<Box> child(String childType) {
            for (var child : children) {
                if (child.type.equals(childType)) {
                    return Optional.of(child);
                }
            }
            return Optional.empty();
        }

        /**
         * Returns the children of a type.
         *
         * @param childType the type
         * @return the children, in file order
         */
        public List<Box> all(String childType) {
            var found = new ArrayList<Box>();
            for (var child : children) {
                if (child.type.equals(childType)) {
                    found.add(child);
                }
            }
            return found;
        }

        /**
         * Returns the descendant at a path of types.
         *
         * @param path the types, for example {@code "mdia", "minf", "stbl"}
         * @return the box, or empty when a step is absent
         */
        public Optional<Box> find(String... path) {
            var box = Optional.of(this);
            for (var step : path) {
                box = box.flatMap(current -> current.child(step));
            }
            return box;
        }

        @Override
        public String toString() {
            return type + "@" + offset;
        }
    }

    /**
     * Parses the top-level boxes of a file.
     *
     * @param data the file
     * @param findings receives the structural problems
     * @return the top-level boxes
     */
    public static List<Box> parse(byte[] data, Findings findings) {
        return new Parser(data, quickTime(data)).children(0, data.length, "", null, findings, true);
    }

    /** {@return whether a file's {@code ftyp} names the QuickTime brand, whose audio sample entries differ} */
    static boolean quickTime(byte[] data) {
        if (data.length < 16 || !fourcc(data, 4).equals("ftyp")) {
            return false;
        }
        var end = Math.min(data.length, u32(data, 0));
        for (var offset = 8L; offset + 4 <= end; offset += offset == 8 ? 8 : 4) {
            if (fourcc(data, offset).equals("qt  ")) {
                return true;
            }
        }
        return false;
    }

    /** Parses the boxes of one file. */
    private record Parser(byte[] data, boolean quickTime) {
        private List<Box> children(long from, long to, String parent, String handler, Findings findings,
                                   boolean topLevel) {
            var boxes = new ArrayList<Box>();
            var position = from;
            while (position < to) {
                var where = parent + "/@" + position;
                if (to - position < 8) {
                    // QuickTime allows a four-byte zero terminator at the end of a udta list
                    if (!(to - position == 4 && u32(data, position) == 0 && parent.endsWith("udta"))) {
                        findings.add(where, (to - position) + " trailing bytes that do not form a box");
                    }
                    break;
                }
                long size = u32(data, position);
                var type = fourcc(data, position + 4);
                var header = 8;
                if (size == 1) {
                    if (to - position < 16) {
                        findings.add(where, "a 64-bit box size runs past its parent");
                        break;
                    }
                    size = s64(data, position + 8);
                    header = 16;
                } else if (size == 0) {
                    if (!topLevel) {
                        findings.add(where, "a box of size 0 (to the end of the file) is not at the top level");
                    }
                    size = to - position;
                }
                if (type.equals("uuid")) {
                    header += 16;
                }
                if (size < header || position + size > to) {
                    findings.add(parent + "/" + type + "@" + position, "size " + size + " does not fit "
                            + (topLevel ? "the file" : "its parent") + " (" + (to - position) + " bytes left)");
                    break;
                }
                var path = parent + "/" + type;
                var payload = position + header;
                var end = position + size;
                List<Box> kids = List.of();
                var childrenOffset = end;
                if (type.equals("trak")) {
                    // the handler of a track decides how its sample entries are laid out
                    var probe = new Box(type, position, header, size,
                            children(payload, end, path, handler, new Findings(""), false), payload);
                    var hdlr = probe.find("mdia", "hdlr");
                    var kind = hdlr.isPresent() && hdlr.get().payloadSize() >= 12
                            ? fourcc(data, hdlr.get().payload() + 8) : null;
                    kids = children(payload, end, path, kind, findings, false);
                    childrenOffset = payload;
                } else if (CONTAINERS.contains(type)) {
                    kids = children(payload, end, path, handler, findings, false);
                    childrenOffset = payload;
                } else if (type.equals("meta")) {
                    // ISO meta is a full box; QuickTime's meta in udta is a plain container
                    var start = isBoxAt(payload, end) ? payload : payload + 4;
                    kids = children(start, end, path, handler, findings, false);
                    childrenOffset = start;
                } else if (type.equals("dref") && end - payload >= 8) {
                    kids = children(payload + 8, end, path, handler, findings, false);
                    childrenOffset = payload + 8;
                } else if (type.equals("stsd") && end - payload >= 8) {
                    kids = sampleEntries(payload + 8, end, path, handler, findings);
                    childrenOffset = payload + 8;
                }
                boxes.add(new Box(type, position, header, size, kids, childrenOffset));
                position = end;
            }
            return boxes;
        }

        private List<Box> sampleEntries(long from, long to, String parent, String handler, Findings findings) {
            var entries = new ArrayList<Box>();
            var position = from;
            while (position + 8 <= to) {
                long size = u32(data, position);
                var type = fourcc(data, position + 4);
                if (size < 8 || position + size > to) {
                    findings.add(parent + "/" + type + "@" + position, "sample entry size " + size
                            + " does not fit stsd");
                    break;
                }
                var end = position + size;
                var fixed = -1L;
                if (handler != null && VIDEO_HANDLERS.contains(handler)) {
                    fixed = 8 + 78;
                } else if (handler != null && AUDIO_HANDLERS.contains(handler) && size >= 8 + 28) {
                    // ISO's version 1 entry keeps the layout; QuickTime's versions 1 and 2 extend it
                    var version = u16(data, position + 16);
                    fixed = 8 + 28 + (!quickTime ? 0 : version == 1 ? 16 : version == 2 ? 36 : 0);
                }
                List<Box> kids = List.of();
                var childrenOffset = end;
                if (fixed > 0) {
                    if (fixed > size) {
                        findings.add(parent + "/" + type + "@" + position, "sample entry of " + size
                                + " bytes is shorter than its fixed part (" + fixed + ")");
                    } else {
                        kids = children(position + fixed, end, parent + "/" + type, handler, findings, false);
                        childrenOffset = position + fixed;
                    }
                }
                entries.add(new Box(type, position, 8, size, kids, childrenOffset));
                position = end;
            }
            if (position != to) {
                findings.add(parent + "@" + position, (to - position) + " bytes after the last sample entry");
            }
            return entries;
        }

        private boolean isBoxAt(long position, long end) {
            if (end - position < 8) {
                return false;
            }
            var size = u32(data, position);
            if (size < 8 || position + size > end) {
                return false;
            }
            for (var i = 4; i < 8; i++) {
                var c = data[(int) position + i] & 0xFF;
                if (c < 0x20 || c > 0x7E) {
                    return false;
                }
            }
            return true;
        }

    }

    /**
     * Returns the first top-level box of a type.
     *
     * @param boxes the top-level boxes
     * @param type the type
     * @return the box, or empty when absent
     */
    public static Optional<Box> top(List<Box> boxes, String type) {
        return boxes.stream().filter(box -> box.type().equals(type)).findFirst();
    }

    /** {@return the four characters at an offset, as text} */
    static String fourcc(byte[] data, long offset) {
        return new String(data, (int) offset, 4, StandardCharsets.ISO_8859_1);
    }

    /** {@return the unsigned 8-bit value at an offset} */
    static int u8(byte[] data, long offset) {
        return data[(int) offset] & 0xFF;
    }

    /** {@return the unsigned big-endian 16-bit value at an offset} */
    static int u16(byte[] data, long offset) {
        return (data[(int) offset] & 0xFF) << 8 | data[(int) offset + 1] & 0xFF;
    }

    /** {@return the unsigned big-endian 24-bit value at an offset} */
    static int u24(byte[] data, long offset) {
        return (data[(int) offset] & 0xFF) << 16 | (data[(int) offset + 1] & 0xFF) << 8 | data[(int) offset + 2] & 0xFF;
    }

    /** {@return the unsigned big-endian 32-bit value at an offset} */
    static long u32(byte[] data, long offset) {
        return ((long) (data[(int) offset] & 0xFF) << 24) | (data[(int) offset + 1] & 0xFF) << 16
                | (data[(int) offset + 2] & 0xFF) << 8 | data[(int) offset + 3] & 0xFF;
    }

    /** {@return the signed big-endian 32-bit value at an offset} */
    static int s32(byte[] data, long offset) {
        return (int) u32(data, offset);
    }

    /** {@return the big-endian 64-bit value at an offset} */
    static long s64(byte[] data, long offset) {
        return u32(data, offset) << 32 | u32(data, offset + 4);
    }

    /** {@return the version of a full box} */
    static int version(byte[] data, Box box) {
        return u8(data, box.payload());
    }

    /** {@return the flags of a full box} */
    static int flags(byte[] data, Box box) {
        return u24(data, box.payload() + 1);
    }
}
