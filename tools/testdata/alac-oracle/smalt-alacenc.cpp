/*
 * Drives Apple's reference ALAC encoder (macosforge/alac, codec/ALACEncoder.cpp, Apache License 2.0), the oracle
 * of AlacEncoderOracleTest. tools/testdata/oracles.py compiles it together with the library's encoder sources.
 *
 * Usage: smalt-alacenc <input> <channels> <bits> <rate> <frame length> <fast: 0 or 1> <output>
 *
 * The input holds interleaved samples, in ALAC's channel order, as little-endian 32-bit integers that fit the
 * sample size (16, 20, 24 or 32 bits). They are encoded in frames of the frame length, the last one shorter, as the
 * library's own conversion utility does. The output holds the magic cookie vended after the last frame and then
 * every packet, each preceded by its length as a big-endian 32-bit integer.
 */
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

#include "ALACEncoder.h"
#include "ALACAudioTypes.h"

static void put32(std::vector<uint8_t> &out, uint32_t value) {
    out.push_back((uint8_t) (value >> 24));
    out.push_back((uint8_t) (value >> 16));
    out.push_back((uint8_t) (value >> 8));
    out.push_back((uint8_t) value);
}

int main(int argc, char **argv) {
    if (argc != 8) {
        fprintf(stderr, "usage: smalt-alacenc input channels bits rate frameLength fast output\n");
        return 2;
    }
    uint32_t channels = (uint32_t) atoi(argv[2]);
    uint32_t bits = (uint32_t) atoi(argv[3]);
    uint32_t rate = (uint32_t) atoi(argv[4]);
    uint32_t frameLength = (uint32_t) atoi(argv[5]);
    bool fast = atoi(argv[6]) != 0;
    uint32_t flags;
    uint32_t bytesPerSample;
    switch (bits) {
        case 16: flags = 1; bytesPerSample = 2; break;
        case 20: flags = 2; bytesPerSample = 3; break;
        case 24: flags = 3; bytesPerSample = 3; break;
        case 32: flags = 4; bytesPerSample = 4; break;
        default: fprintf(stderr, "unsupported sample size %u\n", bits); return 2;
    }
    if (channels < 1 || channels > 8 || frameLength < 16) {
        fprintf(stderr, "unsupported format\n");
        return 2;
    }

    FILE *input = fopen(argv[1], "rb");
    if (input == NULL) {
        perror(argv[1]);
        return 1;
    }
    std::vector<uint8_t> raw;
    uint8_t chunk[65536];
    size_t read;
    while ((read = fread(chunk, 1, sizeof chunk, input)) > 0) {
        raw.insert(raw.end(), chunk, chunk + read);
    }
    fclose(input);
    size_t totalFrames = raw.size() / 4 / channels;

    // The encoder reads 16- and 32-bit samples as native integers and 20- and 24-bit samples as three
    // little-endian bytes, the 20 bits left-justified in them.
    std::vector<uint8_t> pcm(totalFrames * channels * bytesPerSample);
    for (size_t i = 0; i < totalFrames * channels; i++) {
        int32_t value = (int32_t) ((uint32_t) raw[4 * i] | (uint32_t) raw[4 * i + 1] << 8
                                   | (uint32_t) raw[4 * i + 2] << 16 | (uint32_t) raw[4 * i + 3] << 24);
        uint8_t *target = &pcm[i * bytesPerSample];
        if (bits == 16) {
            int16_t sample = (int16_t) value;
            memcpy(target, &sample, 2);
        } else if (bits == 32) {
            memcpy(target, &value, 4);
        } else {
            uint32_t word = (uint32_t) (bits == 20 ? value * 16 : value);
            target[0] = (uint8_t) word;
            target[1] = (uint8_t) (word >> 8);
            target[2] = (uint8_t) (word >> 16);
        }
    }

    AudioFormatDescription inputFormat = {};
    inputFormat.mSampleRate = rate;
    inputFormat.mFormatID = kALACFormatLinearPCM;
    inputFormat.mFormatFlags = kALACFormatFlagIsSignedInteger | kALACFormatFlagIsPacked;
    inputFormat.mBytesPerPacket = channels * bytesPerSample;
    inputFormat.mFramesPerPacket = 1;
    inputFormat.mBytesPerFrame = channels * bytesPerSample;
    inputFormat.mChannelsPerFrame = channels;
    inputFormat.mBitsPerChannel = bits;

    AudioFormatDescription outputFormat = {};
    outputFormat.mSampleRate = rate;
    outputFormat.mFormatID = kALACFormatAppleLossless;
    outputFormat.mFormatFlags = flags;
    outputFormat.mFramesPerPacket = frameLength;
    outputFormat.mChannelsPerFrame = channels;

    ALACEncoder *encoder = new ALACEncoder;
    encoder->SetFrameSize(frameLength);
    encoder->SetFastMode(fast);
    if (encoder->InitializeEncoder(outputFormat) != 0) {
        fprintf(stderr, "InitializeEncoder failed\n");
        return 1;
    }

    // Generous room: the encoder writes a frame before it decides to escape it, and its bit writer reads and
    // writes four bytes at a time past the current position.
    std::vector<uint8_t> packet(frameLength * channels * 16 + 4096);
    std::vector<uint8_t> packets;
    for (size_t first = 0; first < totalFrames; first += frameLength) {
        size_t frames = totalFrames - first < frameLength ? totalFrames - first : frameLength;
        std::vector<uint8_t> block(pcm.begin() + first * channels * bytesPerSample,
                                   pcm.begin() + (first + frames) * channels * bytesPerSample);
        std::fill(packet.begin(), packet.end(), 0);
        int32_t size = (int32_t) block.size();
        if (encoder->Encode(inputFormat, inputFormat, block.data(), packet.data(), &size) != 0) {
            fprintf(stderr, "Encode failed at frame %zu\n", first);
            return 1;
        }
        put32(packets, (uint32_t) size);
        packets.insert(packets.end(), packet.begin(), packet.begin() + size);
    }

    uint32_t cookieSize = encoder->GetMagicCookieSize(channels);
    std::vector<uint8_t> cookie(cookieSize);
    encoder->GetMagicCookie(cookie.data(), &cookieSize);
    std::vector<uint8_t> out;
    put32(out, cookieSize);
    out.insert(out.end(), cookie.begin(), cookie.begin() + cookieSize);
    out.insert(out.end(), packets.begin(), packets.end());

    FILE *output = fopen(argv[7], "wb");
    if (output == NULL || fwrite(out.data(), 1, out.size(), output) != out.size() || fclose(output) != 0) {
        perror(argv[7]);
        return 1;
    }
    delete encoder;
    return 0;
}
