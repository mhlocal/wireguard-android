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
    
    // ⚠️ သတိပြုရန် - .co ၏ အနောက်တွင် /rest/v1/devices ဆိုတာ မဖြစ်မနေ ပါရပါမည် ⚠️
    // ဥပမာ - "https://abcdefghijklmnop.supabase.co/rest/v1/devices"
    private val supabaseUrl = "https://hmjmyoqkvqwjhqdnlamf.supabase.co/rest/v1/devices"
    private val supabaseKey = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Imhtam15b3FrdnF3amhxZG5sYW1mIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODE0MTY3NjMsImV4cCI6MjA5Njk5Mjc2M30.CXgn9jMN1tq9QczoMxmgpL9DzrfF5ah4ncFN1fSBsCU"

    private fun getDeviceId(): String {
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    }

    suspend fun checkTrialStatus(
        onStatusResult: (daysLeft: Int, isPremium: Boolean) -> Unit, 
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
                
                // Supabase ဘက်မှ အောင်မြင်စွာ ပြန်မပို့ပါက (ဥပမာ - လင့်ခ်မှားနေလျှင်)
                if (!response.isSuccessful) {
                    Log.e("PremiumManager", "Supabase GET Error: ${response.code} - $responseData")
                    withContext(Dispatchers.Main) { onStatusResult(1, false) }
                    return@withContext
                }

                val jsonArray = JSONArray(responseData)

                if (jsonArray.length() == 0) {
                    // (၁) အသစ်ဆိုလျှင် ၇ ရက် Free Trial စပေးရန် Database သို့ လှမ်းသိမ်းမည်
                    val isSaved = registerNewDevice(deviceId)
                    if (isSaved) {
                        withContext(Dispatchers.Main) { onStatusResult(7, false) }
                    } else {
                        // Database ထဲ သိမ်းတာ ကျရှုံးသွားလျှင်
                        withContext(Dispatchers.Main) { onStatusResult(1, false) }
                    }
                } else {
                    val deviceData = jsonArray.getJSONObject(0)
                    
                    // (၂) Premium ဝယ်ထားခြင်း ရှိ/မရှိ အရင်စစ်မည်
                    val premiumExpireStr = deviceData.optString("premium_expire_date", "null")
                    if (premiumExpireStr != "null" && premiumExpireStr.isNotEmpty()) {
                        val premiumDaysLeft = getPremiumDaysLeft(premiumExpireStr)
                        if (premiumDaysLeft > 0) {
                            withContext(Dispatchers.Main) { onStatusResult(premiumDaysLeft, true) }
                            return@withContext
                        }
                    }

                    // (၃) Premium မရှိလျှင် (သို့) Premium သက်တမ်းကုန်သွားလျှင် Trial စစ်မည်
                    val trialStartDateStr = deviceData.getString("trial_start_date")
                    val trialDaysLeft = getTrialDaysLeft(trialStartDateStr)

                    withContext(Dispatchers.Main) {
                        if (trialDaysLeft > 0) {
                            onStatusResult(trialDaysLeft, false) 
                        } else {
                            onExpired() 
                        }
                    }
                }
            } catch (e: Exception) {
                // အင်တာနက်မရှိတာမျိုး၊ JSON ပုံစံမှားတာမျိုး ဖြစ်လျှင်
                Log.e("PremiumManager", "App Error: ${e.message}")
                withContext(Dispatchers.Main) { onStatusResult(1, false) }
            }
        }
    }

    private fun registerNewDevice(deviceId: String): Boolean {
        return try {
            val json = JSONObject().apply { put("device_id", deviceId) }
            val requestBody = json.toString().toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url(supabaseUrl)
                .addHeader("apikey", supabaseKey)
                .addHeader("Authorization", "Bearer $supabaseKey")
                .addHeader("Prefer", "return=minimal")
                .post(requestBody)
                .build()
                
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e("PremiumManager", "Supabase POST Error: ${response.code} - ${response.body?.string()}")
                false
            } else {
                true
            }
        } catch (e: Exception) {
            Log.e("PremiumManager", "Save Error: ${e.message}")
            false
        }
    }

    private fun getTrialDaysLeft(startDateStr: String): Int {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return try {
            val startDate = format.parse(startDateStr) ?: Date()
            val diffInMillis = Date().time - startDate.time
            val daysPassed = TimeUnit.MILLISECONDS.toDays(diffInMillis).toInt()
            7 - daysPassed
        } catch (e: Exception) { 0 }
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
