#pragma once

#include <cstdint>
#include <limits>
#include <sstream>
#include <string>
#include <vector>

namespace monoicon::safety {
constexpr uint64_t kHealthyAfterMs = 30000;

struct Identity {
    std::string boot;
    uint64_t pid = 0, start = 0;
    bool operator==(const Identity& other) const {
        return boot == other.boot && pid == other.pid && start == other.start;
    }
};
struct State {
    bool blocked = false;
    Identity process;
    uint64_t armedMs = 0;
    bool healthy = false;
    std::string reset = "-";
};

inline bool Hex(const std::string& value, size_t length) {
    if (value.size() != length) return false;
    for (char c : value) if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
    return true;
}
inline bool ValidBoot(const std::string& value) {
    if (value.size() != 36) return false;
    std::string hex;
    for (size_t i = 0; i < value.size(); ++i) {
        if (i == 8 || i == 13 || i == 18 || i == 23) {
            if (value[i] != '-') return false;
        } else hex += value[i];
    }
    return Hex(hex, 32);
}
inline bool ValidReset(const std::string& value) { return value == "-" || Hex(value, 32); }
inline bool Number(const std::string& text, uint64_t& result) {
    if (text.empty()) return false;
    result = 0;
    for (char c : text) {
        if (c < '0' || c > '9' || result > (std::numeric_limits<uint64_t>::max() - (c - '0')) / 10) return false;
        result = result * 10 + (c - '0');
    }
    return true;
}
inline std::vector<std::string> Fields(const std::string& text) {
    std::istringstream input(text);
    std::vector<std::string> fields;
    std::string field;
    while (input >> field) fields.push_back(field);
    return fields;
}
inline bool ParseIdentity(const std::vector<std::string>& fields, size_t first, Identity& identity) {
    if (fields.size() < first + 3 || !ValidBoot(fields[first])) return false;
    identity.boot = fields[first];
    return Number(fields[first + 1], identity.pid) && identity.pid > 0 && identity.pid <= 0x7fffffffu &&
        Number(fields[first + 2], identity.start) && identity.start > 0;
}
inline bool ParseState(const std::string& text, State& state) {
    const auto fields = Fields(text);
    if (fields.size() != 9 || fields[0] != "MONOICON_SAFE" || fields[1] != "1" ||
        (fields[2] != "0" && fields[2] != "1") || (fields[7] != "0" && fields[7] != "1") ||
        !ValidReset(fields[8]) || !ParseIdentity(fields, 3, state.process) || !Number(fields[6], state.armedMs)) return false;
    state.blocked = fields[2] == "1";
    state.healthy = fields[7] == "1";
    state.reset = fields[8];
    return true;
}
inline std::string Encode(const State& state) {
    return "MONOICON_SAFE 1 " + std::to_string(state.blocked ? 1 : 0) + " " + state.process.boot + " " +
        std::to_string(state.process.pid) + " " + std::to_string(state.process.start) + " " +
        std::to_string(state.armedMs) + " " + std::to_string(state.healthy ? 1 : 0) + " " + state.reset + "\n";
}
inline bool ParseExpected(const std::string& text, Identity& identity) {
    const auto fields = Fields(text);
    return fields.size() == 3 && ParseIdentity(fields, 0, identity);
}

// Only a changed retry token clears a persistent block. An intentional restart
// receipt exempts exactly one pending predecessor and can never clear a block.
inline State Start(const State* previous, const Identity& current, uint64_t nowMs,
                   const std::string& reset, const Identity* expected = nullptr) {
    // Losing the optional retry file is not user consent. Keep the last
    // consumed token so a missing file cannot release a persistent latch.
    const std::string effectiveReset = previous && reset == "-" ? previous->reset : reset;
    State next{false, current, nowMs, false, effectiveReset};
    if (!previous || previous->reset != effectiveReset) return next;
    if (previous->blocked) { next.blocked = true; return next; }
    if (previous->process == current) return *previous;
    if (previous->process.boot == current.boot && !previous->healthy &&
        !(expected && *expected == previous->process)) next.blocked = true;
    return next;
}

inline bool CanMarkHealthy(const State& state, const Identity& current,
                           const std::string& reset, uint64_t nowMs) {
    return !state.blocked && state.process == current && state.reset == reset &&
        nowMs >= state.armedMs && nowMs - state.armedMs >= kHealthyAfterMs;
}
} // namespace monoicon::safety
