#pragma once

#include <algorithm>
#include <chrono>
#include <cstdint>
#include <deque>

inline uint64_t perfNow() {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}
struct Timing {
    double last = 0, sum = 0, maximum = 0;
    uint64_t count = 0;
    void add(uint64_t ns) {
        last = ns / 1e6; sum += last; maximum = std::max(maximum, last); ++count;
    }
    double mean() const { return count ? sum / count : 0; }
    void resetWindow() { sum = maximum = 0; count = 0; }
};
// Owned by one session, accessed exclusively on its FreeRDP worker thread.
struct Performance {
    struct PendingAck { uint32_t id; uint64_t surface, queued; };
    std::deque<PendingAck> pendingAcks;
    Timing decode, yuv, sink, ack, ackQueue;
    Timing frameData, surfaceWork, loopWait, compose;
    Timing inputWait, outputWait, outputAccess, outputRelease, inputQueue;
    uint64_t frameWorkNs = 0, frameWaitNs = 0, sinkTotalNs = 0;
    uint64_t directFrames = 0, ackBeforeSinkFrames = 0;
    uint64_t transportCalls = 0, transportBytes = 0, frameReadStart = 0, frameReads = 0;
    uint64_t submitted = 0, surface = 0, window = perfNow();
    uint64_t decoded = 0, windowDecoded = 0, timeouts = 0;
    int inFlight = 0, peak = 0;
    bool neon = false;
};
