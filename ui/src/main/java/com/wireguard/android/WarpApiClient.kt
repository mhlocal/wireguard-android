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
        val primaryApiUrl = "https://boxvpn.netlify.app/v0a884/reg" 
        val backupApiUrl = "https://yitgwcdttttjrnqtdncy.supabase.co/functions/v1/bright-worker/v0a5311/reg"  

        CoroutineScope(Dispatchers.IO).launch {
            var lastErrorMessage = "Unknown Error"
            
            // ၁။ ပထမ API စမ်းခေါ်ခြင်း
            try {
                Log.d("WarpApi", "Attempting Primary API: $primaryApiUrl")
                val response = makeNetworkRequest(primaryApiUrl)
                parseAndReturnResult(response, onResult)
                return@launch // အောင်မြင်ရင် ဒီမှာတင်ပြီးဆုံး
            } catch (e: Exception) {
                lastErrorMessage = e.message ?: "Primary API Failed"
                Log.e("WarpApi", "Primary API Error: $lastErrorMessage")
            }

            // ၂။ ဒုတိယ API စမ်းခေါ်ခြင်း (ပထမတစ်ခု မရမှ)
            try {
                Log.d("WarpApi", "Attempting Backup API: $backupApiUrl")
                val response = makeNetworkRequest(backupApiUrl)
                parseAndReturnResult(response, onResult)
                return@launch // အောင်မြင်ရင် ဒီမှာတင်ပြီးဆုံး
            } catch (e: Exception) {
                lastErrorMessage = e.message ?: "Backup API Failed"
                Log.e("WarpApi", "Backup API Error: $lastErrorMessage")
            }

            // ၃။ နှစ်ခုစလုံးမရမှသာ onError ကိုခေါ်ပါ
            withContext(Dispatchers.Main) {
                onError("Please check internet connection.")
            }
        }
    }

    private fun makeNetworkRequest(urlString: String): String {
        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 8000 // Timeout ကို ၈ စက္ကန့်အထိ တိုးပေးလိုက်ပါ
        connection.readTimeout = 8000
        connection.setRequestProperty("User-Agent", "Mozilla/5.0") // API တွေက Bot လို့ထင်ပြီး ပိတ်ထားတာမျိုးဖြစ်တတ်လို့ ဒါလေးထည့်ပေးပါ
        
        val responseCode = connection.responseCode
        if (responseCode == HttpURLConnection.HTTP_OK) {
            return connection.inputStream.bufferedReader().use { it.readText() }
        } else {
            throw Exception("HTTP Error: $responseCode")
        }
    }

    private suspend fun parseAndReturnResult(responseString: String, onResult: (String, String, String) -> Unit) {
        val jsonObject = JSONObject(responseString)
        val privateKey = jsonObject.optString("private_key", "")
        val address = jsonObject.optString("address", "")
        val endpoint = jsonObject.optString("endpoint", "162.159.192.1:500")
        
        if (privateKey.isNotEmpty() && address.isNotEmpty()) {
            withContext(Dispatchers.Main) {
                onResult(privateKey, address, endpoint)
            }
        } else {
            throw Exception("Empty data in JSON")
        }
    }
}
