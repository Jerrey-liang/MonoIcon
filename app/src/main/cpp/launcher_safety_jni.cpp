#include "launcher_safety.h"
#include <jni.h>

extern "C" JNIEXPORT jboolean JNICALL
Java_com_jerrey_monoicon_safety_LauncherSafetyNative_nativeBegin(JNIEnv*, jobject) {
    return monoicon::safety::Begin() ? JNI_TRUE : JNI_FALSE;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_jerrey_monoicon_safety_LauncherSafetyNative_nativeIsBlocked(JNIEnv*, jobject) {
    return monoicon::safety::IsBlocked() ? JNI_TRUE : JNI_FALSE;
}
