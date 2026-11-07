// App-side JNI shares the exact parser used by the dex-less native_init engine.
// The obsolete ART-only patch installer is deliberately not a second engine.
#include "hyperos4_profile.h"
#include <jni.h>
#include <string>
#include <sys/system_properties.h>

extern "C" JNIEXPORT jstring JNICALL
Java_com_jerrey_monoicon_profile_ProfileNative_validate(JNIEnv* env, jobject, jbyteArray input) {
    if (!input) return env->NewStringUTF("profile bytes missing");
    const jsize size = env->GetArrayLength(input);
    if (size <= 0 || static_cast<size_t>(size) > monoicon::profile::kMaxProfileBytes) return env->NewStringUTF("profile exceeds 2 MiB limit");
    std::string source(static_cast<size_t>(size), '\0'), error;
    env->GetByteArrayRegion(input, 0, size, reinterpret_cast<jbyte*>(source.data()));
    if (env->ExceptionCheck()) return nullptr;
    monoicon::profile::Profile profile;
    monoicon::profile::Parse(source, profile, error);
    // Diagnostics contain field/operation names only, not arbitrary JSON bytes.
    for (char& c : error) if (static_cast<unsigned char>(c) >= 128) c = '?';
    return env->NewStringUTF(error.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_jerrey_monoicon_profile_ProfileNative_systemProperty(JNIEnv* env, jobject, jstring name) {
    if (!name || env->GetStringUTFLength(name) > 92) return env->NewStringUTF("");
    const char* key = env->GetStringUTFChars(name, nullptr);
    if (!key) return nullptr;
    std::string value;
    if (std::string(key).rfind("ro.", 0) == 0) {
        if (const auto* property = __system_property_find(key)) {
            __system_property_read_callback(property, [](void* cookie, const char*, const char* text, uint32_t) {
                *static_cast<std::string*>(cookie) = text;
            }, &value);
        }
    }
    env->ReleaseStringUTFChars(name, key);
    return env->NewStringUTF(value.c_str());
}
