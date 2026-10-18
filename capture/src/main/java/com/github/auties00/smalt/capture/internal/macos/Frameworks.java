package com.github.auties00.smalt.capture.internal.macos;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.util.Optional;

import static java.lang.foreign.ValueLayout.ADDRESS;

/**
 * Loads the macOS system libraries and frameworks and resolves their symbols.
 *
 * <p>Every library is loaded by the absolute install name it has in the dyld shared cache. Since
 * macOS 11 system libraries exist only inside that cache, not as files, so they are opened with
 * {@link SymbolLookup#libraryLookup(String, Arena)}, which passes the name to {@code dlopen}
 * unchanged. Libraries are loaded into the global arena and never unloaded: the Objective-C
 * classes and upcall stubs registered with them live as long as the process.
 */
@SuppressWarnings("restricted")
final class Frameworks {
    /**
     * The Objective-C runtime.
     */
    static final String OBJC = "/usr/lib/libobjc.A.dylib";

    /**
     * The system umbrella library, which re-exports libdispatch and libsystem_blocks.
     */
    static final String SYSTEM = "/usr/lib/libSystem.B.dylib";

    /**
     * CoreFoundation.
     */
    static final String CORE_FOUNDATION = "/System/Library/Frameworks/CoreFoundation.framework/Versions/A/CoreFoundation";

    /**
     * Foundation, home of {@code NSNumber} and {@code NSBundle}.
     */
    static final String FOUNDATION = "/System/Library/Frameworks/Foundation.framework/Versions/C/Foundation";

    /**
     * CoreMedia.
     */
    static final String CORE_MEDIA = "/System/Library/Frameworks/CoreMedia.framework/Versions/A/CoreMedia";

    /**
     * CoreVideo.
     */
    static final String CORE_VIDEO = "/System/Library/Frameworks/CoreVideo.framework/Versions/A/CoreVideo";

    /**
     * CoreGraphics.
     */
    static final String CORE_GRAPHICS = "/System/Library/Frameworks/CoreGraphics.framework/Versions/A/CoreGraphics";

    /**
     * AVFoundation, which re-exports the capture classes of the private AVFCapture framework.
     */
    static final String AV_FOUNDATION = "/System/Library/Frameworks/AVFoundation.framework/Versions/A/AVFoundation";

    /**
     * ScreenCaptureKit, present from macOS 12.3.
     */
    static final String SCREEN_CAPTURE_KIT = "/System/Library/Frameworks/ScreenCaptureKit.framework/Versions/A/ScreenCaptureKit";

    /**
     * Prevents instantiation.
     */
    private Frameworks() {
        throw new AssertionError();
    }

    /**
     * Loads a library.
     *
     * @param path the install name
     * @return the lookup of the library's symbols
     * @throws IllegalArgumentException if the library cannot be loaded
     * @throws IllegalCallerException if native access is not enabled for this module
     */
    static SymbolLookup load(String path) {
        return SymbolLookup.libraryLookup(path, Arena.global());
    }

    /**
     * Resolves a symbol that must exist.
     *
     * @param name the symbol name, without the leading underscore
     * @return the symbol's address
     * @throws UnsatisfiedLinkError if the library does not export the symbol
     */
    static MemorySegment symbol(SymbolLookup library, String name) {
        return library.find(name).orElseThrow(() -> new UnsatisfiedLinkError("missing symbol " + name));
    }

    /**
     * Reads the value of an exported pointer constant, such as a {@code CFStringRef} key.
     *
     * @param name the constant's name
     * @return the pointer the constant holds, or empty when the library does not export it
     */
    static Optional<MemorySegment> constant(SymbolLookup library, String name) {
        return library.find(name).map(address -> address.reinterpret(ADDRESS.byteSize()).get(ADDRESS, 0));
    }

    /**
     * Reads the value of an exported pointer constant that must exist.
     *
     * @param name the constant's name
     * @return the pointer the constant holds
     * @throws UnsatisfiedLinkError if the library does not export the constant
     */
    static MemorySegment requiredConstant(SymbolLookup library, String name) {
        return constant(library, name).orElseThrow(() -> new UnsatisfiedLinkError("missing constant " + name));
    }
}
