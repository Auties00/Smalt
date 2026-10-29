package com.github.auties00.smalt.internal.tags;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.edit.EditPlan;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.metadata.TagWriteOptions;
import com.github.auties00.smalt.spi.ContainerProvider;
import com.github.auties00.smalt.spi.TagRewrite;

import java.util.Objects;

/**
 * Reads and plans the tags of a file through the {@link ContainerProvider} of its container.
 *
 * <p>Instances are not thread-safe.
 */
final class ProvidedTagFile implements TagFile {
    /**
     * The provider of the container.
     */
    private final ContainerProvider provider;

    /**
     * The file.
     */
    private final MediaSource source;

    /**
     * The bounds on every metadata item.
     */
    private final MediaLimits limits;

    /**
     * Constructs the tag file of a source.
     *
     * @param provider the provider of the container, which edits its tags
     * @param source the file
     * @param limits the bounds on every metadata item
     */
    ProvidedTagFile(ContainerProvider provider, MediaSource source, MediaLimits limits) {
        this.provider = provider;
        this.source = source;
        this.limits = limits;
    }

    @Override
    public MediaTags read() {
        return Objects.requireNonNull(provider.readTags(source, limits), "the tags a provider reads");
    }

    /**
     * {@inheritDoc}
     *
     * @throws MediaException.Malformed if the provider plans to copy bytes past the end of the file
     */
    @Override
    public EditPlan plan(MediaTags tags, TagWriteOptions options) {
        var rewrite = Objects.requireNonNull(provider.planTags(source, tags, options), "the plan a provider makes");
        var size = source.size();
        var plan = EditPlan.builder();
        for (var piece : rewrite.pieces()) {
            switch (piece) {
                case TagRewrite.Bytes(var data) -> plan.bytes(data);
                case TagRewrite.Copy(var start, var length) -> {
                    if (size >= 0 && start > size - length) {
                        throw new MediaException.Malformed("the " + provider.name() + " tag edit copies bytes "
                                + start + "+" + length + " of a file of " + size + " bytes");
                    }
                    plan.copy(start, length);
                }
            }
        }
        return plan.build();
    }
}
