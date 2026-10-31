// Writes the CineForm test vectors of smalt-video with GoPro's cineform-sdk, built from source, as a black-box
// encoder and reference decoder (see make-vectors.sh). Each vector holds four frames of a synthetic pattern with a
// moving square, encoded by CFHD_EncodeSample, each sample followed by CFHD_DecodeSample's full-resolution decode
// (the second of two decodes of it):
//   "CFHDVEC1", then per sample: u32le sample size, the sample, u32le decode size, the decode
// A sequence header sample has no decode (size 0).
// The decode is YU64 (16-bit little-endian Y0 Cr Y1 Cb) for 4:2:2, RG48 (16-bit little-endian R G B) for RGB 4:4:4
// and B64A (16-bit little-endian A R G B, as the SDK reads and writes it) for RGBA 4:4:4:4 (the SDK leaves an RG64
// output black).
// Usage: make-vectors <output directory>
#include <cstdio>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>
#include "CFHDEncoder.h"
#include "CFHDDecoder.h"

static int WIDTH = 128;
static int HEIGHT = 64;
static const int FRAMES = 4;

static void put16le(uint8_t *at, int value) {
    at[0] = (uint8_t) value;
    at[1] = (uint8_t) (value >> 8);
}

// Returns a 16-bit sample of channel c at (x, y) of frame f: gradients, a checker and a square that moves.
static int pattern(int c, int x, int y, int f) {
    int value = (x * 512 + y * 256 * (c + 1) + c * 9000) & 0xFFFF;
    if (((x >> 3) ^ (y >> 3)) & 1) value = (value * 3 / 4) & 0xFFFF;
    int sx = 10 + f * 9, sy = 8 + f * 5;
    if (x >= sx && x < sx + 24 && y >= sy && y < sy + 20) value = c == 0 ? 60000 : 12000 + c * 15000;
    if (c == 3) value = (x * 600 + f * 4000) & 0xFFFF;
    return value;
}

static std::vector<uint8_t> source(CFHD_PixelFormat format, int f, int *pitch) {
    std::vector<uint8_t> frame;
    if (format == CFHD_PIXEL_FORMAT_YU64) {
        *pitch = WIDTH * 4;
        frame.resize((size_t) *pitch * HEIGHT);
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x += 2) {
                uint8_t *at = &frame[(size_t) y * *pitch + x * 4];
                int luma0 = 4096 + pattern(0, x, y, f) * 14 / 16;
                int luma1 = 4096 + pattern(0, x + 1, y, f) * 14 / 16;
                put16le(at, luma0);
                put16le(at + 2, 4096 + pattern(1, x, y, f) * 14 / 16);
                put16le(at + 4, luma1);
                put16le(at + 6, 4096 + pattern(2, x, y, f) * 14 / 16);
            }
        }
    } else if (format == CFHD_PIXEL_FORMAT_RG48) {
        *pitch = WIDTH * 6;
        frame.resize((size_t) *pitch * HEIGHT);
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                for (int c = 0; c < 3; c++) put16le(&frame[(size_t) y * *pitch + x * 6 + c * 2], pattern(c, x, y, f));
            }
        }
    } else {
        *pitch = WIDTH * 8;
        frame.resize((size_t) *pitch * HEIGHT);
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                uint8_t *at = &frame[(size_t) y * *pitch + x * 8];
                put16le(at, pattern(3, x, y, f));
                for (int c = 0; c < 3; c++) put16le(at + 2 + c * 2, pattern(c, x, y, f));
            }
        }
    }
    return frame;
}

static void put32(FILE *out, uint32_t value) {
    uint8_t bytes[4] = {(uint8_t) value, (uint8_t) (value >> 8), (uint8_t) (value >> 16), (uint8_t) (value >> 24)};
    fwrite(bytes, 1, 4, out);
}

static int make(const std::string &path, CFHD_PixelFormat input, CFHD_EncodedFormat encoded, int flags,
                CFHD_EncodingQuality quality, CFHD_PixelFormat output, int outputBytes) {
    CFHD_EncoderRef encoder;
    CFHD_DecoderRef decoder;
    if (CFHD_OpenEncoder(&encoder, NULL) || CFHD_OpenDecoder(&decoder, NULL)) return 1;
    if (CFHD_PrepareToEncode(encoder, WIDTH, HEIGHT, input, encoded, (CFHD_EncodingFlags) flags, quality)) return 2;
    FILE *out = fopen(path.c_str(), "wb");
    if (!out) return 3;
    fwrite("CFHDVEC1", 1, 8, out);
    bool prepared = false;
    std::vector<uint8_t> decoded((size_t) WIDTH * HEIGHT * outputBytes);
    for (int f = 0; f < FRAMES; f++) {
        int pitch;
        auto frame = source(input, f, &pitch);
        if (CFHD_EncodeSample(encoder, frame.data(), pitch)) return 4;
        void *sample;
        size_t size;
        if (CFHD_GetSampleData(encoder, &sample, &size)) return 5;
        // a group of frames starts with a sequence header, which holds too little to prepare the decoder
        bool header = size >= 8 && ((uint8_t *) sample)[0] == 0 && ((uint8_t *) sample)[1] == 1
                && ((uint8_t *) sample)[2] == 0 && ((uint8_t *) sample)[3] == 7;
        if (header) {
            put32(out, (uint32_t) size);
            fwrite(sample, 1, size, out);
            put32(out, 0);
            continue;
        }
        if (!prepared) {
            int width, height;
            CFHD_PixelFormat actual;
            if (CFHD_PrepareToDecode(decoder, WIDTH, HEIGHT, output, CFHD_DECODED_RESOLUTION_FULL,
                                     CFHD_DECODING_FLAGS_NONE, sample, size, &width, &height, &actual)) return 6;
            if (actual != output || width != WIDTH || height != HEIGHT) return 7;
            prepared = true;
        }
        // the SDK's first decode of a group sample by a decoder that has not decoded that group returns before
        // the frame is reconstructed (it leaves the output buffer as it was), so every sample is decoded twice and
        // the second decode is kept
        for (int pass = 0; pass < 2; pass++) {
            if (CFHD_DecodeSample(decoder, sample, size, decoded.data(), WIDTH * outputBytes)) return 8;
        }
        put32(out, (uint32_t) size);
        fwrite(sample, 1, size, out);
        put32(out, (uint32_t) decoded.size());
        fwrite(decoded.data(), 1, decoded.size(), out);
    }
    fclose(out);
    CFHD_CloseEncoder(encoder);
    CFHD_CloseDecoder(decoder);
    return 0;
}

int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: make-vectors <output directory>\n");
        return 2;
    }
    std::string dir = argv[1];
    struct Case {
        const char *name;
        CFHD_PixelFormat input;
        CFHD_EncodedFormat encoded;
        int flags;
        CFHD_EncodingQuality quality;
        CFHD_PixelFormat output;
        int bytes;
        int width;
        int height;
    } cases[] = {
        {"yuv422-intra-filmscan1.cfhd", CFHD_PIXEL_FORMAT_YU64, CFHD_ENCODED_FORMAT_YUV_422, 0,
         CFHD_ENCODING_QUALITY_FILMSCAN1, CFHD_PIXEL_FORMAT_YU64, 4, 128, 64},
        {"yuv422-gop2-filmscan1.cfhd", CFHD_PIXEL_FORMAT_YU64, CFHD_ENCODED_FORMAT_YUV_422,
         CFHD_ENCODING_FLAGS_YUV_2FRAME_GOP, CFHD_ENCODING_QUALITY_FILMSCAN1, CFHD_PIXEL_FORMAT_YU64, 4, 128, 64},
        {"yuv422-gop2-low.cfhd", CFHD_PIXEL_FORMAT_YU64, CFHD_ENCODED_FORMAT_YUV_422,
         CFHD_ENCODING_FLAGS_YUV_2FRAME_GOP, CFHD_ENCODING_QUALITY_LOW, CFHD_PIXEL_FORMAT_YU64, 4, 128, 64},
        {"yuv422-interlaced-medium.cfhd", CFHD_PIXEL_FORMAT_YU64, CFHD_ENCODED_FORMAT_YUV_422,
         CFHD_ENCODING_FLAGS_YUV_INTERLACED, CFHD_ENCODING_QUALITY_MEDIUM, CFHD_PIXEL_FORMAT_YU64, 4, 128, 64},
        {"rgb444-filmscan1.cfhd", CFHD_PIXEL_FORMAT_RG48, CFHD_ENCODED_FORMAT_RGB_444, 0,
         CFHD_ENCODING_QUALITY_FILMSCAN1, CFHD_PIXEL_FORMAT_RG48, 6, 128, 64},
        {"rgba4444-high.cfhd", CFHD_PIXEL_FORMAT_B64A, CFHD_ENCODED_FORMAT_RGBA_4444, 0,
         CFHD_ENCODING_QUALITY_HIGH, CFHD_PIXEL_FORMAT_B64A, 8, 128, 64},
        // band rows that are not a multiple of 8 coefficients wide, and a coded height above the display height
        {"yuv422-intra-176x90-medium.cfhd", CFHD_PIXEL_FORMAT_YU64, CFHD_ENCODED_FORMAT_YUV_422, 0,
         CFHD_ENCODING_QUALITY_MEDIUM, CFHD_PIXEL_FORMAT_YU64, 4, 176, 90},
        {"yuv422-gop2-176x90-high.cfhd", CFHD_PIXEL_FORMAT_YU64, CFHD_ENCODED_FORMAT_YUV_422,
         CFHD_ENCODING_FLAGS_YUV_2FRAME_GOP, CFHD_ENCODING_QUALITY_HIGH, CFHD_PIXEL_FORMAT_YU64, 4, 176, 90},
        {"rgb444-176x90-filmscan1.cfhd", CFHD_PIXEL_FORMAT_RG48, CFHD_ENCODED_FORMAT_RGB_444, 0,
         CFHD_ENCODING_QUALITY_FILMSCAN1, CFHD_PIXEL_FORMAT_RG48, 6, 176, 90},
    };
    for (auto &c : cases) {
        WIDTH = c.width;
        HEIGHT = c.height;
        int error = make(dir + "/" + c.name, c.input, c.encoded, c.flags, c.quality, c.output, c.bytes);
        printf("%s: %d\n", c.name, error);
        if (error) return 1;
    }
    return 0;
}
