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
            .url("https://api.cloudflareclient.com/v0a884/reg")
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
                        val config = jsonResponse.getJSONObject("result").getJSONObject("config")
                        val interfaceInfo = config.getJSONObject("interface")
                        val address = interfaceInfo.getJSONObject("addresses").getString("v4") + "/32"
                        
                        val endpoint = "engage.cloudflareclient.com:2408" 

                        onResult(privateKeyBase64, address, endpoint)
                    } catch (e: Exception) {
                        // JSON Error တက်ပါက Cloudflare ပြန်ပို့သော စာသားအစစ်ကို ဖန်သားပြင်တွင် ပြပါမည်
                        // စာသားအရမ်းရှည်ပါက အစပိုင်းကိုသာ ဖြတ်ယူပါမည်
                        val shortResponse = if (responseData.length > 200) responseData.substring(0, 200) + "..." else responseData
                        onError("Reply: $shortResponse")
                    }
                } else {
                    // HTTP 403 စသည်ဖြင့် Error တက်ပါကလည်း စာသားအစစ်ကို ပြပါမည်
                    val shortResponse = if (responseData.length > 200) responseData.substring(0, 200) + "..." else responseData
                    onError("HTTP ${response.code}: $shortResponse")
                }
            }
        })
    }
}
