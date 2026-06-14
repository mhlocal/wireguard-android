package com.wireguard.android

import android.content.Context
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class PremiumManager(private val context: Context) {
    private val client = OkHttpClient()
    
    // ⚠️ သင့် Supabase URL နှင့် API Key ကို မှန်ကန်စွာ ထည့်ပါ ⚠️
    private val supabaseUrl = "https://hmjmyoqkvqwjhqdnlamf.supabase.co/rest/v1/devices"
    private val supabaseKey = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Imhtam15b3FrdnF3amhxZG5sYW1mIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODE0MTY3NjMsImV4cCI6MjA5Njk5Mjc2M30.CXgn9jMN1tq9QczoMxmgpL9DzrfF5ah4ncFN1fSBsCU"

    private fun getDeviceId(): String {
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    }

    suspend fun checkTrialStatus(
        onStatusResult: (daysLeft: Int) -> Unit, 
        onExpired: () -> Unit
    ) {
        val prefs = context.getSharedPreferences("PremiumPrefs", Context.MODE_PRIVATE)
        val expireDateMs = prefs.getLong("expire_date_ms", 0L)
        val currentTimeMs = System.currentTimeMillis()

        // 🌟 အဆင့် (၁) - ဖုန်းမှတ်ဉာဏ် (SharedPreference) တွင် သက်တမ်းကျန်/မကျန် အရင်စစ်မည် 🌟
        if (expireDateMs > currentTimeMs) {
            val diffInMillis = expireDateMs - currentTimeMs
            val daysLeft = TimeUnit.MILLISECONDS.toDays(diffInMillis).toInt()
            if (daysLeft > 0) {
                withContext(Dispatchers.Main) { onStatusResult(daysLeft) }
                return // သက်တမ်းကျန်သေးသဖြင့် Supabase သို့ အင်တာနက်ဖြင့် လှမ်းမစစ်တော့ပါ
            }
        }

        // 🌟 အဆင့် (၂) - သက်တမ်းကုန်နေပါက (သို့) ပထမဆုံးအကြိမ်ဖြစ်နေပါက Supabase တွင် စစ်မည် 🌟
        val deviceId = getDeviceId()
        
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$supabaseUrl?device_id=eq.$deviceId")
                    .addHeader("apikey", supabaseKey)
                    .addHeader("Authorization", "Bearer $supabaseKey")
                    .get()
                    .build()

                val response = client.newCall(request).execute()
                val responseData = response.body?.string() ?: ""
                
                if (!response.isSuccessful) {
                    withContext(Dispatchers.Main) { onStatusResult(1) } // Error ဖြစ်လျှင် ယာယီခွင့်ပြုထားမည်
                    return@withContext
                }

                val jsonArray = JSONArray(responseData)

                if (jsonArray.length() == 0) {
                    // အသစ်ဖြစ်ပါက ၇ ရက်ပေး၍ ဖုန်းမှတ်ဉာဏ် + Database နှစ်ခုလုံးတွင် သိမ်းမည်
                    val newExpireMs = registerNewDevice(deviceId)
                    if (newExpireMs > 0L) {
                        prefs.edit().putLong("expire_date_ms", newExpireMs).apply()
                        withContext(Dispatchers.Main) { onStatusResult(7) }
                    } else {
                        withContext(Dispatchers.Main) { onStatusResult(1) }
                    }
                } else {
                    val deviceData = jsonArray.getJSONObject(0)
                    val premiumExpireStr = deviceData.optString("premium_expire_date", "null")
                    
                    if (premiumExpireStr != "null" && premiumExpireStr.isNotEmpty()) {
                        val serverExpireMs = parseDateToMillis(premiumExpireStr)
                        
                        if (serverExpireMs > currentTimeMs) {
                            // Supabase တွင် Admin မှ Premium ထပ်တိုးပေးထားပါက ဖုန်းမှတ်ဉာဏ်ကိုပါ အသစ်ပြန်ပြင်မည်
                            prefs.edit().putLong("expire_date_ms", serverExpireMs).apply()
                            val daysLeft = TimeUnit.MILLISECONDS.toDays(serverExpireMs - currentTimeMs).toInt()
                            withContext(Dispatchers.Main) { onStatusResult(daysLeft) }
                        } else {
                            // အမှန်တကယ် သက်တမ်းကုန်သွားပါက မှတ်ဉာဏ်ကို 0 ပြန်ထားမည်
                            prefs.edit().putLong("expire_date_ms", 0L).apply()
                            withContext(Dispatchers.Main) { onExpired() }
                        }
                    } else {
                        prefs.edit().putLong("expire_date_ms", 0L).apply()
                        withContext(Dispatchers.Main) { onExpired() }
                    }
                }
            } catch (e: Exception) {
                Log.e("PremiumManager", "App Error: ${e.message}")
                withContext(Dispatchers.Main) { onStatusResult(1) }
            }
        }
    }

    private fun registerNewDevice(deviceId: String): Long {
        return try {
            val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            calendar.add(Calendar.DAY_OF_YEAR, 7)
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            format.timeZone = TimeZone.getTimeZone("UTC")
            val expireDateStr = format.format(calendar.time)

            val json = JSONObject().apply { 
                put("device_id", deviceId) 
                put("premium_expire_date", expireDateStr)
            }
            
            val requestBody = json.toString().toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url(supabaseUrl)
                .addHeader("apikey", supabaseKey)
                .addHeader("Authorization", "Bearer $supabaseKey")
                .addHeader("Prefer", "return=minimal")
                .post(requestBody)
                .build()
                
            val response = client.newCall(request).execute()
            if (response.isSuccessful) calendar.timeInMillis else 0L
        } catch (e: Exception) {
            0L
        }
    }

    private fun parseDateToMillis(dateStr: String): Long {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return try {
            format.parse(dateStr)?.time ?: 0L
        } catch (e: Exception) { 0L }
    }
}
