// Writes the tables with which GoPro's cineform-sdk (Apache License 2.0 or MIT, https://github.com/gopro/cineform-sdk)
// linearizes the photosites of its 16-bit Bayer output, one per encode curve, as Codec/decoder.c builds them with the
// curve functions of Common/AVIExtendedHeader.h: 16384 little-endian 16-bit values per table, in a file named by the
// curve's ECRV value in hexadecimal. The digests of these files are pinned by the CineForm tests of smalt-video
// (video/cfhd/curves.sha256), so the tables need not be committed.
// Build: gcc -O1 -w -I<cineform-sdk>/Common -o curve-tables curve-tables.c -lm
// Usage: curve-tables <output directory>; then (cd <output directory> && sha256sum -- *) > curves.sha256
#include <stdio.h>
#include <stdint.h>
#include <stdbool.h>
#include <math.h>
#include "AVIExtendedHeader.h"

static int table(uint32_t encode_curve, const char *dir) {
    int encode_curve_type = encode_curve >> 16;
    float encode_curvebase;
    if (encode_curve_type) {
        if (encode_curve_type & CURVE_TYPE_EXTENDED)
            encode_curvebase = (float) (encode_curve & 0xffff);
        else
            encode_curvebase = (float) ((encode_curve >> 8) & 0xff) / (float) (encode_curve & 0xff);
    } else {
        encode_curve_type = CURVE_TYPE_LOG;
        encode_curvebase = 90.0;
    }
    char path[1024];
    snprintf(path, sizeof path, "%s/%08x.bin", dir, (unsigned) encode_curve);
    FILE *out = fopen(path, "wb");
    if (!out) return 1;
    for (int j = 0; j < 16384; j++) {
        int val;
        switch (encode_curve_type & CURVE_TYPE_MASK) {
        case CURVE_TYPE_LOG:
            val = (int) (CURVE_LOG2LIN((float) j / 16384.0f, (float) encode_curvebase) * 65535.0f);
            break;
        case CURVE_TYPE_GAMMA:
            val = (int) (CURVE_GAM2LIN((float) j / 16384.0f, (float) encode_curvebase) * 65535.0f);
            break;
        case CURVE_TYPE_CINEON:
            val = (int) (CURVE_CINEON2LIN((float) j / 16384.0f, (float) encode_curvebase) * 65535.0f);
            break;
        case CURVE_TYPE_CINE985:
            val = (int) (CURVE_CINE9852LIN((float) j / 16384.0f, (float) encode_curvebase) * 65535.0f);
            break;
        case CURVE_TYPE_PARA:
            val = (int) (CURVE_PARA2LIN((float) j / 16384.0f, (int) ((encode_curve >> 8) & 0xff),
                                        (int) (encode_curve & 0xff)) * 65535.0f);
            break;
        case CURVE_TYPE_CSTYLE:
            val = (int) (CURVE_CSTYLE2LIN((float) j / 16384.0f, (int) ((encode_curve >> 8) & 0xff)) * 65535.0f);
            break;
        case CURVE_TYPE_SLOG:
            val = (int) (CURVE_SLOG2LIN((float) j / 16384.0f) * 65535.0f);
            break;
        case CURVE_TYPE_LOGC:
            val = (int) (CURVE_LOGC2LIN((float) j / 16384.0f) * 65535.0f);
            break;
        default:
            val = j;
            break;
        }
        if (val < 0) val = 0;
        if (val > 65535) val = 65535;
        fputc(val & 0xff, out);
        fputc(val >> 8, out);
    }
    fclose(out);
    return 0;
}

int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: curve-tables <output directory>\n");
        return 2;
    }
    // the default curve, the curves CFHDTypes.h names, and a few more bases of the log and gamma types
    static const uint32_t curves[] = {
        0, CURVE_TYPE(1, 90, 1), CURVE_TYPE(2, 22, 10), CURVE_TYPE(5, 17, 10), CURVE_TYPE(7, 17, 10),
        CURVE_TYPE(5, 1, 1), CURVE_TYPE(6, 202, 4), CURVE_TYPE(8, 1, 1), CURVE_TYPE(9, 1, 1), CURVE_TYPE(10, 1, 1),
        CURVE_TYPE(1, 113, 1), CURVE_TYPE_EXT(1, 30), CURVE_TYPE_EXT(1, 70), CURVE_TYPE_EXT(1, 170),
        CURVE_TYPE_EXT(1, 400), CURVE_TYPE_EXT(1, 900), CURVE_TYPE(1, 250, 3), CURVE_TYPE(2, 18, 10),
        CURVE_TYPE(2, 26, 10), CURVE_TYPE(1, 90, 1) | (CURVE_TYPE_NEGATIVE << 16),
    };
    for (size_t i = 0; i < sizeof curves / sizeof curves[0]; i++) {
        if (table(curves[i], argv[1])) return 1;
    }
    return 0;
}
