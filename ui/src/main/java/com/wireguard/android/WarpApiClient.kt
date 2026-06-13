package com.wireguard.android

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class WarpApiClient {

    fun generateWarpConfig(onResult: (String, String, String) -> Unit, onError: (String) -> Unit) {
        
        // ⚠️ သင့်ရဲ့ တကယ့် API Link (၂) ခုကို ဒီနေရာမှာ အစားထိုးထည့်ပါ ⚠️
        val primaryApiUrl = "https://boxvpn.netlify.app/v0a884/reg" 
        val backupApiUrl = "https://yitgwcdttttjrnqtdncy.supabase.co/functions/v1/bright-worker/v0a5311/reg"  

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // ၁။ ပထမ API ကို အရင် စမ်းခေါ်ပါမည်
                Log.d("WarpApi", "Trying Primary API...")
                val response = makeNetworkRequest(primaryApiUrl)
                parseAndReturnResult(response, onResult)

            } catch (e1: Exception) {
                Log.e("WarpApi", "Primary API Failed: ${e1.message}. Trying Backup API...")
                
                // ၂။ ပထမ API အလုပ်မလုပ်ပါက ဒုတိယ API ကို ဆက်ခေါ်ပါမည် (Fallback)
                try {
                    val fallbackResponse = makeNetworkRequest(backupApiUrl)
                    parseAndReturnResult(fallbackResponse, onResult)
                    
                } catch (e2: Exception) {
                    // ၃။ API နှစ်ခုစလုံး အလုပ်မလုပ်တော့မှသာ Error အဖြစ် သတ်မှတ်ပါမည်
                    Log.e("WarpApi", "Backup API also Failed: ${e2.message}")
                    withContext(Dispatchers.Main) {
                        onError("Failed to connect to servers. Please check internet.")
                    }
                }
            }
        }
    }

    // Network (အင်တာနက်) သို့ လှမ်းချိတ်ဆက်သည့် လုပ်ငန်းစဉ်
    private fun makeNetworkRequest(urlString: String): String {
        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 5000 // ၅ စက္ကန့်အတွင်း မချိတ်နိုင်ပါက ဒုတိယ API သို့ ပြောင်းပါမည်
        connection.readTimeout = 5000
        
        val responseCode = connection.responseCode
        if (responseCode == HttpURLConnection.HTTP_OK) {
            return connection.inputStream.bufferedReader().use { it.readText() }
        } else {
            throw Exception("Server returned HTTP response code: $responseCode")
        }
    }

    // API မှ ပြန်ရလာသော JSON ကို ဖြည်ပြီး Config ထုတ်ပေးသည့် လုပ်ငန်းစဉ်
    private suspend fun parseAndReturnResult(responseString: String, onResult: (String, String, String) -> Unit) {
        try {
            val jsonObject = JSONObject(responseString)
            
            // ⚠️ သတိပြုရန် - သင့် API မှ ပြန်ပို့သော စာသား (Keys) များနှင့် အောက်ပါ "private_key", "address" များကို ကိုက်ညီအောင် ပြင်ပေးပါ ⚠️
            val privateKey = jsonObject.optString("private_key", "")
            val address = jsonObject.optString("address", "")
            val endpoint = jsonObject.optString("endpoint", "162.159.192.1:2408") // မပါလာပါက မူလ WARP IP ကို သုံးပါမည်
            
            if (privateKey.isNotEmpty() && address.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    onResult(privateKey, address, endpoint)
                }
            } else {
                throw Exception("Invalid config data received")
            }
        } catch (e: Exception) {
            Log.e("WarpApi", "JSON Parse Error: ${e.message}")
            throw e // JSON ဖြည်ရာတွင် အမှားအယွင်းရှိပါက ဒုတိယ API သို့ ပြောင်းခေါ်စေရန် Error ပစ်တင်လိုက်ပါမည်
        }
    }
}
