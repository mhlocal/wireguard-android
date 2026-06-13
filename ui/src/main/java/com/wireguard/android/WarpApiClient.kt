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
        connection.requestMethod = "GET"
        
        // Android App မှ ခေါ်ကြောင်း သက်သေပြရန် (Bot အဖြစ် သတ်မှတ်မခံရစေရန်)
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36")
        connection.setRequestProperty("Accept", "application/json")
        
        connection.connectTimeout = 15000 
        connection.readTimeout = 15000
        
        val responseCode = connection.responseCode
        if (responseCode in 200..299) {
            return connection.inputStream.bufferedReader().use { it.readText() }
        } else {
            throw Exception("Server Error Code: $responseCode")
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
