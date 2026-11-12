package com.github.auties00.smalt.internal.heif;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.io.FourCc;
import com.github.auties00.smalt.io.DataReference;
import com.github.auties00.smalt.io.DataReferenceResolver;
import com.github.auties00.smalt.io.MediaSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses data reference boxes ({@code dref}, ISO/IEC 14496-12 clause 8.7.2, and the QuickTime data reference atom)
 * and opens the files their entries name through a {@link DataReferenceResolver}, each at most once.
 *
 * <p>Instances are not thread-safe; {@link #close()} closes every source they opened.
 */
public final class DataReferences implements AutoCloseable {
    /**
     * The most entries read from one {@code dref} box.
     */
    public static final int MAX_ENTRIES = 256;

    /**
     * The flag of an entry whose data is in the file holding the box.
     */
    private static final int SELF_CONTAINED = 1;

    /**
     * The size of the fixed part of a version 2 alias record; the tagged extra data follows it.
     */
    private static final int ALIAS_FIXED_SIZE = 150;

    /**
     * The offset of the file name ({@code Str63}) in an alias record.
     */
    private static final int ALIAS_FILE_NAME = 50;

    /**
     * The alias extra data tag of the absolute Mac OS path, with {@code :} separators.
     */
    private static final int ALIAS_TAG_PATH = 2;

    /**
     * The alias extra data tag of the file name in UTF-16.
     */
    private static final int ALIAS_TAG_UNICODE_NAME = 14;

    /**
     * The alias extra data tag of the POSIX path.
     */
    private static final int ALIAS_TAG_POSIX_PATH = 18;

    private final DataReferenceResolver resolver;
    private final Map<DataReference, MediaSource> opened = new HashMap<>();
    private final List<MediaSource> sources = new ArrayList<>();

    /**
     * Constructs an opener over a resolver.
     *
     * @param resolver the resolver asked for each entry, once
     */
    public DataReferences(DataReferenceResolver resolver) {
        this.resolver = resolver;
    }

    /**
     * One entry of a {@code dref} box.
     *
     * @param type the entry type
     * @param selfContained whether the data of the entry is in the file holding the box
     * @param reference the file it names, or {@code null} when it is self-contained or of a type that names no
     * file Smalt can describe
     */
    public record Entry(int type, boolean selfContained, DataReference reference) {
    }

    /**
     * Parses the payload of a {@code dref} box, at most {@value #MAX_ENTRIES} entries.
     *
     * @param dref the payload
     * @return the entries, in the order of their one-based data reference index
     * @throws MediaException.Malformed if the box is truncated
     */
    public static List<Entry> parse(BoxCursor dref) {
        dref.fullBoxHeader();
        var count = (int) Math.min(dref.u32(), MAX_ENTRIES);
        var entries = new ArrayList<Entry>(count);
        for (var index = 0; index < count && dref.hasBox(); index++) {
            var entry = dref.nextBox();
            var flags = entry.remaining() >= 4 ? entry.fullBoxHeader() & 0xFFFFFF : 0;
            if ((flags & SELF_CONTAINED) != 0) {
                entries.add(new Entry(entry.type(), true, null));
                continue;
            }
            var reference = switch (entry.type()) {
                case 0x75726C20 -> new DataReference(DataReference.Kind.URL, string(entry), "");
                case 0x75726E20 -> {
                    var name = string(entry);
                    yield new DataReference(DataReference.Kind.URN, string(entry), name);
                }
                case 0x616C6973 -> alias(entry);
                default -> null;
            };
            entries.add(new Entry(entry.type(), false, reference));
        }
        return entries;
    }

    /**
     * Returns the source of an entry: {@code null} for the file itself, the resolved file otherwise.
     *
     * @param entry the entry of the {@code dref} box
     * @param format the format of the failure, {@code "mp4 external data reference"} or {@code "heif"}
     * @param what the data that uses it, for the failure
     * @return the source, or {@code null} when the data is in the file holding the box
     * @throws MediaException.Unsupported if the entry names a file the resolver does not resolve
     */
    public MediaSource open(Entry entry, String format, String what) {
        if (entry.selfContained()) {
            return null;
        }
        var reference = entry.reference();
        if (reference == null) {
            throw new MediaException.Unsupported(format, what + " is stored in another file, named by a '"
                    + FourCc.printable(entry.type()) + "' data reference, which Smalt does not read");
        }
        if (opened.containsKey(reference)) {
            var source = opened.get(reference);
            if (source == null) {
                throw unresolved(reference, format, what);
            }
            return source;
        }
        var resolved = resolver.resolve(reference);
        var source = resolved == null ? null : resolved.orElse(null);
        opened.put(reference, source);
        if (source == null) {
            throw unresolved(reference, format, what);
        }
        sources.add(source);
        return source;
    }

    /**
     * {@return whether no source has been opened}
     */
    public boolean isEmpty() {
        return sources.isEmpty();
    }

    @Override
    public void close() {
        RuntimeException failure = null;
        for (var source : sources) {
            try {
                source.close();
            } catch (RuntimeException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        sources.clear();
        opened.clear();
        if (failure != null) {
            throw failure;
        }
    }

    private static MediaException.Unsupported unresolved(DataReference reference, String format, String what) {
        var named = reference.location().isEmpty() ? reference.name() : reference.location();
        return new MediaException.Unsupported(format,
                what + " is stored in another file (" + reference.kind().name().toLowerCase(java.util.Locale.ROOT)
                        + " '" + named + "'), which the data reference resolver does not resolve");
    }

    /**
     * Reads a string ending at a zero byte or at the end of the entry, as UTF-8.
     */
    private static String string(BoxCursor entry) {
        var data = entry.data();
        var start = entry.position();
        var end = start;
        var limit = start + entry.remaining();
        while (end < limit && data[end] != 0) {
            end++;
        }
        entry.skip(Math.min(entry.remaining(), end - start + 1L));
        return new String(data, start, end - start, StandardCharsets.UTF_8);
    }

    /**
     * Parses a Mac OS alias record (version 2): its file name, and the POSIX or else Mac OS path of its tagged
     * extra data.
     */
    private static DataReference alias(BoxCursor entry) {
        var data = entry.data();
        var start = entry.position();
        var length = entry.remaining();
        var name = "";
        var path = "";
        if (length > ALIAS_FILE_NAME) {
            var nameLength = Math.min(data[start + ALIAS_FILE_NAME] & 0xFF, Math.min(63, length - ALIAS_FILE_NAME - 1));
            name = new String(data, start + ALIAS_FILE_NAME + 1, nameLength, StandardCharsets.ISO_8859_1);
        }
        var hfsPath = "";
        var position = ALIAS_FIXED_SIZE;
        while (position + 4 <= length) {
            var tag = ((data[start + position] & 0xFF) << 8) | (data[start + position + 1] & 0xFF);
            var size = ((data[start + position + 2] & 0xFF) << 8) | (data[start + position + 3] & 0xFF);
            if (tag == 0xFFFF || position + 4 + size > length) {
                break;
            }
            var at = start + position + 4;
            switch (tag) {
                case ALIAS_TAG_PATH -> hfsPath = new String(data, at, size, StandardCharsets.ISO_8859_1);
                case ALIAS_TAG_POSIX_PATH -> path = new String(data, at, size, StandardCharsets.UTF_8);
                case ALIAS_TAG_UNICODE_NAME -> {
                    if (size >= 2) {
                        var characters = ((data[at] & 0xFF) << 8) | (data[at + 1] & 0xFF);
                        name = new String(data, at + 2, Math.min(characters * 2, size - 2), StandardCharsets.UTF_16BE);
                    }
                }
                default -> {
                }
            }
            position += 4 + size + (size & 1);
        }
        return new DataReference(DataReference.Kind.ALIAS, path.isEmpty() ? hfsPath : path, name);
    }
}
