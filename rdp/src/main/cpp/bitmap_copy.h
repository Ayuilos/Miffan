#pragma once
#include <cstddef>
#include <cstdint>
#if defined(__aarch64__) && !defined(MIFFAN_BITMAP_SCALAR)
#include <arm_neon.h>
#endif

// Separate BGRA/BGRX primary and RGBA Bitmap buffers, already offset to the dirty
// rectangle. Convert and force opaque alpha in one pass, respecting both strides.
inline void copyOpaqueBgraToRgba(uint8_t* __restrict dst, size_t dstStride,
                                const uint8_t* __restrict src, size_t srcStride,
                                size_t width, size_t height) {
    if (!width || !height) return;
    for (size_t row = 0; row < height; ++row) {
        const auto* s = src + row * srcStride;
        auto* d = dst + row * dstStride;
        size_t col = 0;
#if defined(__aarch64__) && !defined(MIFFAN_BITMAP_SCALAR)
        for (; col + 16 <= width; col += 16) {
            auto bgra = vld4q_u8(s + col * 4);
            uint8x16x4_t rgba = {{bgra.val[2], bgra.val[1], bgra.val[0], vdupq_n_u8(255)}};
            vst4q_u8(d + col * 4, rgba);
        }
#endif
        for (; col < width; ++col) {
            d[col * 4] = s[col * 4 + 2];
            d[col * 4 + 1] = s[col * 4 + 1];
            d[col * 4 + 2] = s[col * 4];
            d[col * 4 + 3] = 255;
        }
    }
}
