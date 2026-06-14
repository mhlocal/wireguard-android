#include <jni.h>
#include <string>

// ==========================================
// 🌟 ၁။ Supabase (PremiumManager) အတွက် 🌟
// ==========================================
extern "C" JNIEXPORT jstring JNICALL
Java_com_wireguard_android_PremiumManager_getSupabaseUrl(JNIEnv* env, jobject /* this */) {
    std::string url = "https://xxxxxxxxxxxx.supabase.co/rest/v1/devices"; // သင့် Supabase URL အမှန်ကို ပြောင်းပါ
    return env->NewStringUTF(url.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_wireguard_android_PremiumManager_getSupabaseKey(JNIEnv* env, jobject /* this */) {
    std::string key = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.xxxxxxxxxxxxxxxxx..."; // သင့် Supabase Key အမှန်ကို ပြောင်းပါ
    return env->NewStringUTF(key.c_str());
}

// ==========================================
// 🌟 ၂။ Warp (WarpApiClient) အတွက် 🌟
// ==========================================
extern "C" JNIEXPORT jstring JNICALL
Java_com_wireguard_android_WarpApiClient_getProxyUrl1(JNIEnv* env, jobject /* this */) {
    std::string url = "https://api.cloudflareclient.com//v0a884/reg";
    return env->NewStringUTF(url.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_wireguard_android_WarpApiClient_getProxyUrl2(JNIEnv* env, jobject /* this */) {
    std::string url = "https://yitgwcdttttjrnqtdncy.supabase.co/functions/v1/bright-worker/v0a5311/reg";
    return env->NewStringUTF(url.c_str());
}
