package com.github.auties00.smalt.image.jp2;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs OpenJPH's command-line tools ({@code ojph_compress}, {@code ojph_expand}) as black-box HTJ2K oracles. Tests
 * skip when a tool is not on {@code PATH}.
 */
final class OpenJph {
    private OpenJph() {
        throw new AssertionError();
    }

    /**
     * Codes a PGM or PPM image with {@code ojph_compress}.
     *
     * @param pnm the image
     * @param gray whether it is a PGM image
     * @param arguments the extra options, such as {@code -reversible true}
     * @return the codestream
     */
    static byte[] compress(byte[] pnm, boolean gray, String... arguments) {
        OpenJpeg.assume("ojph_compress");
        try {
            var input = OpenJpeg.temp(gray ? "in.pgm" : "in.ppm");
            Files.write(input, pnm);
            var output = OpenJpeg.temp("out.j2c");
            var command = new ArrayList<>(List.of("-i", input.toString(), "-o", output.toString()));
            command.addAll(List.of(arguments));
            if (OpenJpeg.run("ojph_compress", command) == null) {
                throw new IllegalStateException("ojph_compress failed");
            }
            var data = Files.readAllBytes(output);
            Files.delete(input);
            Files.delete(output);
            return data;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * Decodes a codestream of one or three 8-bit or 16-bit components with {@code ojph_expand}.
     *
     * @param codestream the codestream
     * @param components the number of components, 1 or 3
     * @return the planes, or {@code null} when the tool rejects the codestream
     */
    static Jp2Testing.Plane[] expand(byte[] codestream, int components) {
        OpenJpeg.assume("ojph_expand");
        try {
            var input = OpenJpeg.temp("in.j2c");
            Files.write(input, codestream);
            var output = OpenJpeg.temp(components == 1 ? "out.pgm" : "out.ppm");
            if (OpenJpeg.run("ojph_expand", List.of("-i", input.toString(), "-o", output.toString())) == null) {
                return null;
            }
            var planes = Jp2Testing.pnm(Files.readAllBytes(output));
            Files.delete(input);
            Files.delete(output);
            return planes;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
