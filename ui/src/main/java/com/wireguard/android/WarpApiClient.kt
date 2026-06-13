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
        
        // ⚠️ သင့်၏ API လင့်များကို ဤနေရာတွင် ထည့်ပါ ⚠️
        val primaryApiUrl = "https://boxvpn.netlify.app/v0a884/reg" 
        val backupApiUrl = "https://yitgwcdttttjrnqtdncy.supabase.co/functions/v1/bright-worker/v0a5311/reg"  

        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d("WarpApi", "Trying Primary API: $primaryApiUrl")
                val response = makeNetworkRequest(primaryApiUrl)
                parseAndReturnResult(response, onResult)

            } catch (e1: Exception) {
                Log.e("WarpApi", "Primary API Failed: ${e1.message}. Trying Backup API...")
                
                try {
                    Log.d("WarpApi", "Trying Backup API: $backupApiUrl")
                    val fallbackResponse = makeNetworkRequest(backupApiUrl)
                    parseAndReturnResult(fallbackResponse, onResult)
                    
                } catch (e2: Exception) {
                    Log.e("WarpApi", "Backup API also Failed: ${e2.message}")
                    withContext(Dispatchers.Main) {
                        onError("API 1 Error: ${e1.message}\nAPI 2 Error: ${e2.message}")
                    }
                }
            }
        }
    }

    private fun makeNetworkRequest(urlString: String): String {
        val safeUrlString = if (urlString.startsWith("http://")) urlString.replace("http://", "https://") else urlString
        
        val url = URL(safeUrlString)
        val connection = url.openConnection() as HttpURLConnection
        
        // 🌟 အရေးကြီးသော ပြင်ဆင်ချက် - GET အစား POST သုံးရန် 🌟
        connection.requestMethod = "POST"
        connection.doOutput = true // POST ဖြင့် Data ပို့မည်ဟု သတ်မှတ်ခြင်း
        
        // Headers များ
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36")
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Content-Type", "application/json") // 400 Error ကို ကာကွယ်ရန်
        
        connection.connectTimeout = 15000 
        connection.readTimeout = 15000
        
        // API အများစုသည် POST Request ခေါ်ပါက Body အလွတ် (Empty JSON) တောင်းတတ်သဖြင့် ဖြည့်ပေးခြင်း
        try {
            connection.outputStream.use { os ->
                val input = "{}".toByteArray(Charsets.UTF_8)
                os.write(input, 0, input.size)
            }
        } catch (e: Exception) {
            Log.e("WarpApi", "Error writing POST body: ${e.message}")
        }
        
        val responseCode = connection.responseCode
        if (responseCode in 200..299) {
            return connection.inputStream.bufferedReader().use { it.readText() }
        } else {
            // Error Message အတိအကျကို ပြန်ဖတ်ရန် ကြိုးစားမည်
            val errorMsg = try {
                connection.errorStream?.bufferedReader()?.use { it.readText() }
            } catch (e: Exception) { null }
            
            throw Exception("Server Error Code: $responseCode ${errorMsg?.let { " - $it" } ?: ""}")
        }
    }

    private suspend fun parseAndReturnResult(responseString: String, onResult: (String, String, String) -> Unit) {
        try {
            val jsonObject = JSONObject(responseString)
            
            val privateKey = jsonObject.optString("private_key", "")
            val address = jsonObject.optString("address", "")
            val endpoint = jsonObject.optString("endpoint", "162.159.192.1:2408")
            
            if (privateKey.isNotEmpty() && address.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    onResult(privateKey, address, endpoint)
                }
            } else {
                throw Exception("Invalid Data from API")
            }
        } catch (e: Exception) {
            throw Exception("JSON Parse Error")
        }
    }
}
