package com.github.auties00.smalt.internal.tags;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.heif.BoxCursor;
import com.github.auties00.smalt.internal.io.FourCc;
import com.github.auties00.smalt.internal.limits.LimitChecks;

import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Decompresses a QuickTime compressed movie atom ({@code cmov}, QuickTime File Format, "Compressed Movie
 * Resources"), the only child of a {@code moov} atom whose content is compressed: a {@code dcom} atom naming the
 * algorithm, which is {@code zlib}, and a {@code cmvd} atom holding the size of the uncompressed movie atom and its
 * zlib stream. The offsets the uncompressed movie stores count in the file as it is.
 */
public final class QuickTimeCompressedMovie {
    /**
     * The type of the compressed movie atom.
     */
    public static final int CMOV = 0x636D6F76;

    /**
     * The type of the data compression atom.
     */
    private static final int DCOM = 0x64636F6D;

    /**
     * The type of the compressed movie data atom.
     */
    private static final int CMVD = 0x636D7664;

    /**
     * The type of the movie atom.
     */
    private static final int MOOV = 0x6D6F6F76;

    /**
     * The zlib compression algorithm of a {@code dcom} atom.
     */
    private static final int ZLIB = 0x7A6C6962;

    private QuickTimeCompressedMovie() {
    }

    /**
     * Decompresses a {@code cmov} atom into the {@code moov} atom it holds.
     *
     * @param cmov the payload of the {@code cmov} atom
     * @param limits the bound of the uncompressed size
     * @return the uncompressed {@code moov} atom, header included
     * @throws MediaException.Malformed if an atom is missing or the zlib stream is corrupt or does not hold one
     * {@code moov} atom of the declared size
     * @throws MediaException.Unsupported if the movie is compressed with another algorithm than zlib
     * @throws MediaException.LimitExceeded if the uncompressed movie exceeds the byte limit
     */
    public static byte[] inflate(BoxCursor cmov, MediaLimits limits) {
        var algorithm = -1;
        BoxCursor data = null;
        while (cmov.hasBox()) {
            var box = cmov.nextBox();
            if (box.type() == DCOM) {
                algorithm = box.s32();
            } else if (box.type() == CMVD && data == null) {
                data = box;
            }
        }
        if (algorithm == -1 || data == null) {
            throw new MediaException.Malformed("cmov has no " + (algorithm == -1 ? "dcom" : "cmvd") + " atom");
        }
        if (algorithm != ZLIB) {
            throw new MediaException.Unsupported("quicktime compressed movie",
                    "the movie is compressed with '" + FourCc.printable(algorithm) + "'");
        }
        var size = data.u32();
        if (size < 8 || size > Integer.MAX_VALUE - 16) {
            throw new MediaException.Malformed("cmvd declares an uncompressed movie of " + size + " bytes");
        }
        LimitChecks.checkAllocation(limits, size);
        var movie = new byte[(int) size];
        var inflater = new Inflater();
        try {
            inflater.setInput(data.data(), data.position(), data.remaining());
            var produced = 0;
            while (produced < movie.length) {
                var count = inflater.inflate(movie, produced, movie.length - produced);
                produced += count;
                if (count == 0 && (inflater.finished() || inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
            }
            if (produced < movie.length) {
                throw new MediaException.Malformed("the compressed movie holds " + produced + " of the " + size
                        + " bytes cmvd declares");
            }
        } catch (DataFormatException exception) {
            throw new MediaException.Malformed("the compressed movie is not a zlib stream: " + exception.getMessage(),
                    exception);
        } finally {
            inflater.end();
        }
        var top = BoxCursor.of(movie, CMVD);
        var moov = top.nextBox();
        if (moov.type() != MOOV) {
            throw new MediaException.Malformed("the compressed movie holds a '" + FourCc.printable(moov.type())
                    + "' atom, not a moov");
        }
        return movie;
    }

    /**
     * Returns the {@code cmov} atom a {@code moov} atom holds as its first child.
     *
     * @param moov the payload of the {@code moov} atom, not moved
     * @return the payload of the {@code cmov} atom, or {@code null} when the movie is not compressed
     * @throws MediaException.Malformed if the first child is damaged
     */
    public static BoxCursor find(BoxCursor moov) {
        var cursor = moov.duplicate();
        if (!cursor.hasBox()) {
            return null;
        }
        var first = cursor.nextBox();
        return first.type() == CMOV ? first : null;
    }
}
