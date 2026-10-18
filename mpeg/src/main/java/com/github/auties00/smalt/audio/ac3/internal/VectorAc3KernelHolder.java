package com.github.auties00.smalt.audio.ac3.internal;

/**
 * Creates the Vector API kernel, isolating the only reference to {@link VectorAc3Kernel} so that
 * classes linking against the kernels load without the incubating module.
 */
final class VectorAc3KernelHolder {
    /**
     * Prevents instantiation.
     */
    private VectorAc3KernelHolder() {
        throw new AssertionError();
    }

    /**
     * Creates the Vector API kernel.
     *
     * @return a new kernel
     */
    static Ac3Kernel create() {
        return new VectorAc3Kernel();
    }
}
