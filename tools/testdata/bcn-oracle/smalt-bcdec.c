/*
 * Decodes BCn texture blocks with bcdec (iOrange/bcdec, MIT), the oracle of Smalt's DDS decoder tests.
 *
 *   smalt-bcdec <bc1|bc2|bc3|bc4|bc4s|bc5|bc5s|bc6h|bc6hs|bc7> <width> <height> < blocks > texels
 *
 * The input is the blocks of a width by height surface in row-major block order; the output is the surface's texels
 * in row-major order: RGBA bytes for BC1, BC2, BC3 and BC7, one byte (two's complement when signed) for BC4, two for
 * BC5, and three little-endian IEEE 754 binary16 values for BC6H. BC4 and BC5 use bcdec's precise interpolation.
 * The signed BC4 and BC5 decoders read plain char as signed, so the program is built with -fsigned-char.
 */
#define BCDEC_IMPLEMENTATION
#define BCDEC_BC4BC5_PRECISE
#include "bcdec.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#ifdef _WIN32
#include <fcntl.h>
#include <io.h>
#endif

int main(int argc, char **argv) {
    if (argc != 4) {
        fprintf(stderr, "usage: smalt-bcdec <format> <width> <height>\n");
        return 2;
    }
#ifdef _WIN32
    _setmode(_fileno(stdin), _O_BINARY);
    _setmode(_fileno(stdout), _O_BINARY);
#endif
    const char *format = argv[1];
    int width = atoi(argv[2]);
    int height = atoi(argv[3]);
    int blockBytes = (!strcmp(format, "bc1") || !strncmp(format, "bc4", 3)) ? 8 : 16;
    int texelBytes;
    if (!strncmp(format, "bc4", 3)) {
        texelBytes = 1;
    } else if (!strncmp(format, "bc5", 3)) {
        texelBytes = 2;
    } else if (!strncmp(format, "bc6h", 4)) {
        texelBytes = 6;
    } else {
        texelBytes = 4;
    }
    int columns = (width + 3) / 4;
    int rows = (height + 3) / 4;
    int paddedWidth = columns * 4;
    size_t surface = (size_t) paddedWidth * rows * 4 * texelBytes;
    unsigned char *texels = calloc(surface, 1);
    unsigned char block[16];
    for (int by = 0; by < rows; by++) {
        for (int bx = 0; bx < columns; bx++) {
            if (fread(block, 1, blockBytes, stdin) != (size_t) blockBytes) {
                fprintf(stderr, "truncated input\n");
                return 1;
            }
            unsigned char *out = texels + ((size_t) by * 4 * paddedWidth + bx * 4) * texelBytes;
            int pitch = paddedWidth * texelBytes;
            if (!strcmp(format, "bc1")) {
                bcdec_bc1(block, out, pitch);
            } else if (!strcmp(format, "bc2")) {
                bcdec_bc2(block, out, pitch);
            } else if (!strcmp(format, "bc3")) {
                bcdec_bc3(block, out, pitch);
            } else if (!strcmp(format, "bc4")) {
                bcdec_bc4(block, out, pitch, 0);
            } else if (!strcmp(format, "bc4s")) {
                bcdec_bc4(block, out, pitch, 1);
            } else if (!strcmp(format, "bc5")) {
                bcdec_bc5(block, out, pitch, 0);
            } else if (!strcmp(format, "bc5s")) {
                bcdec_bc5(block, out, pitch, 1);
            } else if (!strcmp(format, "bc6h")) {
                bcdec_bc6h_half(block, out, pitch / 2, 0);
            } else if (!strcmp(format, "bc6hs")) {
                bcdec_bc6h_half(block, out, pitch / 2, 1);
            } else if (!strcmp(format, "bc7")) {
                bcdec_bc7(block, out, pitch);
            } else {
                fprintf(stderr, "unknown format %s\n", format);
                return 2;
            }
        }
    }
    for (int y = 0; y < height; y++) {
        fwrite(texels + (size_t) y * paddedWidth * texelBytes, 1, (size_t) width * texelBytes, stdout);
    }
    free(texels);
    return 0;
}
