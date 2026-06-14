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
                    Log.e("PremiumManager", "Supabase GET Error: ${response.code}")
                    withContext(Dispatchers.Main) { onStatusResult(1) }
                    return@withContext
                }

                val jsonArray = JSONArray(responseData)

                if (jsonArray.length() == 0) {
                    // (၁) အသစ်ဆိုလျှင် ၇ ရက်စာ Expire Date ကို တွက်၍ Database သို့ တန်းသိမ်းမည်
                    val isSaved = registerNewDevice(deviceId)
                    if (isSaved) {
                        withContext(Dispatchers.Main) { onStatusResult(7) }
                    } else {
                        withContext(Dispatchers.Main) { onStatusResult(1) }
                    }
                } else {
                    val deviceData = jsonArray.getJSONObject(0)
                    
                    // (၂) Database ရှိ Expire Date ကို စစ်ဆေးမည်
                    val premiumExpireStr = deviceData.optString("premium_expire_date", "null")
                    
                    if (premiumExpireStr != "null" && premiumExpireStr.isNotEmpty()) {
                        val daysLeft = getPremiumDaysLeft(premiumExpireStr)
                        if (daysLeft > 0) {
                            withContext(Dispatchers.Main) { onStatusResult(daysLeft) }
                        } else {
                            withContext(Dispatchers.Main) { onExpired() }
                        }
                    } else {
                        withContext(Dispatchers.Main) { onExpired() }
                    }
                }
            } catch (e: Exception) {
                Log.e("PremiumManager", "App Error: ${e.message}")
                withContext(Dispatchers.Main) { onStatusResult(1) }
            }
        }
    }

    private fun registerNewDevice(deviceId: String): Boolean {
        return try {
            // ယခုအချိန်မှစ၍ နောက် ၇ ရက်ကို တွက်ချက်ခြင်း
            val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            calendar.add(Calendar.DAY_OF_YEAR, 7)
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            format.timeZone = TimeZone.getTimeZone("UTC")
            val expireDateStr = format.format(calendar.time)

            // Database သို့ ID နှင့်တကွ Expire Date အတိအကျကိုပါ ထည့်သွင်းခြင်း
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
            response.isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    private fun getPremiumDaysLeft(expireDateStr: String): Int {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return try {
            val expireDate = format.parse(expireDateStr) ?: Date()
            val diffInMillis = expireDate.time - Date().time
            TimeUnit.MILLISECONDS.toDays(diffInMillis).toInt()
        } catch (e: Exception) { 0 }
    }
}
