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

    // 🌟 C++ Library ကို ချိတ်ဆက်ခြင်း 🌟
    init {
        System.loadLibrary("api-keys")
    }

    // 🌟 C++ ထဲမှ Function များကို လှမ်းခေါ်ရန် ကြေညာခြင်း 🌟
    private external fun getProxyUrl1(): String
    private external fun getProxyUrl2(): String

    // 🌟 C++ မှ ဖျောက်ထားသော URL များကို ရယူခြင်း 🌟
    private val proxyUrl1 = getProxyUrl1()
    private val proxyUrl2 = getProxyUrl2()

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

        // API ခေါ်မည့် လုပ်ငန်းစဉ်ကို သီးသန့် Function ခွဲထုတ်ထားပါသည်
        fun tryApi(isFirstApi: Boolean) {
            val targetUrl = if (isFirstApi) proxyUrl1 else proxyUrl2
            Log.d("WARP_API", "Trying API: $targetUrl")

            val request = Request.Builder()
                .url(targetUrl)
                .addHeader("User-Agent", "okhttp/3.12.1")
                .addHeader("CF-Client-Version", "a-6.11-2223")
                .post(requestBody)
                .build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (isFirstApi) {
                        Log.w("WARP_API", "API 1 Failed. Trying API 2...")
                        tryApi(false) // ပထမတစ်ခု ကျရှုံးပါက ဒုတိယတစ်ခုကို ဆက်ခေါ်ပါမည်
                    } else {
                        onError("Both APIs Failed. Network Error: ${e.message}")
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    val responseData = response.body?.string() ?: "Empty Response"
                    
                    if (response.isSuccessful) {
                        try {
                            val jsonResponse = JSONObject(responseData)
                            val config = jsonResponse.getJSONObject("config")
                            val interfaceInfo = config.getJSONObject("interface")
                            val address = interfaceInfo.getJSONObject("addresses").getString("v4") + "/32"
                            
                            val endpoint = "engage.cloudflareclient.com:500" 

                            onResult(privateKeyBase64, address, endpoint)
                        } catch (e: Exception) {
                            if (isFirstApi) {
                                tryApi(false)
                            } else {
                                onError("Parse Error on both APIs: ${e.message}")
                            }
                        }
                    } else {
                        if (isFirstApi) {
                            Log.w("WARP_API", "API 1 HTTP Error (${response.code}). Trying API 2...")
                            tryApi(false) // ပထမတစ်ခု 403/500 စသည့် Error တက်ပါက ဒုတိယသို့ ကူးပါမည်
                        } else {
                            val shortResponse = if (responseData.length > 200) responseData.substring(0, 200) + "..." else responseData
                            onError("Both APIs Failed. Last HTTP ${response.code}: $shortResponse")
                        }
                    }
                }
            })
        }

        // လုပ်ငန်းစဉ်ကို ပထမဆုံး API (True) ဖြင့် စတင်ပါမည်
        tryApi(true)
    }
}
