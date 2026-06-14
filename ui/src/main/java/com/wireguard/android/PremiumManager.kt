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

    // 🌟 (၁) C++ Library ကို App ပွင့်သည်နှင့် ချိတ်ဆက်ခြင်း 🌟
    init {
        System.loadLibrary("api-keys")
    }

    // 🌟 (၂) C++ ထဲမှ Function များကို Kotlin သို့ လှမ်းခေါ်ခြင်း (App Signature စစ်ရန် context ထည့်ပေးရမည်) 🌟
    private external fun getSupabaseUrl(context: Context): String
    private external fun getSupabaseKey(context: Context): String

    // 🌟 (၃) C++ မှ ရရှိသော လုံခြုံသည့် URL နှင့် Key ကို အသုံးပြုခြင်း 🌟
    private val supabaseUrl = getSupabaseUrl(context)
    private val supabaseKey = getSupabaseKey(context)

    private fun getDeviceId(): String {
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    }

    suspend fun checkTrialStatus(
        onLoading: () -> Unit,
        onStatusResult: (daysLeft: Int) -> Unit, 
        onExpired: () -> Unit
    ) {
        val prefs = context.getSharedPreferences("PremiumPrefs", Context.MODE_PRIVATE)
        val localExpireMs = prefs.getLong("expire_date_ms", 0L)
        val currentTimeMs = System.currentTimeMillis()

        if (localExpireMs > currentTimeMs) {
            val diffInMillis = localExpireMs - currentTimeMs
            val daysLeft = TimeUnit.MILLISECONDS.toDays(diffInMillis).toInt()
            withContext(Dispatchers.Main) { onStatusResult(daysLeft) }
        } else {
            withContext(Dispatchers.Main) { onLoading() }
        }

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
                        withContext(Dispatchers.Main) { onExpired() }
                    }
                    return@withContext
                }

                val jsonArray = JSONArray(responseData)

                if (jsonArray.length() == 0) {
                    val newExpireMs = registerNewDevice(deviceId)
                    if (newExpireMs > 0L) {
                        prefs.edit().putLong("expire_date_ms", newExpireMs).apply()
                        val newDaysLeft = TimeUnit.MILLISECONDS.toDays(newExpireMs - currentTimeMs).toInt()
                        withContext(Dispatchers.Main) { onStatusResult(newDaysLeft) }
                    } else {
                        if (localExpireMs <= currentTimeMs) {
                            withContext(Dispatchers.Main) { onExpired() }
                        }
                    }
                } else {
                    val deviceData = jsonArray.getJSONObject(0)
                    val premiumExpireStr = deviceData.optString("premium_expire_date", "null")
                    
                    if (premiumExpireStr != "null" && premiumExpireStr.isNotEmpty()) {
                        val serverExpireMs = parseDateToMillis(premiumExpireStr)
                        
                        if (serverExpireMs > currentTimeMs) {
                            if (serverExpireMs != localExpireMs) {
                                prefs.edit().putLong("expire_date_ms", serverExpireMs).apply()
                                val updatedDaysLeft = TimeUnit.MILLISECONDS.toDays(serverExpireMs - currentTimeMs).toInt()
                                withContext(Dispatchers.Main) { onStatusResult(updatedDaysLeft) }
                            }
                        } else {
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
                if (localExpireMs <= currentTimeMs) {
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
