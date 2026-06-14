#include <jni.h>
#include <string>

// ==========================================
// 🌟 ၁။ Supabase (PremiumManager) အတွက် 🌟
// ==========================================

extern "C" JNIEXPORT jstring JNICALL
Java_com_wireguard_android_PremiumManager_getSupabaseUrl(JNIEnv* env, jobject /* this */) {
    std::string url = "https://xxxxxxxxxxxx.supabase.co/rest/v1/devices";
    return env->NewStringUTF(url.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_wireguard_android_PremiumManager_getSupabaseKey(JNIEnv* env, jobject /* this */) {
    std::string key = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.xxxxxxxxxxxxxxxxx...";
    return env->NewStringUTF(key.c_str());
}

// ==========================================
// 🌟 ၂။ Warp (WarpApiClient) အတွက် 🌟
// ==========================================

// Warp ၏ Base URL ကို ဖျောက်ရန်
extern "C" JNIEXPORT jstring JNICALL
Java_com_wireguard_android_WarpApiClient_getWarpBaseUrl(JNIEnv* env, jobject /* this */) {
    // သင့် WarpApiClient ထဲက တကယ့် URL ကို ဤနေရာတွင် ထည့်ပါ
    std::string url = "https://api.cloudflareclient.com/v0a884/reg"; 
    return env->NewStringUTF(url.c_str());
}

// Warp အတွက် လိုအပ်သော တခြား Key များရှိလျှင် (ဥပမာ - Authorization token)
extern "C" JNIEXPORT jstring JNICALL
Java_com_wireguard_android_WarpApiClient_getWarpAuthKey(JNIEnv* env, jobject /* this */) {
    // သင့် WarpApiClient တွင် သုံးသော တကယ့် Key ကို ထည့်ပါ
    std::string key = "YOUR_WARP_SECRET_KEY_OR_TOKEN"; 
    return env->NewStringUTF(key.c_str());
}
