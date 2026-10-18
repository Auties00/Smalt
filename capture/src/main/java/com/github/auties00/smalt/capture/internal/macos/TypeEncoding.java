package com.github.auties00.smalt.capture.internal.macos;

import java.util.Objects;

/**
 * Builds the Objective-C type encodings of the methods and blocks this backend implements.
 *
 * <p>The runtime stores a type encoding with every method added by {@code class_addMethod} and
 * reads one from every block descriptor that sets {@code BLOCK_HAS_SIGNATURE}; forwarding,
 * {@code NSInvocation} and bridging layers use them to marshal arguments. An encoding lists the
 * return type, the size of the argument frame, and every argument with its offset in that frame:
 * {@snippet :
 * // - (void)captureOutput:(AVCaptureOutput *)o didOutputSampleBuffer:(CMSampleBufferRef)b
 * //         fromConnection:(AVCaptureConnection *)c
 * "v40@0:8@16^{opaqueCMSampleBuffer=}24@32"
 * // void (^)(BOOL granted) on arm64
 * "v12@?0B8"
 * }
 * Integral arguments narrower than an {@code int} occupy four bytes of the frame, as Clang
 * encodes them.
 *
 * @implNote This implementation reproduces Clang's {@code getObjCEncodingForMethodDecl} and
 *           {@code getObjCEncodingForBlock} for the 64-bit Darwin targets: pointers, objects,
 *           selectors, classes, {@code long long} and {@code double} are eight bytes; integral
 *           types narrower than {@code int} are widened to four bytes.
 */
final class TypeEncoding {
    /**
     * The encoding of a {@code CMSampleBufferRef}, a pointer to an opaque structure.
     */
    static final String SAMPLE_BUFFER = "^{opaqueCMSampleBuffer=}";

    /**
     * The encoding of
     * {@code -captureOutput:didOutputSampleBuffer:fromConnection:} of
     * {@code AVCaptureVideoDataOutputSampleBufferDelegate}.
     */
    static final String CAPTURE_OUTPUT = method("v", "@", SAMPLE_BUFFER, "@");

    /**
     * The encoding of {@code -stream:didOutputSampleBuffer:ofType:} of {@code SCStreamOutput},
     * whose type is an {@code NSInteger}.
     */
    static final String STREAM_OUTPUT = method("v", "@", SAMPLE_BUFFER, "q");

    /**
     * The encoding of {@code -stream:didStopWithError:} of {@code SCStreamDelegate}.
     */
    static final String STREAM_STOPPED = method("v", "@", "@");

    /**
     * The encoding of a {@code void (^)(id, NSError *)} completion handler.
     */
    static final String OBJECT_ERROR_BLOCK = block("v", "@", "@");

    /**
     * The encoding of a {@code void (^)(NSError *)} completion handler.
     */
    static final String ERROR_BLOCK = block("v", "@");

    /**
     * Prevents instantiation.
     */
    private TypeEncoding() {
        throw new AssertionError();
    }

    /**
     * Returns the encoding of {@code BOOL} on an architecture.
     *
     * <p>{@code BOOL} is {@code bool} ({@code B}) on arm64 and {@code signed char} ({@code c}) on
     * x86_64, following {@code OBJC_BOOL_IS_BOOL} in {@code objc/objc.h}.
     *
     * @param arm64 whether the process runs on arm64
     * @return {@code "B"} on arm64, {@code "c"} otherwise
     */
    static String bool(boolean arm64) {
        return arm64 ? "B" : "c";
    }

    /**
     * Returns the encoding of a {@code void (^)(BOOL)} completion handler.
     *
     * @param arm64 whether the process runs on arm64
     * @return {@code "v12@?0B8"} on arm64, {@code "v12@?0c8"} on x86_64
     */
    static String boolBlock(boolean arm64) {
        return block("v", bool(arm64));
    }

    /**
     * Returns the encoding of an instance method.
     *
     * <p>The receiver ({@code @} at offset 0) and the selector ({@code :} at offset 8) are
     * implicit; {@code arguments} lists the explicit arguments only.
     *
     * @param result    the encoding of the return type
     * @param arguments the encodings of the explicit arguments, in order
     * @return the method type encoding
     * @throws NullPointerException     if an argument is {@code null}
     * @throws IllegalArgumentException if an encoding is not supported by {@link #size(String)}
     */
    static String method(String result, String... arguments) {
        Objects.requireNonNull(result, "result");
        var frame = 16;
        for (var argument : arguments) {
            frame += size(argument);
        }
        var encoding = new StringBuilder(result).append(frame).append("@0:8");
        var offset = 16;
        for (var argument : arguments) {
            encoding.append(argument).append(offset);
            offset += size(argument);
        }
        return encoding.toString();
    }

    /**
     * Returns the encoding of a block.
     *
     * <p>The block itself ({@code @?} at offset 0) is implicit; {@code parameters} lists the
     * block's own parameters only.
     *
     * @param result     the encoding of the return type
     * @param parameters the encodings of the parameters, in order
     * @return the block type encoding
     * @throws NullPointerException     if an argument is {@code null}
     * @throws IllegalArgumentException if an encoding is not supported by {@link #size(String)}
     */
    static String block(String result, String... parameters) {
        Objects.requireNonNull(result, "result");
        var frame = 8;
        for (var parameter : parameters) {
            frame += size(parameter);
        }
        var encoding = new StringBuilder(result).append(frame).append("@?0");
        var offset = 8;
        for (var parameter : parameters) {
            encoding.append(parameter).append(offset);
            offset += size(parameter);
        }
        return encoding.toString();
    }

    /**
     * Returns the number of bytes an argument of an encoded type occupies in the argument frame.
     *
     * @param type the encoding of the type
     * @return the size in bytes
     * @throws NullPointerException     if {@code type} is {@code null}
     * @throws IllegalArgumentException if {@code type} is empty or not a scalar, object,
     *                                  selector, class, block or pointer encoding
     */
    static int size(String type) {
        Objects.requireNonNull(type, "type");
        if (type.isEmpty()) {
            throw new IllegalArgumentException("empty type encoding");
        }
        return switch (type.charAt(0)) {
            case '@', '#', ':', '*', '^', 'q', 'Q', 'd' -> 8;
            case 'c', 'C', 'B', 's', 'S', 'i', 'I', 'f' -> 4;
            default -> throw new IllegalArgumentException("unsupported type encoding " + type);
        };
    }
}
