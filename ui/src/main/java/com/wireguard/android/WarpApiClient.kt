package com.wireguard.android

import android.util.Log
import com.wireguard.crypto.KeyPair
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

class WarpApiClient {
    private val client = OkHttpClient()

    // WARP အကောင့်သစ်ဖန်တီးပြီး Config Data ပြန်တောင်းမည့် Function
    fun generateWarpConfig(onResult: (privateKey: String, address: String, endpoint: String) -> Unit, onError: (String) -> Unit) {
        // ၁။ WireGuard အတွက် Private Key နဲ့ Public Key အသစ်ထုတ်ခြင်း
        val keyPair = KeyPair()
        val publicKeyBase64 = keyPair.publicKey.toBase64()
        val privateKeyBase64 = keyPair.privateKey.toBase64()

        // ၂။ Cloudflare API ဆီပို့မည့် JSON Data တည်ဆောက်ခြင်း
        val jsonBody = JSONObject().apply {
            put("key", publicKeyBase64)
            put("install_id", "")
            put("fcm_token", "")
            put("tos", "2019-11-06T00:00:00.000Z")
            put("model", "Android")
            put("locale", "en_US")
        }

        val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaType())

        // ၃။ Cloudflare API သို့ POST Request ပို့ခြင်း
        val request = Request.Builder()
            .url("https://api.cloudflareclient.com/v0a884/reg")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onError("Network Error: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    val responseData = response.body?.string() ?: ""
                    try {
                        val jsonResponse = JSONObject(responseData)
                        // API က ပြန်ပို့လိုက်တဲ့ IP Address နဲ့ Endpoint ကို ဆွဲထုတ်ခြင်း
                        val config = jsonResponse.getJSONObject("result").getJSONObject("config")
                        val interfaceInfo = config.getJSONObject("interface")
                        val address = interfaceInfo.getJSONObject("addresses").getString("v4") + "/32"
                        
                        // Default WARP Endpoint (လိုအပ်ပါက Clean IP ပြောင်းထည့်ရန်)
                        val endpoint = "engage.cloudflareclient.com:2408" 

                        // အောင်မြင်ပါက Data များ ပြန်ပို့ပေးမည်
                        onResult(privateKeyBase64, address, endpoint)
                    } catch (e: Exception) {
                        onError("JSON Parse Error: ${e.message}")
                    }
                } else {
                    onError("API Error: ${response.code}")
                }
            }
        })
    }
}
