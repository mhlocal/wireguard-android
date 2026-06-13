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
        
        // ⚠️ သင့်၏ API လင့်တစ်ခုတည်းကိုသာ ဤနေရာတွင် ထည့်ပါ ⚠️
        val apiUrl = "yitgwcdttttjrnqtdncy.supabase.co/functions/v1/bright-worker/v0a5311/reg" 

        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d("WarpApi", "Connecting to API: $apiUrl")
                val response = makeNetworkRequest(apiUrl)
                parseAndReturnResult(response, onResult)

            } catch (e: Exception) {
                Log.e("WarpApi", "API Failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    // API တစ်ခုတည်းဖြစ်၍ မရပါက ချက်ချင်း Error ပြပါမည်
                    onError("API Error: ${e.message}")
                }
            }
        }
    }

    private fun makeNetworkRequest(urlString: String): String {
        val safeUrlString = if (urlString.startsWith("http://")) urlString.replace("http://", "https://") else urlString
        
        val url = URL(safeUrlString)
        val connection = url.openConnection() as HttpURLConnection
        
        // POST စနစ်ကို အသုံးပြုပြီး Bot ကာကွယ်ရေး Headers များ ထည့်သွင်းခြင်း
        connection.requestMethod = "POST"
        connection.doOutput = true 
        
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36")
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Content-Type", "application/json")
        
        connection.connectTimeout = 15000 
        connection.readTimeout = 15000
        
        // Body အလွတ် (Empty JSON) ပေးပို့ခြင်း
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
