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
        onLoading: () -> Unit, // 🌟 Loading ပြရန် အသစ်ထည့်ထားသည် 🌟
        onStatusResult: (daysLeft: Int) -> Unit, 
        onExpired: () -> Unit
    ) {
        val prefs = context.getSharedPreferences("PremiumPrefs", Context.MODE_PRIVATE)
        val localExpireMs = prefs.getLong("expire_date_ms", 0L)
        val currentTimeMs = System.currentTimeMillis()

        // 🌟 အဆင့် (၁) - ဖုန်းထဲတွင် ရက်ကျန်/မကျန် စစ်မည် 🌟
        if (localExpireMs > currentTimeMs) {
            val diffInMillis = localExpireMs - currentTimeMs
            val daysLeft = TimeUnit.MILLISECONDS.toDays(diffInMillis).toInt()
            withContext(Dispatchers.Main) { onStatusResult(daysLeft) }
        } else {
            // ရက်မကျန်ပါက (သို့မဟုတ် ပထမဆုံးအကြိမ်ဖြစ်ပါက) Loading အရင်ပြခိုင်းမည်
            withContext(Dispatchers.Main) { onLoading() }
        }

        // 🌟 အဆင့် (၂) - Supabase တွင် အင်တာနက်ဖြင့် မဖြစ်မနေ စစ်မည် 🌟
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
                    if (localExpireMs <= currentTimeMs) {
                        withContext(Dispatchers.Main) { onExpired() } // အင်တာနက်မရှိ/Error တက်၍ ရက်လည်းမကျန်ပါက ပိတ်ချမည်
                    }
                    return@withContext
                }

                val jsonArray = JSONArray(responseData)

                if (jsonArray.length() == 0) {
                    // အသစ်ဖြစ်ပါက ၇ ရက်ပေးမည်
                    val newExpireMs = registerNewDevice(deviceId)
                    if (newExpireMs > 0L) {
                        prefs.edit().putLong("expire_date_ms", newExpireMs).apply()
                        val newDaysLeft = TimeUnit.MILLISECONDS.toDays(newExpireMs - currentTimeMs).toInt()
                        withContext(Dispatchers.Main) { onStatusResult(newDaysLeft) }
                    } else {
                        if (localExpireMs <= currentTimeMs) {
                            withContext(Dispatchers.Main) { onExpired() } // သိမ်း၍မရပါက ပိတ်ချမည်
                        }
                    }
                } else {
                    val deviceData = jsonArray.getJSONObject(0)
                    val premiumExpireStr = deviceData.optString("premium_expire_date", "null")
                    
                    if (premiumExpireStr != "null" && premiumExpireStr.isNotEmpty()) {
                        val serverExpireMs = parseDateToMillis(premiumExpireStr)
                        
                        if (serverExpireMs > currentTimeMs) {
                            // Database တွင် Admin ပြင်ထား၍ ရက်ရှိနေပါက Update ပြန်လုပ်ပေးမည်
                            if (serverExpireMs != localExpireMs) {
                                prefs.edit().putLong("expire_date_ms", serverExpireMs).apply()
                                val updatedDaysLeft = TimeUnit.MILLISECONDS.toDays(serverExpireMs - currentTimeMs).toInt()
                                withContext(Dispatchers.Main) { onStatusResult(updatedDaysLeft) }
                            }
                        } else {
                            // တကယ် သက်တမ်းကုန်နေပါက ဖုန်းမှတ်ဉာဏ်ကို ဖျက်မည်
                            prefs.edit().putLong("expire_date_ms", 0L).apply()
                            withContext(Dispatchers.Main) { onExpired() }
                        }
                    } else {
                        prefs.edit().putLong("expire_date_ms", 0L).apply()
                        withContext(Dispatchers.Main) { onExpired() }
                    }
                }
            } catch (e: Exception) {
                // အင်တာနက်လုံးဝမရှိပါက ဤနေရာသို့ ရောက်လာမည်
                Log.e("PremiumManager", "App Error: ${e.message}")
                if (localExpireMs <= currentTimeMs) {
                    // အင်တာနက်လည်းမရှိ၊ ဖုန်းထဲမှာလည်း ရက်မကျန်ပါက အတင်းပိတ်ချမည်
                    withContext(Dispatchers.Main) { onExpired() }
                }
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
        } catch (e: Exception) { 0L }
    }

    private fun parseDateToMillis(dateStr: String): Long {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return try {
            format.parse(dateStr)?.time ?: 0L
        } catch (e: Exception) { 0L }
    }
}
