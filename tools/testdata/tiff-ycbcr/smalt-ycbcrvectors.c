/*
 * Writes the YCbCr data-unit vectors of smalt-image's TiffYCbCrTest with libtiff (4.x; checked with 4.7.1): each
 * one twice, compressed with the horizontal predictor as libtiff codes it on rows of data units (each sample
 * predicted from the one SamplesPerPixel positions before it), and uncompressed without a predictor.
 *
 *   smalt-ycbcrvectors <directory>
 *
 * writes <name>.tif and <name>.raw.tif for
 *   ycbcr22-8-lzw     24x10, 8-bit, 2x2 subsampling, LZW, 4 rows per strip;
 *   ycbcr42-8-zip     24x10, 8-bit, 4x2, Deflate, 4 rows per strip;
 *   ycbcr21-8-lzw     24x10, 8-bit, 2x1, LZW, 3 rows per strip;
 *   ycbcr22-16-zip    24x10, 16-bit, 2x2, Deflate, 4 rows per strip;
 *   ycbcr44-16-lzw    24x12, 16-bit, 4x4, LZW, 8 rows per strip.
 *
 * The samples come from the 64-bit LCG x = x * 6364136223846793005 + 1442695040888963407 seeded with
 * 0x9E3779B97F4A7C15 (top byte or top 16 bits of each state), luma smoothed by a gradient across each row of
 * data units. The 24-pixel rows hold 36, 60 or 48 samples of data units, which libtiff's predictor requires to
 * be a multiple of SamplesPerPixel.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "tiffio.h"

static unsigned long long state = 0x9E3779B97F4A7C15ULL;

static unsigned int next_bits(int bits) {
    state = state * 6364136223846793005ULL + 1442695040888963407ULL;
    return (unsigned int) (state >> (64 - bits));
}

static void write_one(const char *path, int width, int height, int bits, int sx, int sy, int compression,
                      int predictor, int rowsPerStrip, const unsigned char *data, tmsize_t rowBytes) {
    TIFF *tif = TIFFOpen(path, "w");
    int unitRows = (height + sy - 1) / sy;
    int stripUnits = rowsPerStrip / sy;
    int strip;
    if (tif == NULL) {
        fprintf(stderr, "cannot write %s\n", path);
        exit(1);
    }
    TIFFSetField(tif, TIFFTAG_IMAGEWIDTH, width);
    TIFFSetField(tif, TIFFTAG_IMAGELENGTH, height);
    TIFFSetField(tif, TIFFTAG_BITSPERSAMPLE, bits);
    TIFFSetField(tif, TIFFTAG_SAMPLESPERPIXEL, 3);
    TIFFSetField(tif, TIFFTAG_PHOTOMETRIC, PHOTOMETRIC_YCBCR);
    TIFFSetField(tif, TIFFTAG_YCBCRSUBSAMPLING, sx, sy);
    TIFFSetField(tif, TIFFTAG_PLANARCONFIG, PLANARCONFIG_CONTIG);
    TIFFSetField(tif, TIFFTAG_COMPRESSION, compression);
    if (predictor != 1) {
        TIFFSetField(tif, TIFFTAG_PREDICTOR, predictor);
    }
    TIFFSetField(tif, TIFFTAG_ROWSPERSTRIP, rowsPerStrip);
    for (strip = 0; strip * stripUnits < unitRows; strip++) {
        int units = unitRows - strip * stripUnits < stripUnits ? unitRows - strip * stripUnits : stripUnits;
        /* the predictor works in place on a copy of the strip */
        tmsize_t size = rowBytes * units;
        unsigned char *copy = malloc(size);
        memcpy(copy, data + rowBytes * strip * stripUnits, size);
        if (TIFFWriteEncodedStrip(tif, strip, copy, size) < 0) {
            fprintf(stderr, "cannot write a strip of %s\n", path);
            exit(1);
        }
        free(copy);
    }
    TIFFClose(tif);
}

static void write_vector(const char *directory, const char *name, int width, int height, int bits, int sx, int sy,
                         int compression, int rowsPerStrip) {
    char path[4096];
    int units = (width + sx - 1) / sx;
    int unitSamples = sx * sy + 2;
    int unitRows = (height + sy - 1) / sy;
    tmsize_t rowBytes = (tmsize_t) units * unitSamples * bits / 8;
    unsigned char *data = malloc(rowBytes * unitRows);
    int row, unit, i;
    unsigned int max = (1u << bits) - 1;
    for (row = 0; row < unitRows; row++) {
        for (unit = 0; unit < units; unit++) {
            for (i = 0; i < unitSamples; i++) {
                unsigned int v = next_bits(bits);
                size_t at = (size_t) row * units * unitSamples + (size_t) unit * unitSamples + i;
                if (i < sx * sy) {
                    v = (unsigned int) (((unsigned long long) unit * max / units + v / 16) % (max + 1ull));
                }
                if (bits == 8) {
                    data[at] = (unsigned char) v;
                } else {
                    ((unsigned short *) data)[at] = (unsigned short) v;
                }
            }
        }
    }
    snprintf(path, sizeof path, "%s/%s.tif", directory, name);
    write_one(path, width, height, bits, sx, sy, compression, 2, rowsPerStrip, data, rowBytes);
    snprintf(path, sizeof path, "%s/%s.raw.tif", directory, name);
    write_one(path, width, height, bits, sx, sy, COMPRESSION_NONE, 1, rowsPerStrip, data, rowBytes);
    free(data);
}

int main(int argc, char **argv) {
    if (argc != 2) {
        fprintf(stderr, "usage: smalt-ycbcrvectors <directory>\n");
        return 2;
    }
    write_vector(argv[1], "ycbcr22-8-lzw", 24, 10, 8, 2, 2, COMPRESSION_LZW, 4);
    write_vector(argv[1], "ycbcr42-8-zip", 24, 10, 8, 4, 2, COMPRESSION_ADOBE_DEFLATE, 4);
    write_vector(argv[1], "ycbcr21-8-lzw", 24, 10, 8, 2, 1, COMPRESSION_LZW, 3);
    write_vector(argv[1], "ycbcr22-16-zip", 24, 10, 16, 2, 2, COMPRESSION_ADOBE_DEFLATE, 4);
    write_vector(argv[1], "ycbcr44-16-lzw", 24, 12, 16, 4, 4, COMPRESSION_LZW, 8);
    return 0;
}
