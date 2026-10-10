#include "frame_ack.h"
#include <cassert>
#include <vector>

int main() {
    // MS-RDPEDYC DATA (one-byte DVC id 7), MS-RDPEGFX FrameAcknowledge, frame 0x12345678.
    const std::vector<uint8_t> wire = {
        0x30, 7, 0x0d, 0, 0, 0, 20, 0, 0, 0,
        0xff, 0xff, 0xff, 0xff, 0x78, 0x56, 0x34, 0x12, 1, 0, 0, 0
    };
    assert(frameAckId(wire.data(), wire.size()) == 0x12345678);
    assert(!frameAckId(nullptr, wire.size()));
    for (size_t size = 0; size < wire.size(); ++size) assert(!frameAckId(wire.data(), size));
    auto extra = wire; extra.push_back(0); assert(!frameAckId(extra.data(), extra.size()));
    // Two-byte and four-byte DVC identifiers carry the same GFX payload.
    auto id16 = wire; id16[0] = 0x31; id16.insert(id16.begin() + 2, 0x11);
    assert(frameAckId(id16.data(), id16.size()) == 0x12345678);
    auto id32 = wire; id32[0] = 0x32; id32.insert(id32.begin() + 2, {0x11, 0x22, 0x33});
    assert(frameAckId(id32.data(), id32.size()) == 0x12345678);
    for (auto position : {0, 2, 3, 4, 5, 6, 7, 8, 9}) {
        auto other = wire; other[position] ^= 0x80;
        assert(!frameAckId(other.data(), other.size()));
    }
    auto fragmented = wire; fragmented[0] = 0x20;
    assert(!frameAckId(fragmented.data(), fragmented.size()));
    auto reserved = wire; reserved[0] = 0x33;
    assert(!frameAckId(reserved.data(), reserved.size()));
    auto sp = wire; sp[0] |= 4;
    assert(!frameAckId(sp.data(), sp.size()));
}
