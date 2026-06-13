package com.wireguard.android

import com.wireguard.crypto.KeyPair
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

class WarpApiClient {
    private val client = OkHttpClient()

    fun generateWarpConfig(onResult: (privateKey: String, address: String, endpoint: String) -> Unit, onError: (String) -> Unit) {
        val keyPair = KeyPair()
        val publicKeyBase64 = keyPair.publicKey.toBase64()
        val privateKeyBase64 = keyPair.privateKey.toBase64()

        val jsonBody = JSONObject().apply {
            put("key", publicKeyBase64)
            put("install_id", "")
            put("fcm_token", "")
            put("tos", "2019-11-06T00:00:00.000Z")
            put("model", "Android")
            put("locale", "en_US")
        }

        val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("https://boxvpn.netlify.app/v0a884/reg")
            .addHeader("User-Agent", "okhttp/3.12.1")
            .addHeader("CF-Client-Version", "a-6.11-2223")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onError("Network Error: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                val responseData = response.body?.string() ?: "Empty Response"
                
                if (response.isSuccessful) {
                    try {
                        val jsonResponse = JSONObject(responseData)
                        
                        // "result" ထဲမှာ မဟုတ်ဘဲ အပေါ်ယံက "config" ကို တိုက်ရိုက်ဆွဲထုတ်ပါမည်
                        val config = jsonResponse.getJSONObject("config")
                        val interfaceInfo = config.getJSONObject("interface")
                        val address = interfaceInfo.getJSONObject("addresses").getString("v4") + "/32"
                        
                        val endpoint = "engage.cloudflareclient.com:500" 

                        onResult(privateKeyBase64, address, endpoint)
                    } catch (e: Exception) {
                        val shortResponse = if (responseData.length > 200) responseData.substring(0, 200) + "..." else responseData
                        onError("Parse Error: ${e.message} | Data: $shortResponse")
                    }
                } else {
                    val shortResponse = if (responseData.length > 200) responseData.substring(0, 200) + "..." else responseData
                    onError("HTTP ${response.code}: $shortResponse")
                }
            }
        })
    }
}
