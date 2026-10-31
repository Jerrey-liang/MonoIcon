#include "hyperos4_profile.h"
#include "profile_json.h"
#include <algorithm>
#include <cstring>
#include <set>

namespace monoicon::profile {
namespace {
bool Keys(const Json& j, std::initializer_list<const char*> allowed) {
    if (j.type != Json::Object) return false;
    for (const auto& item : j.object) {
        if (item.first.compare(0, 2, "x-") == 0) continue;
        if (std::none_of(allowed.begin(), allowed.end(), [&](const char* key) { return item.first == key; })) return false;
    }
    return true;
}
bool Text(const Json& j, std::string& out, size_t maximum = 256) {
    if (j.type != Json::String || j.text.empty() || j.text.size() > maximum) return false;
    for (unsigned char c : j.text) if (c < 32 || c == 127) return false;
    out = j.text; return true;
}
bool Id(const Json& j, std::string& out) {
    if (!Text(j, out, 80)) return false;
    return std::all_of(out.begin(), out.end(), [](char c) { return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_'; });
}
bool Uint(const Json& j, uint64_t& out, uint64_t maximum = UINT64_MAX) {
    if (j.type != Json::String && j.type != Json::Number) return false;
    size_t pos = 0; unsigned base = 10; out = 0;
    if (j.type == Json::String && j.text.compare(0, 2, "0x") == 0) { base = 16; pos = 2; }
    if (pos == j.text.size()) return false;
    for (; pos < j.text.size(); ++pos) {
        char c = j.text[pos];
        unsigned d = c >= '0' && c <= '9' ? c - '0' : c >= 'a' && c <= 'f' ? c - 'a' + 10 : c >= 'A' && c <= 'F' ? c - 'A' + 10 : 16;
        if (d >= base || d > maximum || out > (maximum - d) / base) return false;
        out = out * base + d;
    }
    return true;
}
bool Uint32(const Json& j, uint32_t& out) { uint64_t n; if (!Uint(j, n, UINT32_MAX)) return false; out = static_cast<uint32_t>(n); return true; }
bool Array(const Json& j, size_t minimum, size_t maximum) { return j.type == Json::Array && j.array.size() >= minimum && j.array.size() <= maximum; }
bool Strings(const Json& j, std::vector<std::string>& out, size_t minimum = 1, size_t maximum = 128) {
    if (!Array(j, minimum, maximum)) return false;
    std::set<std::string> seen;
    for (const auto& item : j.array) { std::string s; if (!Text(item, s, 512) || !seen.insert(s).second) return false; out.push_back(s); }
    return true;
}
bool Hex(const Json& j, std::vector<uint8_t>& out, size_t limit) {
    if (j.type != Json::String || j.text.empty() || j.text.size() % 2 || j.text.size() / 2 > limit) return false;
    for (size_t i = 0; i < j.text.size(); i += 2) {
        unsigned byte = 0;
        for (size_t k = i; k < i + 2; ++k) {
            char c = j.text[k];
            unsigned d = c >= '0' && c <= '9' ? c - '0' : c >= 'a' && c <= 'f' ? c - 'a' + 10 : 16;
            if (d == 16) return false;
            byte = byte * 16 + d;
        }
        out.push_back(static_cast<uint8_t>(byte));
    }
    return true;
}
bool ParseValue(const Json& j, Value& out, bool inVeneer = false) {
    if (j.type == Json::String) {
        if (j.text == "original" && !inVeneer) out.kind = ValueKind::Original;
        else if (j.text == "veneer" && !inVeneer) out.kind = ValueKind::Veneer;
        else if (j.text == "return" && inVeneer) out.kind = ValueKind::Return;
        else return false;
        return true;
    }
    if (!Keys(j, {"word", "value", "register"})) return false;
    if (j.object.count("word")) {
        if (j.object.size() != 1 || !Uint32(j.at("word"), out.word)) return false;
        out.kind = ValueKind::Word; return true;
    }
    if (j.object.size() != 2 || !Uint32(j.at("register"), out.word) || out.word > 30) return false;
    const auto& kind = j.at("value");
    if (kind.type != Json::String) return false;
    if (kind.text == "background-low") out.kind = ValueKind::BackgroundLow;
    else if (kind.text == "background-high") out.kind = ValueKind::BackgroundHigh;
    else if (kind.text == "foreground-low") out.kind = ValueKind::ForegroundLow;
    else if (kind.text == "foreground-high") out.kind = ValueKind::ForegroundHigh;
    else return false;
    return true;
}
bool Prefix(const std::string& path) {
    return path.size() > 1 && path.size() <= 512 && path.front() == '/' && path.back() == '/' &&
        path.find("..") == std::string::npos && path.find("//") == std::string::npos && path.find('\\') == std::string::npos;
}
} // namespace

bool ValidIconName(const std::string& name) {
    return name.size() > 4 && name.size() <= 255 && name.compare(name.size() - 4, 4, ".png") == 0 &&
        name.find("..") == std::string::npos && std::all_of(name.begin(), name.end(), [](unsigned char c) {
            return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') ||
                c == '.' || c == '_' || c == '$' || c == '#' || c == '-';
        });
}
bool FileOffset(const Image& image, uint64_t address, uint64_t size, uint64_t& offset, uint32_t flags) {
    for (const auto& s : image.segments) {
        if ((s.flags & flags) == flags && address >= s.address && address - s.address <= s.fileSize && size <= s.fileSize - (address - s.address)) {
            offset = s.offset + address - s.address; return true;
        }
    }
    return false;
}

bool Parse(const std::string& json, Profile& result, std::string& error) {
    Profile p; Json root;
    if (!JsonReader(json).parse(root, error)) return false;
    auto fail = [&](const std::string& message) { error = message; return false; };
    uint64_t schema;
    if (!Keys(root, {"schema", "id", "name", "revision", "match", "images", "functions", "patches", "veneer", "icons", "settingsKey", "refresh"}) ||
        !Uint(root.at("schema"), schema) || schema != 1) return fail("unsupported profile schema or unknown root field");
    if (!Id(root.at("id"), p.id) || !Text(root.at("name"), p.name) || !Uint32(root.at("revision"), p.revision) || !p.revision || p.revision > INT32_MAX ||
        !Text(root.at("settingsKey"), p.settingsKey, 128)) return fail("invalid profile metadata");
    if (!std::all_of(p.settingsKey.begin(), p.settingsKey.end(), [](char c) { return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_'; })) return fail("invalid settingsKey");
    if (root.object.count("refresh")) {
        const auto& refresh = root.at("refresh");
        if (!Keys(refresh, {"action", "packageExtra"}) ||
            !Text(refresh.at("action"), p.refresh.action, 256) ||
            !Text(refresh.at("packageExtra"), p.refresh.packageExtra, 80)) return fail("invalid refresh fields");
        const auto identifier = [](char c) {
            return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
        };
        if (p.refresh.action.find('.') == std::string::npos ||
            !std::all_of(p.refresh.action.begin(), p.refresh.action.end(), [&](char c) { return c == '.' || identifier(c); }) ||
            !std::all_of(p.refresh.packageExtra.begin(), p.refresh.packageExtra.end(), identifier)) return fail("invalid refresh action or packageExtra");
    }
    const auto& m = root.at("match");
    if (!Keys(m, {"package", "versionCodes", "versionNames", "sdkMin", "sdkMax", "properties"}) ||
        !Text(m.at("package"), p.match.package) || p.match.package != "com.miui.home" ||
        !Strings(m.at("versionCodes"), p.match.versionCodes) || !Strings(m.at("versionNames"), p.match.versionNames) ||
        !Uint32(m.at("sdkMin"), p.match.sdkMin) || !Uint32(m.at("sdkMax"), p.match.sdkMax) ||
        p.match.sdkMin < 35 || p.match.sdkMax < p.match.sdkMin || p.match.sdkMax > 10000 ||
        !Array(m.at("properties"), 1, 32)) return fail("invalid match constraints (package, versions, SDK or properties)");
    for (const auto& code : p.match.versionCodes) {
        Json j; j.type = Json::String; j.text = code; uint64_t n;
        if (!Uint(j, n, INT64_MAX) || !n || std::to_string(n) != code) return fail("versionCodes must be canonical decimal strings");
    }
    std::set<std::string> propertyNames;
    for (const auto& property : m.at("properties").array) {
        Property item;
        if (!Keys(property, {"name", "prefixes"}) || !Text(property.at("name"), item.name, 92) ||
            item.name.compare(0, 3, "ro.") != 0 || !propertyNames.insert(item.name).second ||
            !Strings(property.at("prefixes"), item.prefixes)) return fail("invalid read-only system property constraint");
        for (char c : item.name) if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '.' || c == '-')) return fail("invalid property name");
        p.match.properties.push_back(std::move(item));
    }
    const auto& images = root.at("images");
    if (!Array(images, 1, 8)) return fail("images must contain 1..8 ELF images");
    std::map<std::string, size_t> imageIds;
    std::set<std::string> libraryNames;
    for (const auto& image : images.array) {
        Image item; std::vector<uint8_t> digest;
        if (!Keys(image, {"id", "library", "size", "sha256", "segments", "identity"}) ||
            !Id(image.at("id"), item.id) || !imageIds.emplace(item.id, p.images.size()).second ||
            !Text(image.at("library"), item.library, 128) || !libraryNames.insert(item.library).second ||
            item.library.compare(0, 3, "lib") != 0 || item.library.size() < 7 || item.library.compare(item.library.size() - 3, 3, ".so") != 0 ||
            item.library.find_first_not_of("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_.-") != std::string::npos ||
            !Uint(image.at("size"), item.fileSize, 1024ull * 1024 * 1024) || item.fileSize < 64 ||
            !Hex(image.at("sha256"), digest, 32) || digest.size() != 32 ||
            !Array(image.at("segments"), 1, 32) || !Array(image.at("identity"), 1, 32)) return fail("invalid ELF image " + item.id);
        item.sha256 = image.at("sha256").text;
        for (const auto& segment : image.at("segments").array) {
            Segment s;
            if (!Keys(segment, {"offset", "address", "fileSize", "memorySize", "flags", "alignment"}) ||
                !Uint(segment.at("offset"), s.offset, item.fileSize) || !Uint(segment.at("address"), s.address, UINT32_MAX) ||
                !Uint(segment.at("fileSize"), s.fileSize, item.fileSize - s.offset) ||
                !Uint(segment.at("memorySize"), s.memorySize, UINT32_MAX - s.address) || s.memorySize < s.fileSize || !s.memorySize ||
                !Uint32(segment.at("flags"), s.flags) || !(s.flags & 4) || (s.flags & ~7u) || (s.flags & 3) == 3 ||
                !Uint(segment.at("alignment"), s.alignment, 1024 * 1024) || !s.alignment || (s.alignment & (s.alignment - 1)) ||
                s.address % s.alignment != s.offset % s.alignment) return fail("invalid ELF segment in " + item.id);
            for (const auto& before : item.segments) if (s.address < before.address + before.memorySize && before.address < s.address + s.memorySize) return fail("overlapping ELF segments");
            item.segments.push_back(s);
        }
        for (const auto& identity : image.at("identity").array) {
            Snapshot s; uint64_t offset;
            if (!Keys(identity, {"address", "bytes"}) || !Uint(identity.at("address"), s.address, UINT32_MAX) ||
                !Hex(identity.at("bytes"), s.bytes, 4096) || !FileOffset(item, s.address, s.bytes.size(), offset)) return fail("identity outside readable ELF segment");
            item.identity.push_back(std::move(s));
        }
        p.images.push_back(std::move(item));
    }
    const auto& functions = root.at("functions");
    if (!Array(functions, 1, 256)) return fail("functions must contain 1..256 complete snapshots");
    std::map<std::string, size_t> functionIds; size_t totalBytes = 0;
    for (const auto& function : functions.array) {
        Function f; std::string image; uint64_t offset;
        if (!Keys(function, {"id", "image", "address", "bytes"}) || !Id(function.at("id"), f.id) ||
            !functionIds.emplace(f.id, p.functions.size()).second || !Text(function.at("image"), image) || !imageIds.count(image) ||
            !Uint(function.at("address"), f.address, UINT32_MAX) || f.address % 4 ||
            !Hex(function.at("bytes"), f.bytes, 256 * 1024) || f.bytes.size() % 4) return fail("invalid function " + f.id);
        f.image = imageIds.at(image); totalBytes += f.bytes.size();
        if (totalBytes > 512 * 1024 || !FileOffset(p.images[f.image], f.address, f.bytes.size(), offset, 5)) return fail("function outside RX segment or snapshot budget exceeded");
        for (const auto& before : p.functions) if (before.image == f.image && f.address < before.address + before.bytes.size() && before.address < f.address + f.bytes.size()) return fail("overlapping function snapshots");
        p.functions.push_back(std::move(f));
    }
    for (size_t i = 0; i < p.images.size(); ++i) if (std::none_of(p.functions.begin(), p.functions.end(), [&](const Function& f) { return f.image == i; })) return fail("every image needs a function snapshot");
    const auto& patches = root.at("patches");
    if (!Array(patches, 1, kMaxPatches)) return fail("patches must contain 1..256 instructions");
    std::set<std::string> patchIds; std::set<std::pair<size_t, uint64_t>> addresses;
    size_t veneerCount = 0;
    for (const auto& patch : patches.array) {
        Patch item; std::string function; uint64_t offset;
        if (!Keys(patch, {"id", "function", "offset", "expected", "palette", "icons", "circle"}) ||
            !Id(patch.at("id"), item.id) || !patchIds.insert(item.id).second ||
            !Text(patch.at("function"), function) || !functionIds.count(function) || !Uint(patch.at("offset"), offset, UINT32_MAX) || offset % 4 ||
            !Uint32(patch.at("expected"), item.original) || !ParseValue(patch.at("palette"), item.palette) || !ParseValue(patch.at("icons"), item.icons)) return fail("invalid instruction patch " + item.id);
        item.circle = item.icons;
        if (patch.object.count("circle") && !ParseValue(patch.at("circle"), item.circle)) return fail("invalid circle replacement");
        if (item.icons.kind == ValueKind::Veneer || item.circle.kind == ValueKind::Veneer) return fail("veneer only supported in scalar palette mode");
        item.function = functionIds.at(function); const auto& f = p.functions[item.function];
        if (offset > f.bytes.size() || f.bytes.size() - offset < 4) return fail("patch outside function snapshot");
        uint32_t word = 0; for (unsigned i = 0; i < 4; ++i) word |= uint32_t(f.bytes[offset + i]) << (8 * i);
        if (word != item.original) return fail("expected instruction differs from function snapshot: " + item.id);
        item.address = f.address + offset;
        if (!addresses.emplace(f.image, item.address).second) return fail("duplicate patch address");
        if (item.palette.kind == ValueKind::Veneer) { ++veneerCount; p.veneer.patch = p.patches.size(); }
        p.patches.push_back(item);
    }
    const auto& veneer = root.at("veneer");
    if (veneerCount > 1) return fail("schema 1 supports one scalar veneer");
    if (veneerCount) {
        if (!Keys(veneer, {"words"}) || !Array(veneer.at("words"), 2, 64)) return fail("missing scalar veneer template");
        for (const auto& word : veneer.at("words").array) { Value v; if (!ParseValue(word, v, true)) return fail("invalid veneer word"); p.veneer.words.push_back(v); }
        const auto& words = p.veneer.words;
        if (words.back().kind != ValueKind::Return || std::any_of(words.begin(), words.end() - 1, [](const Value& v) { return v.kind == ValueKind::Return; }) ||
            words.front().kind != ValueKind::Word || words.front().word != p.patches[p.veneer.patch].original) return fail("veneer must replay displaced word and end with return");
        const auto& patch = p.patches[p.veneer.patch]; const auto& f = p.functions[patch.function];
        if (patch.address + 4 >= f.address + f.bytes.size()) return fail("veneer continuation outside verified function");
    } else if (root.object.count("veneer")) return fail("unused veneer");
    const auto& icons = root.at("icons");
    if (!Keys(icons, {"prefixes", "names", "aliases", "largeIconPrefixes", "geometryBridge"}) || !Strings(icons.at("prefixes"), p.icons.prefixes, 1, 32) ||
        !Strings(icons.at("names"), p.icons.names, 1, 16) || icons.at("aliases").type != Json::Object || icons.at("aliases").object.size() > 4096) return fail("invalid icon rules");
    for (const auto& prefix : p.icons.prefixes) if (!Prefix(prefix)) return fail("icon prefix must be an absolute directory without traversal");
    if (icons.object.count("largeIconPrefixes")) {
        if (!Strings(icons.at("largeIconPrefixes"), p.icons.largeIconPrefixes, 1, 16)) return fail("invalid large icon prefixes");
        for (const auto& prefix : p.icons.largeIconPrefixes) if (!Prefix(prefix)) return fail("large icon prefix must be an absolute directory without traversal");
    }
    if (icons.object.count("geometryBridge")) {
        std::string id;
        if (p.icons.largeIconPrefixes.empty() || !Text(icons.at("geometryBridge"), id) || !functionIds.count(id))
            return fail("geometry bridge needs large icon roots and a verified function");
        p.icons.geometryBridge = functionIds.at(id);
        const auto& bridge = p.functions[p.icons.geometryBridge];
        if (p.images[bridge.image].library == "libapp.so") return fail("geometry bridge must use the native Bundle ABI, not a Dart entry point");
        if (std::any_of(p.patches.begin(), p.patches.end(), [&](const Patch& patch) { return patch.function == p.icons.geometryBridge; }))
            return fail("geometry bridge function cannot be patched");
    }
    for (const auto& name : p.icons.names) if (name != "package" && name != "class" && name != "component" && name != "qualified") return fail("unknown icon name strategy");
    if (std::none_of(p.icons.names.begin(), p.icons.names.end(), [](const std::string& s) { return s != "package"; })) return fail("at least one component icon name is required");
    for (const auto& alias : icons.at("aliases").object) {
        if (!ValidIconName(alias.first) || alias.second.type != Json::String || !ValidIconName(alias.second.text)) return fail("unsafe icon alias");
        p.icons.aliases.emplace(alias.first, alias.second.text);
    }
    result = std::move(p); error.clear(); return true;
}

uint32_t Resolve(const Value& value, uint32_t original, uint32_t background, uint32_t foreground, uint32_t branch) {
    switch (value.kind) {
        case ValueKind::Original: return original;
        case ValueKind::Word: return value.word;
        case ValueKind::BackgroundLow: return 0x52800000u | ((background & 0xffff) << 5) | value.word;
        case ValueKind::BackgroundHigh: return 0x72a00000u | ((background >> 16) << 5) | value.word;
        case ValueKind::ForegroundLow: return 0x52800000u | ((foreground & 0xffff) << 5) | value.word;
        case ValueKind::ForegroundHigh: return 0x72a00000u | ((foreground >> 16) << 5) | value.word;
        case ValueKind::Veneer: case ValueKind::Return: return branch;
    }
    return original;
}
} // namespace monoicon::profile
