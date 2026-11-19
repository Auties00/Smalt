/*
 * smalt-pngdump: reads a PNG datastream with libpng and prints what libpng found, for the PNG
 * encoder tests (an independent reader of Smalt's output).
 *
 *   smalt-pngdump info <file>     prints one line per chunk libpng reports, then every libpng
 *                                 warning as "warning <text>"
 *   smalt-pngdump pixels <file>   writes the image to standard output as RGBA, 8 bits per sample
 *                                 for images of 8 bits or fewer and 16 bits (big-endian) for
 *                                 16-bit images, deinterlaced, with palettes and tRNS expanded
 *
 * Exits with 1 and a message on standard error when libpng reports an error.
 * Build: tools/testdata/png-oracle/build.sh (gcc and libpng 1.6.46 or later).
 */
#include <png.h>
#include <setjmp.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#ifdef _WIN32
#include <fcntl.h>
#include <io.h>
#endif

static char warnings[64][256];
static int warning_count;

static void on_error(png_structp png, png_const_charp message) {
    fprintf(stderr, "libpng error: %s\n", message);
    longjmp(png_jmpbuf(png), 1);
}

static void on_warning(png_structp png, png_const_charp message) {
    (void) png;
    if (warning_count < 64) {
        snprintf(warnings[warning_count++], sizeof warnings[0], "%s", message);
    }
}

int main(int argc, char **argv) {
    if (argc != 3 || (strcmp(argv[1], "info") != 0 && strcmp(argv[1], "pixels") != 0)) {
        fprintf(stderr, "usage: smalt-pngdump info|pixels <file>\n");
        return 2;
    }
    int pixels = strcmp(argv[1], "pixels") == 0;
    FILE *file = fopen(argv[2], "rb");
    if (file == NULL) {
        fprintf(stderr, "cannot open %s\n", argv[2]);
        return 2;
    }
    png_structp png = png_create_read_struct(PNG_LIBPNG_VER_STRING, NULL, on_error, on_warning);
    png_infop info = png_create_info_struct(png);
    png_bytep image = NULL;
    png_bytepp rows = NULL;
    if (setjmp(png_jmpbuf(png))) {
        png_destroy_read_struct(&png, &info, NULL);
        free(image);
        free(rows);
        fclose(file);
        return 1;
    }
    png_init_io(png, file);
    png_read_info(png, info);
    png_uint_32 width, height;
    int depth, color_type, interlace;
    png_get_IHDR(png, info, &width, &height, &depth, &color_type, &interlace, NULL, NULL);
    if (pixels) {
        png_set_expand(png);
        png_set_gray_to_rgb(png);
        if ((color_type & PNG_COLOR_MASK_ALPHA) == 0 && !png_get_valid(png, info, PNG_INFO_tRNS)) {
            png_set_filler(png, depth == 16 ? 0xFFFF : 0xFF, PNG_FILLER_AFTER);
        }
        png_set_interlace_handling(png);
        png_read_update_info(png, info);
        size_t row_bytes = png_get_rowbytes(png, info);
        image = malloc(row_bytes * height);
        rows = malloc(sizeof(png_bytep) * height);
        for (png_uint_32 y = 0; y < height; y++) {
            rows[y] = image + row_bytes * y;
        }
        png_read_image(png, rows);
        png_read_end(png, NULL);
#ifdef _WIN32
        _setmode(_fileno(stdout), _O_BINARY);
#endif
        fwrite(image, 1, row_bytes * height, stdout);
    } else {
        png_set_interlace_handling(png);
        png_read_update_info(png, info);
        rows = malloc(sizeof(png_bytep) * height);
        size_t row_bytes = png_get_rowbytes(png, info);
        image = malloc(row_bytes * height + 1);
        for (png_uint_32 y = 0; y < height; y++) {
            rows[y] = image + row_bytes * y;
        }
        png_read_image(png, rows);
        png_read_end(png, info);
        printf("IHDR %u %u %d %d %d\n", (unsigned) width, (unsigned) height, depth, color_type, interlace);
        png_color_8p bits;
        if (png_get_sBIT(png, info, &bits)) {
            printf("sBIT %d %d %d %d %d\n", bits->red, bits->green, bits->blue, bits->gray, bits->alpha);
        }
        png_bytep alpha;
        int alpha_count;
        png_color_16p key;
        if (png_get_tRNS(png, info, &alpha, &alpha_count, &key)) {
            if (color_type == PNG_COLOR_TYPE_PALETTE) {
                printf("tRNS palette %d", alpha_count);
                for (int i = 0; i < alpha_count; i++) {
                    printf(" %d", alpha[i]);
                }
                printf("\n");
            } else if (color_type == PNG_COLOR_TYPE_GRAY) {
                printf("tRNS gray %d\n", key->gray);
            } else {
                printf("tRNS rgb %d %d %d\n", key->red, key->green, key->blue);
            }
        }
        png_colorp palette;
        int palette_count;
        if (png_get_PLTE(png, info, &palette, &palette_count)) {
            printf("PLTE %d\n", palette_count);
        }
        png_fixed_point gamma;
        if (png_get_valid(png, info, PNG_INFO_gAMA) && png_get_gAMA_fixed(png, info, &gamma)) {
            printf("gAMA %d\n", (int) gamma);
        }
        png_fixed_point c[8];
        if (png_get_valid(png, info, PNG_INFO_cHRM)
                && png_get_cHRM_fixed(png, info, &c[0], &c[1], &c[2], &c[3], &c[4], &c[5], &c[6], &c[7])) {
            printf("cHRM %d %d %d %d %d %d %d %d\n", (int) c[0], (int) c[1], (int) c[2], (int) c[3], (int) c[4],
                    (int) c[5], (int) c[6], (int) c[7]);
        }
        int intent;
        if (png_get_sRGB(png, info, &intent)) {
            printf("sRGB %d\n", intent);
        }
        png_charp name;
        int compression;
        png_bytep profile;
        png_uint_32 profile_length;
        if (png_get_iCCP(png, info, &name, &compression, &profile, &profile_length)) {
            printf("iCCP %s %u\n", name, (unsigned) profile_length);
        }
        png_uint_32 res_x, res_y;
        int unit;
        if (png_get_pHYs(png, info, &res_x, &res_y, &unit)) {
            printf("pHYs %u %u %d\n", (unsigned) res_x, (unsigned) res_y, unit);
        }
        png_byte primaries, transfer, matrix, range;
        if (png_get_cICP(png, info, &primaries, &transfer, &matrix, &range)) {
            printf("cICP %d %d %d %d\n", primaries, transfer, matrix, range);
        }
        png_fixed_point m[8];
        png_uint_32 max_luminance, min_luminance;
        if (png_get_mDCV_fixed(png, info, &m[0], &m[1], &m[2], &m[3], &m[4], &m[5], &m[6], &m[7], &max_luminance,
                &min_luminance)) {
            printf("mDCV %d %d %d %d %d %d %d %d %u %u\n", (int) m[0], (int) m[1], (int) m[2], (int) m[3],
                    (int) m[4], (int) m[5], (int) m[6], (int) m[7], (unsigned) max_luminance, (unsigned) min_luminance);
        }
        png_uint_32 max_cll, max_fall;
        if (png_get_cLLI_fixed(png, info, &max_cll, &max_fall)) {
            printf("cLLI %u %u\n", (unsigned) max_cll, (unsigned) max_fall);
        }
        for (int i = 0; i < warning_count; i++) {
            printf("warning %s\n", warnings[i]);
        }
    }
    png_destroy_read_struct(&png, &info, NULL);
    free(image);
    free(rows);
    fclose(file);
    return 0;
}
