package com.github.auties00.smalt.image.metadata;

import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.internal.edit.EditPlan;
import com.github.auties00.smalt.io.MediaSource;

import java.util.List;
import java.util.Map;

/**
 * Describes a BMP file, which carries no metadata this editor reads or writes.
 */
final class BmpFile implements ImageFile {
    /**
     * The source.
     */
    private final MediaSource source;

    /**
     * Creates the description.
     *
     * @param source the source
     */
    BmpFile(MediaSource source) {
        this.source = source;
    }

    @Override
    public ImageMetadata metadata() {
        return new ImageMetadata(ImageFormat.BMP, null, null, null, null, List.of(), Map.of());
    }

    @Override
    public EditPlan plan(Resolved target) {
        if (target.replacesAny()) {
            throw ImageFiles.unsupported(ImageFormat.BMP, "metadata");
        }
        return EditPlan.builder().copy(0, source.size()).build();
    }
}
