#include <jni.h>
#include <string>
#include <stdlib.h> // 🌟 exit(0) သုံးရန် ဤ Header ကို ထည့်ထားပါသည်

// ==========================================
// 🌟 App ၏ Signature ကို စစ်ဆေးသည့် စနစ် (Mod ကာကွယ်ရေး) 🌟
// ==========================================
bool isValidApp(JNIEnv* env, jobject context) {
    if (context == nullptr) return false;

    jclass contextClass = env->GetObjectClass(context);
    jmethodID getPackageManagerId = env->GetMethodID(contextClass, "getPackageManager", "()Landroid/content/pm/PackageManager;");
    jobject packageManager = env->CallObjectMethod(context, getPackageManagerId);

    jmethodID getPackageNameId = env->GetMethodID(contextClass, "getPackageName", "()Ljava/lang/String;");
    jstring packageName = (jstring) env->CallObjectMethod(context, getPackageNameId);

    jclass packageManagerClass = env->GetObjectClass(packageManager);
    jmethodID getPackageInfoId = env->GetMethodID(packageManagerClass, "getPackageInfo", "(Ljava/lang/String;I)Landroid/content/pm/PackageInfo;");
    
    // GET_SIGNATURES = 64
    jobject packageInfo = env->CallObjectMethod(packageManager, getPackageInfoId, packageName, 64);
    if (packageInfo == nullptr) return false;

    jclass packageInfoClass = env->GetObjectClass(packageInfo);
    jfieldID signaturesFieldId = env->GetFieldID(packageInfoClass, "signatures", "[Landroid/content/pm/Signature;");
    jobjectArray signaturesArray = (jobjectArray) env->GetObjectField(packageInfo, signaturesFieldId);

    jobject signature = env->GetObjectArrayElement(signaturesArray, 0);
    jclass signatureClass = env->GetObjectClass(signature);
    jmethodID hashCodeId = env->GetMethodID(signatureClass, "hashCode", "()I");
    jint hashCode = env->CallIntMethod(signature, hashCodeId);

    // ⚠️ သင်ပေးထားသော App Hash ဂဏန်း အစစ်အမှန်
    jint expectedHashCode = 1844937167; 

    return (hashCode == expectedHashCode);
}


// ==========================================
// 🌟 ၁။ Supabase API များ (PremiumManager) 🌟
// ==========================================
extern "C" JNIEXPORT jstring JNICALL
Java_com_wireguard_android_PremiumManager_getSupabaseUrl(JNIEnv* env, jobject /* this */, jobject context) {
    // Hash မတူပါက (Mod ထားပါက) App ကို ချက်ချင်း ပိတ်ချမည်
    if (!isValidApp(env, context)) {
        exit(0); 
    }
    
    // မှန်ကန်မှသာ အောက်ပါ URL အစစ်ကို ပြန်ပေးမည်
    std::string url = "https://hmjmyoqkvqwjhqdnlamf.supabase.co/rest/v1/devices"; 
    return env->NewStringUTF(url.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_wireguard_android_PremiumManager_getSupabaseKey(JNIEnv* env, jobject /* this */, jobject context) {
    if (!isValidApp(env, context)) {
        exit(0); 
    }
    
    std::string key = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Imhtam15b3FrdnF3amhxZG5sYW1mIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODE0MTY3NjMsImV4cCI6MjA5Njk5Mjc2M30.CXgn9jMN1tq9QczoMxmgpL9DzrfF5ah4ncFN1fSBsCU"; 
    return env->NewStringUTF(key.c_str());
}


// ==========================================
// 🌟 ၂။ Warp API များ (WarpApiClient) 🌟
// ==========================================
extern "C" JNIEXPORT jstring JNICALL
Java_com_wireguard_android_WarpApiClient_getProxyUrl1(JNIEnv* env, jobject /* this */, jobject context) {
    if (!isValidApp(env, context)) {
        exit(0); 
    }
    
    std::string url = "https://api.cloudflareclient.com//v0a884/reg";
    return env->NewStringUTF(url.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_wireguard_android_WarpApiClient_getProxyUrl2(JNIEnv* env, jobject /* this */, jobject context) {
    if (!isValidApp(env, context)) {
        exit(0); 
    }
    
    std::string url = "https://yitgwcdttttjrnqtdncy.supabase.co/functions/v1/bright-worker/v0a5311/reg";
    return env->NewStringUTF(url.c_str());
}
