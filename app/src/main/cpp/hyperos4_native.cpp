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
    char value[PROP_VALUE_MAX]{};
    if (key[0] == 'r' && key[1] == 'o' && key[2] == '.') __system_property_get(key, value);
    env->ReleaseStringUTFChars(name, key);
    return env->NewStringUTF(value);
}
