/*
 * Writes a GIF of one image from raw color indices with giflib's encoder (EGif), the oracle of smalt-image's
 * GifLzwEncoderTest. giflib is driven through its public API only.
 *
 *     smalt-gifenc <indices file> <width> <height> <bits per pixel> <output gif>
 *
 * The indices file holds width * height bytes, each below 2^bits. The GIF has a global color map of 2^bits
 * gray entries, no extensions and one image without a local color map, so the LZW data starts at byte
 * 13 + 3 * 2^bits + 10.
 */
#include <stdio.h>
#include <stdlib.h>

#include "gif_lib.h"

static int fail(const char *what, int error) {
    fprintf(stderr, "smalt-gifenc: %s: %s\n", what, GifErrorString(error));
    return 1;
}

int main(int argc, char **argv) {
    if (argc != 6) {
        fprintf(stderr, "usage: smalt-gifenc <indices file> <width> <height> <bits per pixel> <output gif>\n");
        return 2;
    }
    int width = atoi(argv[2]);
    int height = atoi(argv[3]);
    int bits = atoi(argv[4]);
    if (width <= 0 || height <= 0 || bits < 1 || bits > 8) {
        fprintf(stderr, "smalt-gifenc: invalid size or depth\n");
        return 2;
    }
    size_t count = (size_t) width * (size_t) height;
    GifPixelType *indices = malloc(count);
    FILE *input = fopen(argv[1], "rb");
    if (indices == NULL || input == NULL || fread(indices, 1, count, input) != count) {
        fprintf(stderr, "smalt-gifenc: cannot read %zu indices from %s\n", count, argv[1]);
        return 1;
    }
    fclose(input);
    int colors = 1 << bits;
    ColorMapObject *map = GifMakeMapObject(colors, NULL);
    if (map == NULL) {
        fprintf(stderr, "smalt-gifenc: no color map\n");
        return 1;
    }
    for (int i = 0; i < colors; i++) {
        GifByteType level = (GifByteType) (i * 255 / (colors - 1));
        map->Colors[i].Red = level;
        map->Colors[i].Green = level;
        map->Colors[i].Blue = level;
    }
    int error = 0;
    GifFileType *gif = EGifOpenFileName(argv[5], false, &error);
    if (gif == NULL) {
        return fail("open", error);
    }
    if (EGifPutScreenDesc(gif, width, height, bits, 0, map) == GIF_ERROR) {
        return fail("screen descriptor", gif->Error);
    }
    if (EGifPutImageDesc(gif, 0, 0, width, height, false, NULL) == GIF_ERROR) {
        return fail("image descriptor", gif->Error);
    }
    for (int y = 0; y < height; y++) {
        if (EGifPutLine(gif, indices + (size_t) y * (size_t) width, width) == GIF_ERROR) {
            return fail("line", gif->Error);
        }
    }
    if (EGifCloseFile(gif, &error) == GIF_ERROR) {
        return fail("close", error);
    }
    GifFreeMapObject(map);
    free(indices);
    return 0;
}
