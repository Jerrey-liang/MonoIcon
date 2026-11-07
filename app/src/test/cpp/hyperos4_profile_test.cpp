#include "hyperos4_profile.h"
#include "profile_json.h"
#include "profile_sha256.h"
#include "hyperos4_large_icon_paths.h"
#include "hyperos4_elf_imports.h"

#include <algorithm>
#include <array>
#include <fstream>
#include <functional>
#include <iostream>
#include <iterator>
#include <string>

namespace p = monoicon::profile;
namespace {
unsigned checks = 0;
unsigned failures = 0;

void Check(bool condition, const std::string& message) {
    ++checks;
    if (!condition) { ++failures; std::cerr << "FAIL: " << message << '\n'; }
}

std::string Quote(const std::string& input) {
    std::string out = "\"";
    const char* hex = "0123456789abcdef";
    for (unsigned char c : input) {
        if (c == '"' || c == '\\') { out += '\\'; out += static_cast<char>(c); }
        else if (c < 32) { out += "\\u00"; out += hex[c >> 4]; out += hex[c & 15]; }
        else out += static_cast<char>(c);
    }
    return out + '"';
}

// Test-only serializer: mutation cases exercise the production reader/validator
// without depending on whitespace or the formatting of the official asset.
std::string Encode(const p::Json& value) {
    if (value.type == p::Json::String) return Quote(value.text);
    if (value.type == p::Json::Number || value.type == p::Json::Bool) return value.text;
    if (value.type == p::Json::Null) return "null";
    const bool object = value.type == p::Json::Object;
    std::string out = object ? "{" : "[";
    bool first = true;
    if (object) {
        for (const auto& item : value.object) {
            if (!first) out += ',';
            first = false;
            out += Quote(item.first) + ':' + Encode(item.second);
        }
    } else {
        for (const auto& item : value.array) {
            if (!first) out += ',';
            first = false;
            out += Encode(item);
        }
    }
    return out + (object ? '}' : ']');
}

p::Json String(const std::string& text) { p::Json j; j.type = p::Json::String; j.text = text; return j; }
p::Json Number(const std::string& text) { p::Json j; j.type = p::Json::Number; j.text = text; return j; }

void Rejected(const std::string& source, const std::string& message) {
    p::Profile result;
    result.id = "previous-valid-profile";
    std::string error;
    Check(!p::Parse(source, result, error), message);
    Check(!error.empty(), message + " has a diagnostic");
    Check(result.id == "previous-valid-profile", message + " leaves prior state intact");
}

void Mutant(const p::Json& original, const std::string& name, const std::function<void(p::Json&)>& change) {
    auto mutated = original;
    change(mutated);
    Rejected(Encode(mutated), name);
}

void TestMigration(const p::Profile& profile) {
    Check(profile.id == "hyperos4-6144", "official profile id");
    Check(profile.revision == 9, "official revision");
    Check(profile.refresh.action == "com.xiaomi.market.ACTION_HD_ICON_UPDATE" &&
          profile.refresh.packageExtra == "packageName", "verified package refresh transport");
    Check(profile.match.versionCodes == std::vector<std::string>{"801026144"}, "6144 version code");
    Check(profile.match.versionNames == std::vector<std::string>{"RELEASE-8.01.02.6144-260818-08251107-R"}, "6144 version name");
    Check(profile.match.sdkMin == 37 && profile.match.sdkMax == 37, "verified SDK range");
    Check(profile.match.systemVersions == std::vector<std::string>{"OS4.0.0.3.XAACNXM"}, "verified exact system version");
    Check(profile.images.size() == 2, "both launcher images migrated");
    Check(profile.functions.size() == 38, "rendering, refresh and native scalar geometry ABI snapshots");
    Check(profile.patches.size() == 28, "migration and folder entry/MAML preview fixes");
    if (profile.images.size() != 2 || profile.functions.size() != 38 || profile.patches.size() != 28) return;
    Check(profile.images[0].sha256 == "60c8c7a47634b3474dd9e1052f996473882456e87b0f37a2bfe1fe377c413ce9", "app ELF digest retained");

    // Frozen pre-refactor production snapshots, verified against Git HEAD once
    // during migration. These assertions remain useful after the old headers
    // disappear and do not depend on Git or a local proprietary launcher APK.
    struct FunctionEvidence { const char* id; uint64_t address; size_t size; const char* sha256; };
    const FunctionEvidence evidence[] = {
        {"support",0x1a04848,516,"d4b7b74ad7eca65d2d388faf1d61f9e2b81836e9843f5855f8d52545110181f8"},
        {"settings",0x194ad28,624,"53b657b0b8230fb869e6cdb4019ee51d8f86c9a84f4d13adbb379e2fca96b7ef"},
        {"foreground",0x8ceef0,256,"1231785514d3468b029f36329224cc0836d5eb8d126d3f399ed794101cf85ed7"},
        {"settings_changed",0x194a6f0,356,"eac08cafbda16a9bf4b903524bab6222a4382d6d13d25cb29774b063ba1dc15a"},
        {"widget",0x12c8e14,344,"0bd46d553f3f15782e52549b8dd867a6f4a308d014d2eb6916ff4007f1da4a7f"},
        {"fancy",0x191a164,1084,"7422221c6df8a3fd286df75e35d44a8f624f7d126dba6987b6a92adff6cb66cd"},
        {"animating",0x191a624,1084,"e3c4869c6bdb03ebf1bbda1933314f1a65b142d7db4d7f01eaa072b55bd34eae"},
        {"maml",0x191aefc,1084,"926ccf35071b252619fc027203bc345a694654d97118085e5ce7c5b2b19ea79c"},
        {"theme",0x1aad3e0,392,"2c1e4d7263831bde9f8eeaaad686ae9722ed25974ac615ea940cec5608fb2c45"},
        {"aihd",0x1aaaca0,568,"828d302386909a245db8fa4bfd533764a1bcf4a9121665c33ebe74d7f3957b21"},
        {"mod",0x1aab660,516,"698ec400a87a03e2abc5bc506b4dceb15e698adc8afffac366c3e8caf131ba36"},
        {"mod_style",0x17e743c,64,"2ce94ba70a806181f0062ce655fdb8345e99a58fb5cda2c561dc54cbf74fd5e4"},
        {"theme_style",0x1aab284,248,"257c539e49ff73dc22ed93194ccb56aa9189c4767fdba0f821a7b04a13facbd4"},
        {"observer",0x5ae5b0,2784,"92bf76073ee2f6c381eaeee9e6d80ecbbf2f2a430662de0fef9a58f6db570ae3"},
        // New refresh evidence comes from the same SHA-verified original ELF.
        {"hd_icon_update_callback",0x1a6e398,384,"81f60b728c2c500aee3ba201cf28a4716d2b2517e8143c7aec9ca52c70664eaa"},
        {"icon_cache_v2_clear",0x14f0838,188,"7849592f28ec8521543c9ca0db0cfd1bd465d2e259516893be6fb7036e68edc0"},
        {"animation_capture_fill",0x1927940,452,"f63f59c4e5f8721850306584384adedf059eb69d62c4e86e9634a9764ae4bbd4"},
        {"animation_bitmap_fill",0x19ccc28,2352,"eac5e59121c7924cc744c4d6b48e4d9b038f6c88dfd4eed65713f07cd00fb3a9"},
        {"drawable_widget_update",0x12c83ac,296,"2b213fff2f6e9ccb94e4531603cef37a67f81557cf2eed927a58266b493ba99a"},
        {"hd_package_refresh",0x1a6e518,1160,"8d087afc3c2b7f050934311031229e74e936a69e7917761138b962f4601fc108"},
        {"folder_preview_refresh",0x194aa98,612,"6f70b989394d379f2da90fcd7544652f0498f29cdbf49bf6301983e9708f70de"},
        {"folder_default_icon",0x13d2354,1004,"5db8746cb305958841324941954f66222287554b67d9c2989fd29b4d57c686c4"},
        {"folder_update_ui",0x93cad8,196,"1f3928fb57dee292ce7eb25ad6836b4e5213c71c8b84511247e99342d73bd323"},
        {"folder_reload_previews",0x90f51c,780,"ead6619c8b04e05a4f2c059752e518d2480db41ce3739e64034dc08ff328bd33"},
        {"folder_load_items",0x90f828,484,"f4302813a82f3c3c35ce2aada2ec94d3b4a77e238399aec2e9e89c83924620b0"},
        {"folder_run_load",0x90fa0c,1572,"29cdf4117f950598d96b885fa12cc61a77349d6b812ce21b7d2692a606e8e538"},
        {"folder_maml_preview",0x13d5970,1520,"5311e32eda68e1b17b4c64276972f51cd764a4c509420b497a50459b28e682dd"},
        {"folder_icon_item",0x13d11cc,1700,"654c85455b029a33de8ede42158077286681038b12af3ff6e09a51957649d007"},
        {"folder_icon_dispatch",0x13d4534,308,"91329aa8727f16a36a05730f580ea253a76ce8798c615cc2cb7b5696eb266133"},
        {"large_geometry_bridge",0x64ae9c,2916,"ae3ea3704750609d812c3170955c7667b424ccd05165fc8989182d954daec104"},
        {"large_geometry_caller",0x4c39a0,736,"32710892c268fc9508486f87da78ffe99d1276465b8a8dd746a1f36ab9fb6fc1"},
        {"bundle_double_abi",0xa53470,80,"09903366b5133ea861d1e441a1753101362b47c63f540eb091e9bd61c83a7e25"},
        {"bundle_float_abi",0xa53588,92,"a1cb571bcc59237917e0cbeb81c622811dae0673db86f7cbb7d7ab2d60e69aac"},
        {"geometry_width",0x1a42524,480,"55dc1a45b8bf2cc76a4b2d491eb4984fc31b3c5246f7fec3fcdd299588f416d0"},
        {"geometry_height",0x1a423e4,320,"be6ac69737b527d4ffdf4a058e0116a0725b7e51ae0d0e9207a8b1ff8fccf35e"},
        {"geometry_scales",0x1a49e7c,472,"ce5d1583d01230b3383e07b26e27b88c360b0e229ccb4c68a51f14b0735f1683"},
        {"geometry_scale_group",0x1513b14,384,"383d334fb6a50b81744dbaeaaedac09574d86ccd8c306109e3814c2d3691cc82"},
        {"geometry_icon_size",0x14e2ae0,56,"ff80e97e2e7dbb30d414f43687960483a3858738fd8ed6774c58b21ac867c898"},
    };
    size_t bytes = 0;
    for (const auto& entry : evidence) {
        auto found = std::find_if(profile.functions.begin(), profile.functions.end(), [&](const p::Function& f) { return f.id == entry.id; });
        Check(found != profile.functions.end(), std::string("snapshot exists: ") + entry.id);
        if (found == profile.functions.end()) continue;
        p::Sha256 hash; hash.update(found->bytes.data(), found->bytes.size());
        Check(found->address == entry.address && found->bytes.size() == entry.size && hash.finish() == entry.sha256, std::string("snapshot identity: ") + entry.id);
        bytes += found->bytes.size();
    }
    Check(bytes == 28464, "snapshot byte total");

    // Expected values are the old transaction's outputs for independent scalar,
    // exported-PNG, and circle modes. No profile expression is used to derive
    // this oracle. The branch value substitutes the runtime allocated veneer.
    const uint32_t bg = 0xff6750a4, fg = 0xff123456, branch = 0x14012345;
    const uint64_t addresses[] = {0x1a04944,0x194adc4,0x194ae24,0x194ae28,0x194ae2c,0x8cefb8,0x194a79c,0x12c8f4c,0x191a184,0x191a644,0x191af1c,0x1aad408,0x1aaacc4,0x1aab688,0x1aab6d4,0x17e743c,0x1aab2b8,0x1a6e424};
    const uint32_t scalar[] = {0x14000013,0xaa1003e4,0x528a1481,0x72bfece1,0xd503201f,branch,0xd503201f,0xd503201f,0xf85f03a1,0xf85f03a1,0xf85f03a1,0xf85e83a5,0xf85703a0,0xf9403f40,0x94000280,0xa9bf79fd,0x94000031,0x97b6f0b3};
    const uint32_t icons[] = {0x36200261,0xaa1103e4,0x93417c61,0x36000043,0xf8407061,0xf85d83a2,0xd503201f,0xd503201f,0x14000104,0x14000104,0x14000104,0x1400000a,0x14000004,0x1400000e,0x1400002d,0xa9bf79fd,0x94000031,0x97ea0905};
    for (size_t i = 0; i < 18; ++i) {
        const auto& patch = profile.patches[i];
        Check(patch.address == addresses[i], "migrated patch address " + patch.id);
        Check(p::Resolve(patch.palette,patch.original,bg,fg,branch) == scalar[i], "scalar output " + patch.id);
        Check(p::Resolve(patch.icons,patch.original,bg,fg,branch) == icons[i], "PNG output " + patch.id);
        const uint32_t circle = i == 15 ? 0x140b0f92 : i == 16 ? 0x14000017 : icons[i];
        Check(p::Resolve(patch.circle,patch.original,bg,fg,branch) == circle, "circle output " + patch.id);
    }
    const uint64_t alphaSites[] = {0x1927acc,0x1927ae0,0x19cce14,0x19cce28};
    const uint32_t fillCalls[] = {0x97ce08de,0x97ce0855,0x97cb740c,0x97cb7383};
    for (size_t i = 0; i < 4; ++i) {
        const auto& patch = profile.patches[18 + i];
        Check(patch.address == alphaSites[i] && patch.original == fillCalls[i], "animation fill call evidence");
        Check(p::Resolve(patch.circle,patch.original,bg,fg,branch) == 0xd503201f, "circle preserves source alpha");
        Check(p::Resolve(patch.icons,patch.original,bg,fg,branch) == fillCalls[i] &&
              p::Resolve(patch.palette,patch.original,bg,fg,branch) == fillCalls[i], "other modes retain edge fill");
    }
    // Retain the identical-drawable fast path. Only a changed old future proxy
    // must stop bypassing the normal dispose/evict/render-and-setState path.
    Check(std::none_of(profile.patches.begin(), profile.patches.end(), [](const p::Patch& patch) {
        return patch.address == 0x12c846c;
    }), "identical drawable does not trigger repeated renders");
    const uint64_t reloadSites[] = {0x12c847c,0x1a6e994,0x13d23b8};
    const uint32_t reloadOriginal[] = {0x54000200,0x97b6ef57,0x372007c2};
    const uint32_t reloadWords[] = {0xd503201f,0x97fb7041,0x1400003e};
    for (size_t i = 0; i < 3; ++i) {
        const auto& patch = profile.patches[22 + i];
        Check(patch.address == reloadSites[i] && patch.original == reloadOriginal[i], "widget/folder patch identity");
        Check(p::Resolve(patch.icons,patch.original,bg,fg,branch) == reloadWords[i] &&
              p::Resolve(patch.circle,patch.original,bg,fg,branch) == reloadWords[i], "both PNG modes reload widget/folder");
        Check(p::Resolve(patch.palette,patch.original,bg,fg,branch) == reloadOriginal[i], "scalar mode retains widget/folder behavior");
    }
    Check(0x1a6e994 + (int64_t(int32_t(0x97fb7041u << 6)) >> 4) == 0x194aa98,
          "folder call targets the complete verified refresh function");
    Check(0x13d23b8 + (int64_t(int32_t(0x1400003eu << 6)) >> 4) == 0x13d24b0,
          "folder PNG route stays inside its verified function");
    const uint64_t folderSites[] = {0x194ac30,0x13d5970,0x13d5974};
    const uint32_t folderOriginal[] = {0x97bc2f40,0xa9bf79fd,0xaa0f03fd};
    const uint32_t folderWords[] = {0x97bfc7aa,0xaa0603e0,0xd65f03c0};
    for (size_t i = 0; i < 3; ++i) {
        const auto& patch = profile.patches[25 + i];
        Check(patch.address == folderSites[i] && patch.original == folderOriginal[i], "folder reload/fallback patch identity");
        Check(p::Resolve(patch.icons,patch.original,bg,fg,branch) == folderWords[i] &&
              p::Resolve(patch.circle,patch.original,bg,fg,branch) == folderWords[i], "PNG modes reload entries and return MAML fallback");
        Check(p::Resolve(patch.palette,patch.original,bg,fg,branch) == folderOriginal[i], "scalar mode retains original folder calls and MAML prologue");
    }
    Check(0x194ac30 + (int64_t(int32_t(0x97bfc7aau << 6)) >> 4) == 0x93cad8,
          "folder refresh targets the native content update method");
    const auto wordAt = [&](uint64_t address) {
        for (const auto& f : profile.functions) {
            if (f.image != 0 || address < f.address || address - f.address + 4 > f.bytes.size()) continue;
            const size_t offset = address - f.address;
            uint32_t word = 0;
            for (size_t i = 0; i < 4; ++i) word |= uint32_t(f.bytes[offset + i]) << (i * 8);
            return word;
        }
        Check(false, "missing ABI evidence instruction");
        return uint32_t(0);
    };
    Check(wordAt(0x194ac28) == 0xf85903a1, "folder controller is the replacement call receiver");
    Check(wordAt(0x194ac34) == 0xf85983a2, "caller reloads its iterator after the void replacement");
    Check(wordAt(0x13d1444) == 0x940003c4 && wordAt(0x13d144c) == 0xb802b040,
          "caller stores the normal preview widget in captured field 0x2b");
    Check(wordAt(0x13d4590) == 0xb842b006 && wordAt(0x13d4594) == 0x8b1c80c6 &&
          wordAt(0x13d45b0) == 0x940004f0, "x6 carries the normal widget into MAML preview");
    // V2 invalidation remains in the explicit update callback. The next word
    // reloads its action, so the substituted void log return is never consumed.
    const auto callback = std::find_if(profile.functions.begin(), profile.functions.end(),
        [](const p::Function& f) { return f.id == "hd_icon_update_callback"; });
    if (callback != profile.functions.end() && callback->bytes.size() == 384) {
        uint32_t next = 0;
        for (size_t i = 0; i < 4; ++i) next |= uint32_t(callback->bytes[0x90 + i]) << (i * 8);
        Check(next == 0xf85e03a0, "HD callback reloads action after the substituted call");
    }
    Check(profile.veneer.words.size() == 9 && profile.veneer.patch == 5, "foreground veneer preserved");
    if (profile.veneer.words.size() == 9) {
        const uint32_t words[] = {0xf85d83a2,0xf85f03a3,0x91421760,0xf944fc00,0xeb00007f,0x54000061,0x52868ac2,0x72bfe242,branch};
        for (size_t i = 0; i < 9; ++i) Check(p::Resolve(profile.veneer.words[i],0,bg,fg,branch) == words[i], "foreground veneer word " + std::to_string(i));
    }
    Check(profile.icons.prefixes == std::vector<std::string>{"/data/system/theme_magic/customized_icons/","/product/media/theme/miui_mod_icons/"}, "icon directories preserved");
    Check(profile.icons.names == std::vector<std::string>{"qualified","class","package"}, "icon candidate order preserved");
    Check(profile.icons.largeIconPrefixes == std::vector<std::string>{"/data/system/theme/large_icons/"}, "large icon root is explicit");
    Check(profile.icons.geometryBridge < profile.functions.size() && profile.functions[profile.icons.geometryBridge].id == "large_geometry_bridge", "geometry entry point is a verified function reference");
    Check(profile.icons.aliases.size() == 13, "all launcher aliases preserved");
    auto weather = profile.icons.aliases.find("com.miui.weather2.png");
    Check(weather != profile.icons.aliases.end() && weather->second == "com.miui.weather.png", "weather native alias preserved");

    uint64_t offset = 0;
    Check(p::FileOffset(profile.images[1],0x5ae5b0,2784,offset,5) && offset == 0x5aa5b0, "launcher VMA translated through its PT_LOAD offset");
    Check(!p::FileOffset(profile.images[0],0x1ab9b7c,8,offset,5), "range spanning RX end is refused");
    Check(!p::FileOffset(profile.images[0],UINT64_MAX-4,8,offset,5), "overflowed address refused");
    Check(!p::FileOffset(profile.images[0],0,4,offset,5), "non-executable data cannot be a patch target");
}

void TestInvalidProfiles(const p::Json& json) {
    Mutant(json,"refresh shell syntax rejected",[](p::Json& j) { j.object["refresh"].object["action"] = String("com.example.UPDATE;id"); });
    Mutant(json,"refresh extra newline rejected",[](p::Json& j) { j.object["refresh"].object["packageExtra"] = String("packageName\nid"); });
    Mutant(json,"refresh extra missing",[](p::Json& j) { j.object["refresh"].object.erase("packageExtra"); });
    Mutant(json,"unknown refresh field rejected",[](p::Json& j) { j.object["refresh"].object["command"] = String("id"); });
    Mutant(json,"refresh null rejected",[](p::Json& j) { j.object["refresh"] = p::Json{}; });
    auto legacy = json;
    legacy.object.erase("refresh");
    p::Profile legacyProfile; std::string legacyError;
    Check(p::Parse(Encode(legacy), legacyProfile, legacyError) && legacyProfile.refresh.action.empty(),
          "legacy profiles without refresh remain readable");
    legacy.object["match"].object.erase("systemVersions");
    Check(p::Parse(Encode(legacy), legacyProfile, legacyError) && legacyProfile.match.systemVersions.empty(),
          "legacy profiles without system versions remain readable for manual approval");
    Mutant(json,"empty system versions rejected",[](p::Json& j) { j.object["match"].object["systemVersions"].array.clear(); });
    Mutant(json,"null system versions rejected",[](p::Json& j) { j.object["match"].object["systemVersions"] = p::Json{}; });
    Mutant(json,"scalar system versions rejected",[](p::Json& j) { j.object["match"].object["systemVersions"] = String("OS4.0.0.3.XAACNXM"); });
    Mutant(json,"empty system version rejected",[](p::Json& j) { j.object["match"].object["systemVersions"].array[0] = String(""); });
    Mutant(json,"duplicate system version rejected",[](p::Json& j) { auto& versions = j.object["match"].object["systemVersions"].array; versions.push_back(versions[0]); });
    Mutant(json,"numeric system version rejected",[](p::Json& j) { j.object["match"].object["systemVersions"].array[0] = Number("4"); });
    Mutant(json,"unknown schema",[](p::Json& j) { j.object["schema"] = Number("2"); });
    Mutant(json,"unknown safety field",[](p::Json& j) { j.object["unsafeSkipValidation"] = Number("1"); });
    Mutant(json,"schema exponent is not an integer",[](p::Json& j) { j.object["schema"] = Number("1e0"); });
    Mutant(json,"revision overflow",[](p::Json& j) { j.object["revision"] = Number("4294967296"); });
    Mutant(json,"negative revision",[](p::Json& j) { j.object["revision"] = Number("-1"); });
    Mutant(json,"package outside driver scope",[](p::Json& j) { j.object["match"].object["package"] = String("com.example.other"); });
    Mutant(json,"noncanonical version",[](p::Json& j) { j.object["match"].object["versionCodes"].array[0] = String("0801026144"); });
    Mutant(json,"version integer overflow",[](p::Json& j) { j.object["match"].object["versionCodes"].array[0] = String("9223372036854775808"); });
    Mutant(json,"empty environment constraints",[](p::Json& j) { j.object["match"].object["properties"].array.clear(); });
    Mutant(json,"writable property rejected",[](p::Json& j) { j.object["match"].object["properties"].array[0].object["name"] = String("persist.test"); });
    Mutant(json,"SDK inversion",[](p::Json& j) { j.object["match"].object["sdkMax"] = Number("36"); });
    Mutant(json,"library traversal",[](p::Json& j) { j.object["images"].array[0].object["library"] = String("lib../bad.so"); });
    Mutant(json,"invalid digest",[](p::Json& j) { j.object["images"].array[0].object["sha256"] = String(std::string(64,'g')); });
    Mutant(json,"duplicate image id",[](p::Json& j) { auto& a=j.object["images"].array; a[1].object["id"] = a[0].at("id"); });
    Mutant(json,"writable executable segment",[](p::Json& j) { j.object["images"].array[0].object["segments"].array[1].object["flags"] = Number("7"); });
    Mutant(json,"segment past file end",[](p::Json& j) { j.object["images"].array[0].object["segments"].array[1].object["fileSize"] = String("0xffffffff"); });
    Mutant(json,"overlapping segments",[](p::Json& j) { auto& a=j.object["images"].array[0].object["segments"].array; a.push_back(a[0]); });
    Mutant(json,"identity outside mapping",[](p::Json& j) { j.object["images"].array[0].object["identity"].array[0].object["address"] = String("0xffffffff"); });
    Mutant(json,"unaligned function",[](p::Json& j) { j.object["functions"].array[0].object["address"] = String("0x1a04849"); });
    Mutant(json,"function outside RX",[](p::Json& j) { j.object["functions"].array[0].object["address"] = String("0x1000"); });
    Mutant(json,"odd snapshot",[](p::Json& j) { j.object["functions"].array[0].object["bytes"].text += "0"; });
    Mutant(json,"snapshot has partial word",[](p::Json& j) { j.object["functions"].array[0].object["bytes"].text += "00"; });
    Mutant(json,"duplicate function id",[](p::Json& j) { auto& a=j.object["functions"].array; a[1].object["id"] = a[0].at("id"); });
    Mutant(json,"overlapping snapshots",[](p::Json& j) { auto f=j.object["functions"].array[0]; f.object["id"] = String("overlap"); j.object["functions"].array.push_back(f); });
    Mutant(json,"unknown function",[](p::Json& j) { j.object["patches"].array[0].object["function"] = String("missing"); });
    Mutant(json,"patch past function end",[](p::Json& j) { j.object["patches"].array[0].object["offset"] = String("0x204"); });
    Mutant(json,"patch offset overflow",[](p::Json& j) { j.object["patches"].array[0].object["offset"] = String("0xffffffffffffffff"); });
    Mutant(json,"patch alignment",[](p::Json& j) { j.object["patches"].array[0].object["offset"] = String("0xfd"); });
    Mutant(json,"instruction mismatch",[](p::Json& j) { j.object["patches"].array[0].object["expected"] = String("0xd503201f"); });
    Mutant(json,"duplicate patch address",[](p::Json& j) { auto patch=j.object["patches"].array[0]; patch.object["id"] = String("duplicate_address"); j.object["patches"].array.push_back(patch); });
    Mutant(json,"missing mode is not inferred",[](p::Json& j) { j.object["patches"].array[0].object.erase("icons"); });
    Mutant(json,"unknown operation",[](p::Json& j) { j.object["patches"].array[0].object["palette"] = String("run-shell"); });
    Mutant(json,"replacement word overflow",[](p::Json& j) { j.object["patches"].array[0].object["palette"].object["word"] = String("0x100000000"); });
    Mutant(json,"invalid register",[](p::Json& j) { j.object["patches"].array[2].object["palette"].object["register"] = Number("31"); });
    Mutant(json,"unknown scalar operand",[](p::Json& j) { j.object["patches"].array[2].object["palette"].object["value"] = String("background-middle"); });
    Mutant(json,"veneer displaced word changed",[](p::Json& j) { j.object["veneer"].object["words"].array[0].object["word"] = String("0xd503201f"); });
    Mutant(json,"missing veneer return",[](p::Json& j) { j.object["veneer"].object["words"].array.back() = String("original"); });
    Mutant(json,"early veneer return",[](p::Json& j) { j.object["veneer"].object["words"].array[2] = String("return"); });
    Mutant(json,"PNG veneer forbidden",[](p::Json& j) { j.object["patches"].array[5].object["icons"] = String("veneer"); });
    Mutant(json,"prefix traversal",[](p::Json& j) { j.object["icons"].object["prefixes"].array[0] = String("/data/../system/"); });
    Mutant(json,"prefix must be a directory",[](p::Json& j) { j.object["icons"].object["prefixes"].array[0] = String("/data/system"); });
    Mutant(json,"large prefix traversal",[](p::Json& j) { j.object["icons"].object["largeIconPrefixes"].array[0] = String("/data/../system/"); });
    Mutant(json,"unknown geometry function",[](p::Json& j) { j.object["icons"].object["geometryBridge"] = String("unknown"); });
    Mutant(json,"Dart geometry entry rejected",[](p::Json& j) { j.object["icons"].object["geometryBridge"] = String("geometry_width"); });
    Mutant(json,"mutable geometry entry rejected",[](p::Json& j) {
        auto& patch = j.object["patches"].array[0];
        patch.object["function"] = String("large_geometry_bridge");
        patch.object["offset"] = String("0x0");
        patch.object["expected"] = String("0xd10643ff");
    });
    Mutant(json,"unknown candidate algorithm",[](p::Json& j) { j.object["icons"].object["names"].array[0] = String("eval"); });
    Mutant(json,"alias key traversal",[](p::Json& j) { j.object["icons"].object["aliases"].object["../bad.png"] = String("good.png"); });
    Mutant(json,"alias value traversal",[](p::Json& j) { j.object["icons"].object["aliases"].object["good.png"] = String("../bad.png"); });

    std::string serialized = Encode(json);
    Rejected("{\"schema\":1," + serialized.substr(1), "duplicate JSON key");
    Rejected("{\"\\u0073chema\":1," + serialized.substr(1), "escaped duplicate JSON key");
    Rejected(serialized + "{}", "trailing JSON object");
    Rejected(serialized.substr(0, serialized.size()-1), "truncated JSON");
    Rejected(std::string(p::kMaxProfileBytes + 1,' '), "oversized profile");
    Rejected("{\"name\":\"\\ud800\"}", "unpaired unicode surrogate");
    Rejected(std::string("{\"name\":\"") + char(0xc0) + char(0x80) + "\"}", "overlong UTF-8");
    Rejected(std::string(34,'[') + "0" + std::string(34,']'), "excessive nesting");

    auto extended = json;
    extended.object["x-user-notes"] = String("A custom profile may carry descriptive extension data.");
    extended.object["name"] = String("Custom \xf0\x9f\x8e\xa8 profile");
    p::Profile accepted; std::string error;
    Check(p::Parse(Encode(extended),accepted,error), "UTF-8 metadata and x- extensions remain supported");
    auto noLargeIcons = json;
    noLargeIcons.object["icons"].object.erase("largeIconPrefixes");
    noLargeIcons.object["icons"].object.erase("geometryBridge");
    Check(p::Parse(Encode(noLargeIcons), accepted, error) && accepted.icons.largeIconPrefixes.empty(), "older profiles retain ordinary icon behavior");
}

void TestLargeIconPaths() {
    namespace h = monoicon::hyos;
    const std::vector<std::string> roots{"/data/system/theme/large_icons/"};
    const std::string base = roots[0] + "0/resources/calendar";
    const std::string target = ".large/" + std::string(64, 'a');
    std::vector<h::LargeIconPath> paths;
    for (const auto* span : {"2x1", "1x2", "2x2"}) {
        const std::string source = base + "/" + span;
        const std::string manifest = source + "\t" + target + "\tcom.example.png\n";
        Check(h::ParseLargeIconPaths(manifest, roots, paths), "valid large resource ownership manifest");
        Check(h::LargeIconRelativePath(source, paths) == target, "type discovery enumerates exported res directory");
        Check(h::LargeIconRelativePath(source + "/res", paths) == target + "/res", "res directory maps");
        for (const auto* qualifier : {"drawable", "drawable-sw720dp-xxhdpi", "drawable-xhdpi"}) {
            Check(h::LargeIconRelativePath(source + "/res/" + qualifier + "/com.example.png", paths) ==
                target + "/res/drawable/com.example.png", "density candidate keeps exact size-specific PNG");
        }
        Check(h::LargeIconRelativePath(source + "/res/drawable/com.other.png", paths).empty(), "unowned package falls through");
        Check(h::LargeIconRelativePath(source + "/res/drawable/../com.example.png", paths).empty(), "nested or traversing filename falls through");
        Check(h::LargeIconRelativePath(base + "/1x1", paths).empty(), "normal resource is not redirected");
        Check(!h::ParseLargeIconPaths(manifest + manifest, roots, paths), "ambiguous duplicate ownership is rejected");
        Check(!h::ParseLargeIconPaths(manifest.substr(0, manifest.size() - 1), roots, paths), "truncated manifest is rejected");
        Check(!h::ParseLargeIconPaths(source + "\t../outside\tcom.example.png\n", roots, paths), "target traversal is rejected");
    }
    Check(!h::ParseLargeIconPaths("/other/2x1\t" + target + "\tcom.example.png\n", roots, paths), "only profile-authorized roots map");
    Check(!h::ParseLargeIconPaths(base + "/../2x1\t" + target + "\tcom.example.png\n", roots, paths), "source traversal is rejected");
    Check(h::ParseLargeIconPaths("", roots, paths) && paths.empty(), "missing export leaves original type discovery intact");
    Check(h::LargeIconRelativePath(base + "/2x1", paths).empty(), "missing PNG has no synthetic resource directory");
}

void TestImportedFunctions() {
    std::vector<uint8_t> bytes(4096);
    auto put = [&](size_t at, uint64_t value) {
        for (unsigned i = 0; i < 8; ++i) bytes[at + i] = static_cast<uint8_t>(value >> (8 * i));
    };
    const std::array<const char*, 3> names{"Bundle_get_f64", "Bundle_get_f32", "Bundle_drop"};
    constexpr char dependency[] = "libhyper_os_schema_public.so";
    const std::array<std::array<uint64_t, 2>, 9> dynamic{{
        {{1, 1}}, {{5, 0x300}}, {{10, 256}}, {{6, 0x500}}, {{11, 24}},
        {{23, 0x700}}, {{2, 72}}, {{20, 7}}, {{0, 0}},
    }};
    for (size_t i = 0; i < dynamic.size(); ++i) {
        put(0x100 + i * 16, dynamic[i][0]); put(0x108 + i * 16, dynamic[i][1]);
    }
    std::memcpy(bytes.data() + 0x301, dependency, sizeof(dependency));
    for (size_t i = 0; i < names.size(); ++i) {
        const uint64_t name = 64 + i * 32, symbol = 0x500 + (i + 1) * 24;
        std::memcpy(bytes.data() + 0x300 + name, names[i], std::strlen(names[i]) + 1);
        put(symbol, name); bytes[symbol + 4] = 0x12;
        put(0x700 + i * 24, 0x900 + i * 8);
        put(0x708 + i * 24, (uint64_t(i + 1) << 32) | 1026);
    }
    const auto original = bytes;
    bool allowWritable = true;
    auto read = [&](uint64_t address, void* output, size_t size, uint32_t flags) {
        if (address > bytes.size() || size > bytes.size() - address ||
            (flags == 6 && (!allowWritable || address < 0x900 || address + size > 0x918))) return false;
        std::memcpy(output, bytes.data() + address, size);
        return true;
    };
    std::array<uint64_t, 3> slots{};
    auto parse = [&](uint64_t size = 144) {
        return monoicon::hyos::ReadImportedFunctionSlots(0x100, size, dependency, names, read, slots);
    };
    Check(parse() && slots == std::array<uint64_t, 3>{0x900, 0x908, 0x910}, "ELF imports resolved by name without fixed offsets");
    auto reject = [&](const char* reason, const std::function<void()>& mutate) {
        bytes = original; slots = {1, 2, 3}; mutate();
        Check(!parse() && slots == std::array<uint64_t, 3>{1, 2, 3}, reason);
    };
    reject("missing expected DT_NEEDED is rejected", [&] { bytes[0x301] = 'x'; });
    reject("duplicate dynamic field is rejected", [&] { put(0x110, 10); });
    reject("out-of-image string table is rejected", [&] { put(0x118, 4090); });
    reject("oversized string table is bounded", [&] { put(0x128, 2 * 1024 * 1024 + 1); });
    reject("unsupported symbol record size is rejected", [&] { put(0x148, 16); });
    reject("REL instead of RELA is rejected", [&] { put(0x178, 17); });
    reject("unbounded relocation table is rejected", [&] { put(0x168, 16385 * 24); });
    reject("missing required jump slot is rejected", [&] { put(0x708, (uint64_t(1) << 32) | 1025); });
    reject("symbol index is bounded", [&] { put(0x708, (uint64_t(65536) << 32) | 1026); });
    reject("nonfunction import is rejected", [&] { bytes[0x51c] = 0x11; });
    reject("defined rather than imported symbol is rejected", [&] { bytes[0x51e] = 1; });
    reject("jump-slot addend is rejected", [&] { put(0x710, 8); });
    reject("unaligned jump slot is rejected", [&] { put(0x700, 0x901); });
    reject("duplicate slot ownership is rejected", [&] { put(0x718, 0x900); });
    reject("slot outside writable PT_LOAD is rejected", [&] { put(0x700, 0x800); });
    bytes = original;
    allowWritable = false;
    Check(!parse(), "reader PT_LOAD protection check is enforced");
    allowWritable = true;
    Check(!parse(128), "unterminated dynamic table is rejected");
    Check(!parse(65552), "dynamic table scan is bounded");
}

void TestOriginalGeometryImports(const p::Profile& profile, const char* path) {
    std::ifstream input(path, std::ios::binary);
    const std::vector<uint8_t> bytes{std::istreambuf_iterator<char>(input), std::istreambuf_iterator<char>()};
    const auto& spec = profile.images[profile.functions[profile.icons.geometryBridge].image];
    p::Sha256 hash; hash.update(bytes.data(), bytes.size());
    const bool matching = bytes.size() == spec.fileSize && hash.finish() == spec.sha256;
    Check(matching, "optional original launcher ELF matches profile SHA-256");
    if (!matching || bytes.size() < 64) return;
    auto word = [&](size_t at) {
        uint64_t value = 0;
        if (at > bytes.size() || bytes.size() - at < 8) return value;
        for (unsigned i = 0; i < 8; ++i) value |= uint64_t(bytes[at + i]) << (i * 8);
        return value;
    };
    const uint64_t phoff = word(32);
    const unsigned phnum = bytes[56] | (unsigned(bytes[57]) << 8);
    uint64_t dynamicAddress = 0, dynamicSize = 0;
    for (unsigned i = 0; i < phnum; ++i) {
        const size_t at = static_cast<size_t>(phoff + uint64_t(i) * 56);
        if (at > bytes.size() || bytes.size() - at < 56) return;
        if (static_cast<uint32_t>(word(at)) == 2) { dynamicAddress = word(at + 16); dynamicSize = word(at + 32); }
    }
    auto read = [&](uint64_t address, void* out, size_t size, uint32_t flags) {
        uint64_t offset = 0;
        if (!p::FileOffset(spec, address, size, offset, flags) || offset > bytes.size() || size > bytes.size() - offset) return false;
        std::memcpy(out, bytes.data() + offset, size); return true;
    };
    std::array<uint64_t, 3> slots{};
    Check(monoicon::hyos::ReadImportedFunctionSlots(dynamicAddress, dynamicSize,
        "libhyper_os_schema_public.so", {"Bundle_get_f64", "Bundle_get_f32", "Bundle_drop"}, read, slots) &&
        slots == std::array<uint64_t, 3>{0xe652f0, 0xe63660, 0xe62458},
        "original 6144 import parser agrees with independently inspected ELF relocations");
}

void TestScalarsAndNames() {
    for (auto value : {0u,0x0000ffffu,0xffff0000u,0xffffffffu,0x80000000u}) {
        const uint32_t low = p::Resolve({p::ValueKind::BackgroundLow,30},0,value,0,0);
        const uint32_t high = p::Resolve({p::ValueKind::BackgroundHigh,30},0,value,0,0);
        Check((low & 31) == 30 && (high & 31) == 30, "register preserved in ARGB encoding");
        Check((((low >> 5) & 0xffff) | (((high >> 5) & 0xffff) << 16)) == value, "unsigned ARGB is lossless");
    }
    Check(p::Resolve({p::ValueKind::Original,0},0xfedcba98,0,0,0) == 0xfedcba98, "original-word expression preserves all bits");
    Check(p::Resolve({p::ValueKind::Word,0xdeadbeef},0,0,0,0) == 0xdeadbeef, "literal-word expression preserves all bits");
    Check(p::ValidIconName("com.example#com.vendor.Activity$Inner.png"), "qualified icon filename accepted");
    for (const auto* name : {"../x.png","x/evil.png","x\\evil.png","..png","x.png\n","x.jpg","/x.png"}) Check(!p::ValidIconName(name), std::string("unsafe icon name: ") + name);
    Check(!p::ValidIconName(std::string(252,'a') + ".png"), "icon filename length bounded");
}

void TestHashes() {
    struct Vector { std::string text; const char* digest; };
    const Vector vectors[] = {
        {"","e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"},
        {"abc","ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"},
        {"abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq","248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1"},
        {std::string(1000000,'a'),"cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0"},
    };
    for (const auto& vector : vectors) {
        for (size_t chunk : {size_t(1),size_t(63),size_t(64),size_t(65),size_t(65536)}) {
            p::Sha256 hash;
            for (size_t at=0; at<vector.text.size();) { size_t count=std::min(chunk,vector.text.size()-at); hash.update(vector.text.data()+at,count); at+=count; }
            Check(hash.finish() == vector.digest, "FIPS SHA-256 vector, chunk=" + std::to_string(chunk));
        }
    }
}
} // namespace

int main(int argc, char** argv) {
    if (argc < 2 || argc > 3) { std::cerr << "usage: hyperos4_profile_test <official-profile.json> [original-launcher.so]\n"; return 2; }
    std::ifstream file(argv[1],std::ios::binary);
    if (!file) { std::cerr << "Cannot read profile: " << argv[1] << '\n'; return 2; }
    const std::string source{std::istreambuf_iterator<char>(file),std::istreambuf_iterator<char>()};
    p::Profile profile; std::string error;
    if (!p::Parse(source,profile,error)) { std::cerr << "Official profile rejected: " << error << '\n'; return 1; }
    p::Json json;
    if (!p::JsonReader(source).parse(json,error)) { std::cerr << error << '\n'; return 1; }
    TestMigration(profile);
    TestInvalidProfiles(json);
    TestScalarsAndNames();
    TestLargeIconPaths();
    TestImportedFunctions();
    if (argc == 3) TestOriginalGeometryImports(profile, argv[2]);
    TestHashes();
    std::cout << (failures ? "FAIL " : "PASS ") << checks << " checks; " << failures << " failures\n";
    return failures ? 1 : 0;
}
