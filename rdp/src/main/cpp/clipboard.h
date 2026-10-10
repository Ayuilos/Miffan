#pragma once
#include <deque>
#include <mutex>
#include <string>
#include <vector>
#include <freerdp/client/cliprdr.h>
#include <winpr/windows.h>
#include <winpr/sysinfo.h>

// cliprdr has its own receive thread. Callbacks only copy bounded events; JNI and all
// outgoing requests run on the owning RDP thread. Never use its JNIEnv on the channel thread.
struct Clipboard {
    static constexpr size_t LIMIT = 4 * 1024 * 1024;
    struct Event { int type; UINT32 value; std::vector<BYTE> data; };
    std::mutex mutex;
    std::deque<Event> events;
    size_t queuedBytes = 0;
    CliprdrClientContext* context = nullptr;
    std::u16string local;
    bool hasLocal = false, ready = false, dirty = false, requested = false, refresh = false, awaitingAck = false;
    UINT64 deadline = 0;

    static UINT push(CliprdrClientContext* context, Event event) {
        auto* self = static_cast<Clipboard*>(context->custom);
        if (!self) return CHANNEL_RC_OK;
        std::lock_guard<std::mutex> lock(self->mutex);
        if (self->events.size() >= 32 || self->queuedBytes + event.data.size() > LIMIT) return ERROR_NOT_ENOUGH_MEMORY;
        self->queuedBytes += event.data.size();
        self->events.push_back(std::move(event));
        return CHANNEL_RC_OK;
    }
    void attach(CliprdrClientContext* channel) {
        context = channel;
        channel->custom = this;
        channel->ServerCapabilities = [](CliprdrClientContext*, const CLIPRDR_CAPABILITIES*) -> UINT { return CHANNEL_RC_OK; };
        channel->MonitorReady = [](CliprdrClientContext* c, const CLIPRDR_MONITOR_READY*) { return push(c, {1, 0, {}}); };
        channel->ServerFormatList = [](CliprdrClientContext* c, const CLIPRDR_FORMAT_LIST* list) {
            bool unicode = false;
            for (UINT32 i = 0; i < list->numFormats; ++i) unicode |= list->formats[i].formatId == CF_UNICODETEXT;
            return push(c, {2, unicode ? 1U : 0U, {}});
        };
        channel->ServerFormatListResponse = [](CliprdrClientContext* c, const CLIPRDR_FORMAT_LIST_RESPONSE* response) {
            return push(c, {5, (response->common.msgFlags & CB_RESPONSE_OK) ? 1U : 0U, {}});
        };
        channel->ServerFormatDataRequest = [](CliprdrClientContext* c, const CLIPRDR_FORMAT_DATA_REQUEST* request) {
            return push(c, {3, request->requestedFormatId, {}});
        };
        channel->ServerFormatDataResponse = [](CliprdrClientContext* c, const CLIPRDR_FORMAT_DATA_RESPONSE* response) -> UINT {
            const size_t size = response->common.dataLen;
            if (size > LIMIT || (size & 1) || (size && !response->requestedFormatData)) return ERROR_INVALID_DATA;
            Event event{4, (response->common.msgFlags & CB_RESPONSE_OK) ? 1U : 0U, {}};
            if (size) event.data.assign(response->requestedFormatData, response->requestedFormatData + size);
            return push(c, std::move(event));
        };
    }
    UINT advertise() {
        CLIPRDR_FORMAT format{CF_UNICODETEXT, nullptr};
        CLIPRDR_FORMAT_LIST list{};
        list.common.msgType = CB_FORMAT_LIST;
        list.numFormats = hasLocal ? 1 : 0; list.formats = hasLocal ? &format : nullptr;
        dirty = false; awaitingAck = true; deadline = GetTickCount64() + 10000;
        return context->ClientFormatList(context, &list);
    }
    UINT request() {
        CLIPRDR_FORMAT_DATA_REQUEST request{};
        request.common.msgType = CB_FORMAT_DATA_REQUEST;
        request.requestedFormatId = CF_UNICODETEXT;
        requested = true; refresh = false;
        return context->ClientFormatDataRequest(context, &request);
    }
    UINT respond(UINT32 format) {
        std::vector<BYTE> bytes;
        const bool ok = hasLocal && format == CF_UNICODETEXT;
        if (ok) {
            bytes.reserve((local.size() + 1) * 2);
            for (char16_t c : local) { bytes.push_back(c & 0xff); bytes.push_back(c >> 8); }
            bytes.push_back(0); bytes.push_back(0);
        }
        CLIPRDR_FORMAT_DATA_RESPONSE response{};
        response.common.msgType = CB_FORMAT_DATA_RESPONSE;
        response.common.msgFlags = ok ? CB_RESPONSE_OK : CB_RESPONSE_FAIL;
        response.common.dataLen = static_cast<UINT32>(bytes.size());
        response.requestedFormatData = bytes.data();
        return context->ClientFormatDataResponse(context, &response);
    }
};
