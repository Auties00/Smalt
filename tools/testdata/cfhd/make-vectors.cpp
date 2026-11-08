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
#include "CFHDMetadataTags.h"

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

// Returns a 16-bit sample of color c (0 red, 1 green, 2 blue) at (x, y) of frame f of the CineForm RAW pattern:
// gradients, a checker, a moving square and a clipped highlight.
static int scene(int c, int x, int y, int f) {
    int value = 6000 + x * 160 + y * 90 * (c + 1) + c * 7000;
    if (((x >> 4) ^ (y >> 3)) & 1) value = value * 2 / 3;
    int sx = 12 + f * 11, sy = 6 + f * 7;
    if (x >= sx && x < sx + 30 && y >= sy && y < sy + 22) value = c == 1 ? 52000 : 9000 + c * 14000;
    if (x >= WIDTH - 24 && y < 12) value = 65535;
    return value > 65535 ? 65535 : value;
}

// The color of the photosite at (x, y) for each Bayer phase (0 RGGB, 1 GRBG, 2 GBRG, 3 BGGR).
static int filter(int phase, int x, int y) {
    static const int map[4][4] = {{0, 1, 1, 2}, {1, 0, 2, 1}, {1, 2, 0, 1}, {2, 1, 1, 0}};
    return map[phase][(y & 1) * 2 + (x & 1)];
}

// The path of this program, which decodes each CineForm RAW sample in a process of its own (see decodeAll).
static std::string SELF;

// Decodes one sample with a new decoder, the first decode of this process, and writes the decode; the --decode mode
// of main.
static int decodeOne(CFHD_PixelFormat format, int bytes, const char *input, const char *output) {
    FILE *in = fopen(input, "rb");
    if (!in) return 11;
    std::vector<uint8_t> sample;
    uint8_t chunk[65536];
    size_t n;
    while ((n = fread(chunk, 1, sizeof chunk, in)) > 0) sample.insert(sample.end(), chunk, chunk + n);
    fclose(in);
    // the demosaic of GoPro's decoder races between its threads, so the metadata entry that limits it to one core
    // (CPUM 1) takes the place of the start of the free space the encoder leaves in the sample's metadata
    static const uint8_t freeSpace[] = {'F', 'R', 'E', 'E'};
    for (size_t at = 0; at + 8 <= sample.size(); at += 4) {
        if (memcmp(&sample[at], freeSpace, 4) == 0) {
            uint32_t size = sample[at + 4] | sample[at + 5] << 8 | sample[at + 6] << 16;
            if (sample[at + 7] != 0 || size < 12 || size % 4 != 0) break;
            static const uint8_t limit[] = {'C', 'P', 'U', 'M', 4, 0, 0, 'L', 1, 0, 0, 0, 'F', 'R', 'E', 'E'};
            if (at + 20 > sample.size()) break;
            memcpy(&sample[at], limit, sizeof limit);
            size -= 12;
            sample[at + 16] = (uint8_t) size;
            sample[at + 17] = (uint8_t) (size >> 8);
            sample[at + 18] = (uint8_t) (size >> 16);
            sample[at + 19] = 0;
            break;
        }
    }
    CFHD_DecoderRef decoder;
    if (CFHD_OpenDecoder(&decoder, NULL)) return 1;
    int width, height;
    CFHD_PixelFormat actual;
    if (CFHD_PrepareToDecode(decoder, WIDTH, HEIGHT, format, CFHD_DECODED_RESOLUTION_FULL, CFHD_DECODING_FLAGS_NONE,
                             sample.data(), sample.size(), &width, &height, &actual)) return 6;
    if (actual != format || width != WIDTH || height != HEIGHT) return 7;
    std::vector<uint8_t> decoded((size_t) WIDTH * HEIGHT * bytes);
    if (CFHD_DecodeSample(decoder, sample.data(), sample.size(), decoded.data(), WIDTH * bytes)) return 8;
    CFHD_CloseDecoder(decoder);
    FILE *out = fopen(output, "wb");
    if (!out) return 3;
    fwrite(decoded.data(), 1, decoded.size(), out);
    fclose(out);
    return 0;
}

// Decodes CineForm RAW samples to one pixel format, each in a new process limited to one core (see decodeOne).
// GoPro's decoder gives Bayer output that depends on the work its process did before (another decode, an encode),
// and its threads race when they demosaic, so only the first decode of such a process is reproducible; every sample
// is decoded in two of them and the decodes compared. A 64x32 frame does not decode reproducibly even so.
static int decodeAll(const std::vector<std::vector<uint8_t>> &samples, CFHD_PixelFormat format, int bytes,
                     const std::string &scratch, std::vector<std::vector<uint8_t>> &decodes) {
    for (size_t f = 0; f < samples.size(); f++) {
        std::string input = scratch + ".sample", output = scratch + ".decode";
        FILE *out = fopen(input.c_str(), "wb");
        if (!out) return 3;
        fwrite(samples[f].data(), 1, samples[f].size(), out);
        fclose(out);
        std::vector<uint8_t> runs[2];
        for (int run = 0; run < 2; run++) {
            std::string command = "\"" + SELF + "\" --decode " + std::to_string(bytes) + " "
                    + std::to_string(WIDTH) + " " + std::to_string(HEIGHT) + " \"" + input + "\" \"" + output + "\"";
#ifdef _WIN32
            command = "\"" + command + "\"";
#endif
            if (system(command.c_str())) return 12;
            FILE *in = fopen(output.c_str(), "rb");
            if (!in) return 11;
            runs[run].resize((size_t) WIDTH * HEIGHT * bytes);
            if (fread(runs[run].data(), 1, runs[run].size(), in) != runs[run].size()) return 11;
            fclose(in);
        }
        if (runs[0] != runs[1]) return 13;
        decodes.push_back(runs[0]);
        remove(input.c_str());
        remove(output.c_str());
    }
    return 0;
}

// Writes a CineForm RAW vector: "CFHDBYR1", then per sample: u32le sample size, the sample, u32le size of the
// decode to BYR4 (16-bit little-endian photosites), that decode, u32le size of the decode to RG48 (0 when the vector
// leaves it out), that decode. The source is the scene above, or, with crop non-null, a mosaic of WIDTH x HEIGHT
// little-endian 16-bit photosites that is shifted by f columns of quads per frame.
static int makeBayer(const std::string &path, int phase, CFHD_EncodingQuality quality, uint32_t curve,
                     uint32_t preset, int frames, bool rgb, const std::vector<uint16_t> *crop) {
    CFHD_EncoderRef encoder;
    if (CFHD_OpenEncoder(&encoder, NULL)) return 1;
    if (CFHD_PrepareToEncode(encoder, WIDTH, HEIGHT, CFHD_PIXEL_FORMAT_BYR4, CFHD_ENCODED_FORMAT_BAYER,
                             CFHD_ENCODING_FLAGS_NONE, quality)) return 2;
    CFHD_MetadataRef metadata;
    if (CFHD_MetadataOpen(&metadata)) return 9;
    uint32_t value = (uint32_t) phase;
    CFHD_MetadataAdd(metadata, TAG_BAYER_FORMAT, METADATATYPE_UINT32, 4, &value, false);
    if (curve) CFHD_MetadataAdd(metadata, TAG_ENCODE_CURVE, METADATATYPE_UINT32, 4, &curve, false);
    if (preset) CFHD_MetadataAdd(metadata, TAG_ENCODE_PRESET, METADATATYPE_UINT32, 4, &preset, false);
    if (CFHD_MetadataAttach(encoder, metadata)) return 10;
    std::vector<uint16_t> frame((size_t) WIDTH * HEIGHT);
    std::vector<std::vector<uint8_t>> samples;
    for (int f = 0; f < frames; f++) {
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int source = (x + 2 * f) % WIDTH;
                frame[(size_t) y * WIDTH + x] = crop ? (*crop)[(size_t) y * WIDTH + source]
                                                     : (uint16_t) scene(filter(phase, x, y), x, y, f);
            }
        }
        if (CFHD_EncodeSample(encoder, frame.data(), WIDTH * 2)) return 4;
        void *sample;
        size_t size;
        if (CFHD_GetSampleData(encoder, &sample, &size)) return 5;
        samples.emplace_back((uint8_t *) sample, (uint8_t *) sample + size);
    }
    CFHD_MetadataClose(metadata);
    CFHD_CloseEncoder(encoder);
    std::vector<std::vector<uint8_t>> mosaics, colors;
    int error = decodeAll(samples, CFHD_PIXEL_FORMAT_BYR4, 2, path, mosaics);
    if (!error && rgb) error = decodeAll(samples, CFHD_PIXEL_FORMAT_RG48, 6, path, colors);
    if (error) return error;
    FILE *out = fopen(path.c_str(), "wb");
    if (!out) return 3;
    fwrite("CFHDBYR1", 1, 8, out);
    for (size_t f = 0; f < samples.size(); f++) {
        put32(out, (uint32_t) samples[f].size());
        fwrite(samples[f].data(), 1, samples[f].size(), out);
        put32(out, (uint32_t) mosaics[f].size());
        fwrite(mosaics[f].data(), 1, mosaics[f].size(), out);
        put32(out, rgb ? (uint32_t) colors[f].size() : 0);
        if (rgb) fwrite(colors[f].data(), 1, colors[f].size(), out);
    }
    fclose(out);
    return 0;
}

static std::vector<uint16_t> readCrop(const char *path, int width, int height) {
    std::vector<uint16_t> crop((size_t) width * height);
    FILE *in = fopen(path, "rb");
    if (!in || fread(crop.data(), 2, crop.size(), in) != crop.size()) {
        fprintf(stderr, "cannot read %s\n", path);
        exit(1);
    }
    fclose(in);
    return crop;
}

int main(int argc, char **argv) {
    SELF = argv[0];
    if (argc == 7 && std::string(argv[1]) == "--decode") {
        WIDTH = atoi(argv[3]);
        HEIGHT = atoi(argv[4]);
        return atoi(argv[2]) == 2 ? decodeOne(CFHD_PIXEL_FORMAT_BYR4, 2, argv[5], argv[6])
                                  : decodeOne(CFHD_PIXEL_FORMAT_RG48, 6, argv[5], argv[6]);
    }
    if (argc < 2) {
        fprintf(stderr, "usage: make-vectors <output directory> [<crop 256x128> <crop 160x96>]\n");
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
    struct BayerCase {
        const char *name;
        int width;
        int height;
        int phase;
        CFHD_EncodingQuality quality;
        uint32_t curve;
        uint32_t preset;
        int frames;
        bool rgb;
        int crop;
    } bayerCases[] = {
        {"bayer-rggb-filmscan1.cfhd", 128, 64, 0, CFHD_ENCODING_QUALITY_FILMSCAN1, 0, 0, 2, true, 0},
        {"bayer-grbg-high.cfhd", 128, 64, 1, CFHD_ENCODING_QUALITY_HIGH, 0, 0, 2, true, 0},
        {"bayer-gbrg-medium.cfhd", 128, 64, 2, CFHD_ENCODING_QUALITY_MEDIUM, 0, 0, 2, true, 0},
        {"bayer-bggr-low.cfhd", 128, 64, 3, CFHD_ENCODING_QUALITY_LOW, 0, 0, 2, true, 0},
        // coded heights above the display height (GoPro's decoder fails on rows of quads that are not a multiple of 8)
        {"bayer-rggb-176x90-filmscan1.cfhd", 176, 90, 0, CFHD_ENCODING_QUALITY_FILMSCAN1, 0, 0, 1, true, 0},
        {"bayer-grbg-208x120-high.cfhd", 208, 120, 1, CFHD_ENCODING_QUALITY_HIGH, 0, 0, 1, true, 0},
        // the encode curves of the ECRV metadata, and a source whose curve was applied before encoding; GoPro's
        // encoder applies the CineStyle curve to unnormalized photosites and codes extreme coefficients, which
        // Smalt reconstructs unlike GoPro's decoder, so that curve is held to the decoder's table only (curve-tables.c)
        {"bayer-gamma22.cfhd", 128, 64, 0, CFHD_ENCODING_QUALITY_FILMSCAN1, CFHD_CURVE_GAMMA_2pt2, 0, 1, false, 0},
        {"bayer-cineon17.cfhd", 128, 64, 1, CFHD_ENCODING_QUALITY_FILMSCAN1, CFHD_CURVE_CINEON_1pt7, 0, 1, false, 0},
        {"bayer-cine985.cfhd", 128, 64, 2, CFHD_ENCODING_QUALITY_FILMSCAN1, CFHD_CURVE_CINE985_1pt7, 0, 1, false, 0},
        {"bayer-redspace.cfhd", 128, 64, 3, CFHD_ENCODING_QUALITY_FILMSCAN1, CFHD_CURVE_REDSPACE, 0, 1, false, 0},
        {"bayer-cineon10.cfhd", 128, 64, 0, CFHD_ENCODING_QUALITY_FILMSCAN1, CFHD_CURVE_CINEON_1pt0, 0, 1, false, 0},
        {"bayer-slog.cfhd", 128, 64, 1, CFHD_ENCODING_QUALITY_FILMSCAN1, CFHD_CURVE_SLOG, 0, 1, false, 0},
        {"bayer-logc.cfhd", 128, 64, 2, CFHD_ENCODING_QUALITY_FILMSCAN1, CFHD_CURVE_LOGC, 0, 1, false, 0},
        {"bayer-protune.cfhd", 128, 64, 3, CFHD_ENCODING_QUALITY_FILMSCAN1, CFHD_CURVE_PROTUNE, 0, 1, false, 0},
        {"bayer-log12stop.cfhd", 128, 64, 0, CFHD_ENCODING_QUALITY_FILMSCAN1, CFHD_CURVE_LOG_12_STOP, 0, 1, false, 0},
        {"bayer-linear.cfhd", 128, 64, 1, CFHD_ENCODING_QUALITY_FILMSCAN1, CFHD_CURVE_LINEAR, 0, 1, false, 0},
        {"bayer-preset.cfhd", 128, 64, 2, CFHD_ENCODING_QUALITY_FILMSCAN1, 0, 1, 1, true, 0},
        // crops of a CC0 camera raw (see make-vectors.sh)
        {"bayer-canon-rggb-filmscan1.cfhd", 256, 128, 0, CFHD_ENCODING_QUALITY_FILMSCAN1, 0, 0, 1, true, 1},
        {"bayer-canon-bggr-medium.cfhd", 160, 96, 3, CFHD_ENCODING_QUALITY_MEDIUM, 0, 0, 1, true, 2},
    };
    for (auto &c : bayerCases) {
        if (c.crop && argc < 4) {
            printf("%s: skipped, no crop\n", c.name);
            continue;
        }
        WIDTH = c.width;
        HEIGHT = c.height;
        std::vector<uint16_t> crop;
        if (c.crop) crop = readCrop(argv[1 + c.crop], c.width, c.height);
        int error = makeBayer(dir + "/" + c.name, c.phase, c.quality, c.curve, c.preset, c.frames, c.rgb,
                              c.crop ? &crop : NULL);
        printf("%s: %d\n", c.name, error);
        if (error) return 1;
    }
    return 0;
}
