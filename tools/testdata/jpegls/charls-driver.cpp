// A command line driver of CharLS (BSD-3-Clause) for tools/testdata/jpegls/make-vectors.py: it decodes JPEG-LS
// files to planar 16-bit samples and encodes planar 16-bit samples with the options the vectors use, so that the
// vectors are CharLS's output for inputs the tests regenerate.
//
//   charls-driver decode <in.jls> <out.raw>
//       writes "width height components bits" to stdout and the samples to out.raw: planar, component after
//       component, each sample an unsigned 16-bit little-endian value.
//   charls-driver encode <in.raw> <out.jls> <width> <height> <components> <bits> <interleave> <near>
//                        <maxval> <t1> <t2> <t3> <reset> <transform> [<spiff color space>]
//       reads planar 16-bit little-endian samples; 0 for maxval, t1, t2, t3 or reset keeps the default; a SPIFF
//       header with the given color space is written when the last argument is present.
//   charls-driver encode-mapped <in.raw> <out.jls> <width> <height> <bits> <table> <entry size>
//       encodes one component whose samples index the mapping table read from the file <table>, mapping table
//       identifier 1.
#include <charls/charls.hpp>

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <iostream>
#include <iterator>
#include <string>
#include <vector>

namespace {

std::vector<std::byte> read_file(const char* path)
{
    std::ifstream input(path, std::ios::binary);
    if (!input)
    {
        std::cerr << "cannot read " << path << '\n';
        std::exit(2);
    }
    std::vector<char> bytes((std::istreambuf_iterator<char>(input)), std::istreambuf_iterator<char>());
    std::vector<std::byte> result(bytes.size());
    for (size_t i = 0; i < bytes.size(); ++i)
    {
        result[i] = static_cast<std::byte>(bytes[i]);
    }
    return result;
}

void write_file(const char* path, const std::vector<std::byte>& bytes, size_t size)
{
    std::ofstream output(path, std::ios::binary);
    output.write(reinterpret_cast<const char*>(bytes.data()), static_cast<std::streamsize>(size));
    if (!output)
    {
        std::cerr << "cannot write " << path << '\n';
        std::exit(2);
    }
}

uint32_t read_planar(const std::vector<std::byte>& raw, size_t index)
{
    return std::to_integer<uint32_t>(raw[2 * index]) | (std::to_integer<uint32_t>(raw[2 * index + 1]) << 8);
}

int decode(const char* in, const char* out)
{
    const auto source{read_file(in)};
    charls::jpegls_decoder decoder{source, true};
    const auto info{decoder.frame_info()};
    const auto mode{decoder.get_interleave_mode()};
    std::vector<std::byte> decoded(decoder.get_destination_size());
    decoder.decode(decoded);
    const size_t width{info.width};
    const size_t height{info.height};
    const size_t count{static_cast<size_t>(info.component_count)};
    const size_t bytes{info.bits_per_sample > 8 ? 2U : 1U};
    std::vector<std::byte> planar(width * height * count * 2);
    for (size_t c = 0; c < count; ++c)
    {
        for (size_t i = 0; i < width * height; ++i)
        {
            const size_t at{mode == charls::interleave_mode::none ? (c * width * height + i) * bytes
                                                                   : (i * count + c) * bytes};
            uint32_t value{std::to_integer<uint32_t>(decoded[at])};
            if (bytes == 2)
            {
                value |= std::to_integer<uint32_t>(decoded[at + 1]) << 8;
            }
            const size_t to{(c * width * height + i) * 2};
            planar[to] = static_cast<std::byte>(value & 0xFF);
            planar[to + 1] = static_cast<std::byte>(value >> 8);
        }
    }
    write_file(out, planar, planar.size());
    std::cout << width << ' ' << height << ' ' << count << ' ' << info.bits_per_sample << '\n';
    return 0;
}

int encode(int argc, const char* const argv[])
{
    const auto raw{read_file(argv[2])};
    const auto width{static_cast<uint32_t>(std::atoi(argv[4]))};
    const auto height{static_cast<uint32_t>(std::atoi(argv[5]))};
    const auto count{std::atoi(argv[6])};
    const auto bits{std::atoi(argv[7])};
    const auto mode{static_cast<charls::interleave_mode>(std::atoi(argv[8]))};
    const auto near{std::atoi(argv[9])};
    const charls::jpegls_pc_parameters presets{std::atoi(argv[10]), std::atoi(argv[11]), std::atoi(argv[12]),
                                               std::atoi(argv[13]), std::atoi(argv[14])};
    const auto transform{static_cast<charls::color_transformation>(std::atoi(argv[15]))};
    const size_t bytes{bits > 8 ? 2U : 1U};
    const size_t samples{static_cast<size_t>(width) * height};
    std::vector<std::byte> source(samples * count * bytes);
    for (size_t c = 0; c < static_cast<size_t>(count); ++c)
    {
        for (size_t i = 0; i < samples; ++i)
        {
            const auto value{read_planar(raw, c * samples + i)};
            const size_t at{mode == charls::interleave_mode::none ? (c * samples + i) * bytes : (i * count + c) * bytes};
            source[at] = static_cast<std::byte>(value & 0xFF);
            if (bytes == 2)
            {
                source[at + 1] = static_cast<std::byte>(value >> 8);
            }
        }
    }
    charls::jpegls_encoder encoder;
    encoder.frame_info({width, height, bits, count})
        .interleave_mode(mode)
        .near_lossless(near)
        .preset_coding_parameters(presets)
        .color_transformation(transform);
    std::vector<std::byte> destination(encoder.estimated_destination_size());
    encoder.destination(destination);
    if (argc > 16)
    {
        encoder.write_standard_spiff_header(static_cast<charls::spiff_color_space>(std::atoi(argv[16])));
    }
    const auto size{encoder.encode(source)};
    write_file(argv[3], destination, size);
    return 0;
}

int encode_mapped(const char* const argv[])
{
    const auto raw{read_file(argv[2])};
    const auto width{static_cast<uint32_t>(std::atoi(argv[4]))};
    const auto height{static_cast<uint32_t>(std::atoi(argv[5]))};
    const auto bits{std::atoi(argv[6])};
    const auto table{read_file(argv[7])};
    const auto entry_size{std::atoi(argv[8])};
    const size_t bytes{bits > 8 ? 2U : 1U};
    const size_t samples{static_cast<size_t>(width) * height};
    std::vector<std::byte> source(samples * bytes);
    for (size_t i = 0; i < samples; ++i)
    {
        const auto value{read_planar(raw, i)};
        source[i * bytes] = static_cast<std::byte>(value & 0xFF);
        if (bytes == 2)
        {
            source[i * bytes + 1] = static_cast<std::byte>(value >> 8);
        }
    }
    charls::jpegls_encoder encoder;
    encoder.frame_info({width, height, bits, 1});
    std::vector<std::byte> destination(encoder.estimated_destination_size() + table.size() + 1024);
    encoder.destination(destination);
    encoder.write_mapping_table(1, entry_size, table);
    encoder.set_mapping_table_id(0, 1);
    const auto size{encoder.encode(source)};
    write_file(argv[3], destination, size);
    return 0;
}

} // namespace

int main(const int argc, const char* const argv[])
{
    try
    {
        const std::string command{argc > 1 ? argv[1] : ""};
        if (command == "decode" && argc == 4)
        {
            return decode(argv[2], argv[3]);
        }
        if (command == "encode" && (argc == 16 || argc == 17))
        {
            return encode(argc, argv);
        }
        if (command == "encode-mapped" && argc == 9)
        {
            return encode_mapped(argv);
        }
        std::cerr << "usage: see the comment at the top of charls-driver.cpp\n";
        return 2;
    }
    catch (const std::exception& error)
    {
        std::cerr << "charls: " << error.what() << '\n';
        return 1;
    }
}
