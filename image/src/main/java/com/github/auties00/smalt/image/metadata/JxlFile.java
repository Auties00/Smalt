package com.github.auties00.smalt.image.metadata;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.jxl.internal.JxlCodestream;
import com.github.auties00.smalt.internal.edit.EditPlan;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.XmpPacket;

import java.util.List;
import java.util.Map;

/**
 * Reads the metadata of a JPEG XL file: the {@code Exif} and {@code xml } boxes of its container
 * (also when Brotli-compressed in {@code brob} boxes) and the ICC profile of its codestream.
 *
 * <p>Edits are not supported: an edit that changes anything raises
 * {@link MediaException.Unsupported}, and one that keeps everything copies the file.
 */
final class JxlFile implements ImageFile {
    /**
     * The source.
     */
    private final MediaSource source;

    /**
     * The parsed codestream headers and container.
     */
    private final JxlCodestream codestream;

    /**
     * Reads the file's container and image header.
     *
     * @param source the source
     * @param limits the resource bounds
     * @throws MediaException.Malformed     if the container or the header is corrupt
     * @throws MediaException.LimitExceeded if the file exceeds the byte limit
     */
    JxlFile(MediaSource source, MediaLimits limits) {
        this.source = source;
        var size = source.size();
        LimitChecks.checkAllocation(limits, size);
        if (size < 0 || size > Integer.MAX_VALUE - 16) {
            throw new MediaException.LimitExceeded("JPEG XL file of " + size + " bytes");
        }
        var data = new byte[(int) size];
        source.readFully(0, data, 0, data.length);
        this.codestream = JxlCodestream.open(data, limits);
    }

    @Override
    public ImageMetadata metadata() {
        var container = codestream.container();
        XmpPacket xmp = null;
        if (container.xmp() != null) {
            try {
                xmp = XmpPacket.parse(container.xmp());
            } catch (MediaException.Malformed ignored) {
                // a packet that does not parse is left out
            }
        }
        var icc = codestream.icc();
        return new ImageMetadata(ImageFormat.JXL, container.exif(), xmp, null, icc != null ? icc.clone() : null,
                List.of(), Map.of());
    }

    @Override
    public EditPlan plan(Resolved target) {
        if (!target.keepsAll()) {
            // TODO: rewrite the Exif and xml boxes of a container (wrapping a bare codestream in one).
            throw new MediaException.Unsupported("jxl", "the metadata of jxl files cannot be edited");
        }
        return EditPlan.builder().copy(0, source.size()).build();
    }
}
