#include "bitmap_copy.h"
#include <cassert>
#include <vector>

int main() {
    copyOpaqueBgraToRgba(nullptr, 0, nullptr, 0, 0, 3);
    copyOpaqueBgraToRgba(nullptr, 0, nullptr, 0, 20, 0);
    for (size_t width : {1, 2, 15, 16, 17, 31, 32, 33}) {
        const size_t height = 3, srcStride = width * 4 + 11, dstStride = width * 4 + 19;
        std::vector<uint8_t> src(1 + srcStride * height + 13, 0x3c);
        std::vector<uint8_t> dst(1 + dstStride * height + 13, 0xa5);
        auto expected = dst;
        for (size_t y = 0; y < height; ++y) {
            for (size_t x = 0; x < width; ++x) {
                auto* pixel = &src[1 + y * srcStride + x * 4];
                pixel[0] = uint8_t(17 + x); pixel[1] = uint8_t(55 + y);
                pixel[2] = uint8_t(91 + x + y); pixel[3] = uint8_t(x);
                auto* rgba = &expected[1 + y * dstStride + x * 4];
                rgba[0] = uint8_t(91 + x + y); rgba[1] = uint8_t(55 + y);
                rgba[2] = uint8_t(17 + x); rgba[3] = 255;
            }
        }
        auto original = src;
        // Deliberately unaligned buffers and different padded row strides.
        copyOpaqueBgraToRgba(dst.data() + 1, dstStride, src.data() + 1, srcStride, width, height);
        assert(dst == expected); // includes untouched row padding and bounds
        assert(src == original);
    }
}
