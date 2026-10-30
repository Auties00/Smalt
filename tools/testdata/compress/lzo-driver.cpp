// Compresses (c) or decompresses (d) one file with lzokay (MIT), for make-vectors.py.
// Usage: lzo-driver c|d <input> <output> [decompressed size]
#include "lzokay.hpp"

#include <cstdio>
#include <cstdlib>
#include <vector>

static std::vector<uint8_t> slurp(const char* path) {
  std::vector<uint8_t> data;
  FILE* file = std::fopen(path, "rb");
  if (!file) {
    std::perror(path);
    std::exit(2);
  }
  uint8_t buffer[65536];
  std::size_t read;
  while ((read = std::fread(buffer, 1, sizeof buffer, file)) > 0) {
    data.insert(data.end(), buffer, buffer + read);
  }
  std::fclose(file);
  return data;
}

int main(int argc, char** argv) {
  if (argc < 4) {
    std::fprintf(stderr, "usage: lzo-driver c|d <input> <output> [size]\n");
    return 2;
  }
  auto input = slurp(argv[2]);
  std::vector<uint8_t> output;
  std::size_t size = 0;
  lzokay::EResult result;
  if (argv[1][0] == 'c') {
    output.resize(lzokay::compress_worst_size(input.size()));
    result = lzokay::compress(input.data(), input.size(), output.data(), output.size(), size);
  } else {
    output.resize(argc > 4 ? std::strtoull(argv[4], nullptr, 10) : input.size() * 64 + 64);
    result = lzokay::decompress(input.data(), input.size(), output.data(), output.size(), size);
  }
  if (result != lzokay::EResult::Success) {
    std::fprintf(stderr, "lzokay failed: %d\n", static_cast<int>(result));
    return 1;
  }
  FILE* file = std::fopen(argv[3], "wb");
  std::fwrite(output.data(), 1, size, file);
  std::fclose(file);
  return 0;
}
