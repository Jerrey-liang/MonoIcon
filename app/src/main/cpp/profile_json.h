#pragma once

// Small, bounded JSON reader shared by the app's JNI validator and native_init.
// No exceptions, coercions, duplicate keys, comments or trailing input are accepted.
#include <cstdint>
#include <map>
#include <string>
#include <vector>

namespace monoicon::profile {
struct Json {
    enum Type { Null, Bool, Number, String, Array, Object } type = Null;
    std::string text;
    std::vector<Json> array;
    std::map<std::string, Json> object;
    const Json& at(const char* key) const {
        static const Json missing;
        auto found = object.find(key);
        return found == object.end() ? missing : found->second;
    }
};

class JsonReader {
public:
    explicit JsonReader(const std::string& source) : source_(source) {}
    bool parse(Json& result, std::string& error) {
        if (source_.empty() || source_.size() > 2 * 1024 * 1024 || !value(result, 0)) {
            error = "invalid JSON at byte " + std::to_string(pos_);
            return false;
        }
        space();
        if (pos_ != source_.size()) { error = "trailing JSON at byte " + std::to_string(pos_); return false; }
        return true;
    }
private:
    const std::string& source_;
    size_t pos_ = 0, nodes_ = 0;
    void space() { while (pos_ < source_.size() && (source_[pos_] == ' ' || source_[pos_] == '\n' || source_[pos_] == '\r' || source_[pos_] == '\t')) ++pos_; }
    bool take(char c) { if (pos_ < source_.size() && source_[pos_] == c) { ++pos_; return true; } return false; }
    bool hex4(uint32_t& word) {
        word = 0;
        for (int i = 0; i < 4; ++i) {
            if (pos_ == source_.size()) return false;
            const char c = source_[pos_++];
            unsigned d = c >= '0' && c <= '9' ? c - '0' : c >= 'a' && c <= 'f' ? c - 'a' + 10 : c >= 'A' && c <= 'F' ? c - 'A' + 10 : 16;
            if (d == 16) return false;
            word = word * 16 + d;
        }
        return true;
    }
    static void utf8(std::string& out, uint32_t c) {
        if (c < 0x80) out += static_cast<char>(c);
        else if (c < 0x800) { out += static_cast<char>(0xc0 | (c >> 6)); out += static_cast<char>(0x80 | (c & 63)); }
        else if (c < 0x10000) { out += static_cast<char>(0xe0 | (c >> 12)); out += static_cast<char>(0x80 | ((c >> 6) & 63)); out += static_cast<char>(0x80 | (c & 63)); }
        else { out += static_cast<char>(0xf0 | (c >> 18)); out += static_cast<char>(0x80 | ((c >> 12) & 63)); out += static_cast<char>(0x80 | ((c >> 6) & 63)); out += static_cast<char>(0x80 | (c & 63)); }
    }
    bool string(std::string& out) {
        if (!take('"')) return false;
        while (pos_ < source_.size()) {
            unsigned char c = source_[pos_++];
            if (c == '"') return true;
            if (c < 0x20) return false;
            if (c == '\\') {
                if (pos_ == source_.size()) return false;
                c = source_[pos_++];
                switch (c) {
                    case '"': case '\\': case '/': out += static_cast<char>(c); break;
                    case 'b': out += '\b'; break; case 'f': out += '\f'; break;
                    case 'n': out += '\n'; break; case 'r': out += '\r'; break; case 't': out += '\t'; break;
                    case 'u': {
                        uint32_t u;
                        if (!hex4(u) || u == 0 || (u >= 0xdc00 && u <= 0xdfff)) return false;
                        if (u >= 0xd800 && u <= 0xdbff) {
                            uint32_t low;
                            if (!take('\\') || !take('u') || !hex4(low) || low < 0xdc00 || low > 0xdfff) return false;
                            u = 0x10000 + ((u - 0xd800) << 10) + low - 0xdc00;
                        }
                        utf8(out, u); break;
                    }
                    default: return false;
                }
            } else if (c < 0x80) out += static_cast<char>(c);
            else {
                int count = c >= 0xc2 && c <= 0xdf ? 1 : c >= 0xe0 && c <= 0xef ? 2 : c >= 0xf0 && c <= 0xf4 ? 3 : 0;
                if (!count || source_.size() - pos_ < static_cast<size_t>(count)) return false;
                uint32_t u = c & ((1u << (6 - count)) - 1);
                for (int i = 0; i < count; ++i) {
                    unsigned char next = source_[pos_++];
                    if ((next & 0xc0) != 0x80) return false;
                    u = (u << 6) | (next & 63);
                }
                if (u < (count == 1 ? 0x80u : count == 2 ? 0x800u : 0x10000u) || u > 0x10ffff || (u >= 0xd800 && u <= 0xdfff)) return false;
                utf8(out, u);
            }
        }
        return false;
    }
    bool value(Json& out, unsigned depth) {
        space();
        if (depth > 32 || ++nodes_ > 65536 || pos_ == source_.size()) return false;
        if (source_[pos_] == '"') { out.type = Json::String; return string(out.text); }
        if (take('{')) {
            out.type = Json::Object; space(); if (take('}')) return true;
            do {
                space(); std::string key; Json child;
                if (!string(key)) return false;
                space(); if (!take(':') || !value(child, depth + 1) || !out.object.emplace(key, std::move(child)).second) return false;
                space(); if (take('}')) return true;
            } while (take(','));
            return false;
        }
        if (take('[')) {
            out.type = Json::Array; space(); if (take(']')) return true;
            do {
                Json child; if (!value(child, depth + 1)) return false;
                out.array.push_back(std::move(child)); space(); if (take(']')) return true;
            } while (take(','));
            return false;
        }
        for (const char* literal : {"true", "false", "null"}) {
            const std::string text(literal);
            if (source_.compare(pos_, text.size(), text) == 0) { pos_ += text.size(); out.type = text == "null" ? Json::Null : Json::Bool; out.text = text; return true; }
        }
        out.type = Json::Number;
        const size_t start = pos_; take('-');
        if (!take('0')) {
            if (pos_ == source_.size() || source_[pos_] < '1' || source_[pos_] > '9') return false;
            while (pos_ < source_.size() && source_[pos_] >= '0' && source_[pos_] <= '9') ++pos_;
        }
        for (char part : {'.', 'e'}) {
            if (take(part) || (part == 'e' && take('E'))) {
                if (part == 'e') { if (!take('+')) take('-'); }
                size_t digits = pos_;
                while (pos_ < source_.size() && source_[pos_] >= '0' && source_[pos_] <= '9') ++pos_;
                if (pos_ == digits) return false;
            }
        }
        out.text = source_.substr(start, pos_ - start); return true;
    }
};
} // namespace monoicon::profile
