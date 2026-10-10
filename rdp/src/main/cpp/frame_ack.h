#pragma once
#include <cstddef>
#include <cstdint>
#include <optional>

// A 20-byte RDPGFX FRAMEACKNOWLEDGE carried in one unfragmented DRDYNVC DATA PDU.
// Only recognize the exact wire shape; never inspect arbitrary desktop/clipboard data.
inline std::optional<uint32_t> frameAckId(const uint8_t* data, size_t size) {
    if (!data || size < 22 || (data[0] >> 4) != 3 || (data[0] & 0x0c) != 0) return {};
    const unsigned idCode = data[0] & 3;
    if (idCode > 2) return {};
    const size_t offset = 1 + (1u << idCode);
    if (size != offset + 20) return {};
    const auto* gfx = data + offset;
    if (gfx[0] != 0x0d || gfx[1] || gfx[2] || gfx[3] || gfx[4] != 20 || gfx[5] || gfx[6] || gfx[7]) return {};
    return uint32_t(gfx[12]) | (uint32_t(gfx[13]) << 8) | (uint32_t(gfx[14]) << 16) | (uint32_t(gfx[15]) << 24);
}
