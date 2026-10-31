#pragma once

#include "hyperos4_profile.h"
#include <algorithm>
#include <string>
#include <vector>

namespace monoicon::hyos {
struct LargeIconPath { std::string source, target, name; };

inline bool SafeLargeSource(const std::string& path, const std::vector<std::string>& prefixes) {
    if (path.size() > 1000 || path.find("..") != std::string::npos ||
        path.find("//") != std::string::npos || path.find('\\') != std::string::npos ||
        std::any_of(path.begin(), path.end(), [](unsigned char c) { return c < 32 || c >= 127; })) return false;
    const bool root = std::any_of(prefixes.begin(), prefixes.end(), [&](const std::string& prefix) {
        return path.compare(0, prefix.size(), prefix) == 0 && path.size() > prefix.size();
    });
    if (!root || path.size() < 4) return false;
    const auto span = path.substr(path.size() - 4);
    return span == "/1x2" || span == "/2x1" || span == "/2x2";
}

inline bool SafeLargeTarget(const std::string& target) {
    return target.size() == 71 && target.compare(0, 7, ".large/") == 0 &&
        std::all_of(target.begin() + 7, target.end(), [](char c) {
            return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
        });
}

// A generation owns a bounded manifest of resource directories for PNGs that
// were actually rendered. Unsupported icons never enter this table.
inline bool ParseLargeIconPaths(const std::string& text, const std::vector<std::string>& prefixes,
                               std::vector<LargeIconPath>& output) {
    if (text.size() > 512 * 1024) return false;
    std::vector<LargeIconPath> parsed;
    size_t cursor = 0;
    while (cursor < text.size()) {
        const auto end = text.find('\n', cursor);
        if (end == std::string::npos || parsed.size() >= 4096) return false;
        const auto tab = text.find('\t', cursor);
        if (tab == std::string::npos || tab >= end) return false;
        const auto secondTab = text.find('\t', tab + 1);
        if (secondTab == std::string::npos || secondTab >= end) return false;
        LargeIconPath item{text.substr(cursor, tab - cursor), text.substr(tab + 1, secondTab - tab - 1),
                           text.substr(secondTab + 1, end - secondTab - 1)};
        if (!SafeLargeSource(item.source, prefixes) || !SafeLargeTarget(item.target) || !monoicon::profile::ValidIconName(item.name) ||
            std::any_of(parsed.begin(), parsed.end(), [&](const LargeIconPath& old) { return old.source == item.source; })) return false;
        parsed.push_back(std::move(item));
        cursor = end + 1;
    }
    output = std::move(parsed);
    return true;
}

// Map only the loader's directory enumeration and resource PNG requests.
// Density selection is intentionally delegated to the launcher; all accepted
// drawable qualifiers resolve to the one PNG rendered at its actual geometry.
inline std::string LargeIconRelativePath(const std::string& path, const std::vector<LargeIconPath>& entries) {
    for (const auto& entry : entries) {
        if (path == entry.source) return entry.target;
        if (path.compare(0, entry.source.size() + 1, entry.source + "/") != 0) continue;
        const auto rest = path.substr(entry.source.size());
        if (rest == "/res") return entry.target + rest;
        if (rest.compare(0, 13, "/res/drawable") != 0) return {};
        const auto slash = rest.find('/', 5);
        const auto qualifier = rest.substr(5, slash == std::string::npos ? std::string::npos : slash - 5);
        if (!std::all_of(qualifier.begin(), qualifier.end(), [](unsigned char c) {
                return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
                       (c >= '0' && c <= '9') || c == '-' || c == '_';
            })) return {};
        if (slash == std::string::npos) return entry.target + "/res/drawable";
        const auto name = rest.substr(slash + 1);
        if (name == entry.name) return entry.target + "/res/drawable/" + name;
    }
    return {};
}
}  // namespace monoicon::hyos
