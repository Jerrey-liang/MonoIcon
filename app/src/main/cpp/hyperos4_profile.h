#pragma once
#include <cstddef>
#include <cstdint>
#include <map>
#include <string>
#include <vector>

namespace monoicon::profile {
constexpr size_t kMaxProfileBytes = 2 * 1024 * 1024;
constexpr size_t kMaxPatches = 256;
struct Segment {
    uint64_t offset{}, address{}, fileSize{}, memorySize{}, alignment{};
    uint32_t flags{};
};
struct Snapshot { uint64_t address{}; std::vector<uint8_t> bytes; };
struct Image {
    std::string id, library, sha256;
    uint64_t fileSize{};
    std::vector<Segment> segments;
    std::vector<Snapshot> identity;
};
struct Function : Snapshot { std::string id; size_t image{}; };
enum class ValueKind { Original, Word, BackgroundLow, BackgroundHigh, ForegroundLow, ForegroundHigh, Veneer, Return };
struct Value { ValueKind kind = ValueKind::Original; uint32_t word{}; };
struct Patch {
    std::string id;
    size_t function{};
    uint64_t address{};
    uint32_t original{};
    Value palette, icons, circle;
};
struct Veneer { std::vector<Value> words; size_t patch{}; };
struct Property { std::string name; std::vector<std::string> prefixes; };
struct Match {
    std::string package;
    std::vector<std::string> versionCodes, versionNames;
    uint32_t sdkMin{}, sdkMax{};
    std::vector<Property> properties;
};
struct Icons {
    std::vector<std::string> prefixes, names;
    std::vector<std::string> largeIconPrefixes;
    size_t geometryBridge = SIZE_MAX;
    std::map<std::string, std::string> aliases;
};
// Optional app-side refresh hint; the native engine validates but never sends it.
struct Refresh { std::string action, packageExtra; };
struct Profile {
    std::string id, name, settingsKey;
    uint32_t revision{};
    Match match;
    std::vector<Image> images;
    std::vector<Function> functions;
    std::vector<Patch> patches;
    Veneer veneer;
    Icons icons;
    Refresh refresh;
};
bool Parse(const std::string& json, Profile& profile, std::string& error);
bool FileOffset(const Image& image, uint64_t address, uint64_t size, uint64_t& offset, uint32_t flags = 4);
uint32_t Resolve(const Value& value, uint32_t original, uint32_t background, uint32_t foreground, uint32_t branch);
bool ValidIconName(const std::string& name);
} // namespace monoicon::profile
