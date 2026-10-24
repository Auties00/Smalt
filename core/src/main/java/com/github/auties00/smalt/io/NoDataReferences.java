package com.github.auties00.smalt.io;

import java.util.Optional;

/**
 * The resolver of {@link DataReferenceResolver#none()}, which resolves no reference.
 */
enum NoDataReferences implements DataReferenceResolver {
    /**
     * The only instance.
     */
    INSTANCE;

    @Override
    public Optional<MediaSource> resolve(DataReference reference) {
        return Optional.empty();
    }

    @Override
    public String toString() {
        return "DataReferenceResolver.none()";
    }
}
