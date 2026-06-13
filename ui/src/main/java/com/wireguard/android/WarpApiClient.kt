package com.wireguard.android

import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class WarpApiClient {

    companion object {
        private const val TAG = "WarpApiClient"
        private const val PRIMARY_API_ENDPOINT = "https://boxvpn.netlify.app/v0a884/reg"
        private const val FALLBACK_API_ENDPOINT = "https://yitgwcdttttjrnqtdncy.supabase.co/functions/v1/bright-worker/v0a5311/reg"
        private const val CONNECTION_TIMEOUT_MS = 5000
    }

    fun fetchConfiguration(
        onSuccess: (privateKey: String, address: String, endpoint: String) -> Unit,
        onFailure: (errorMessage: String) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d(TAG, "Initiating configuration fetch from primary endpoint.")
                val response = executeNetworkRequest(PRIMARY_API_ENDPOINT)
                processConfigResponse(response, onSuccess)

            } catch (primaryException: Exception) {
                Log.w(TAG, "Primary endpoint unavailable: ${primaryException.message}. Attempting fallback.")
                
                try {
                    val fallbackResponse = executeNetworkRequest(FALLBACK_API_ENDPOINT)
                    processConfigResponse(fallbackResponse, onSuccess)
                    
                } catch (fallbackException: Exception) {
                    Log.e(TAG, "All configuration endpoints failed.", fallbackException)
                    withContext(Dispatchers.Main) {
                        onFailure("Failed to connect to required services. Please check your network connection.")
                    }
                }
            }
        }
    }

    private fun executeNetworkRequest(endpointUrl: String): String {
        val url = URL(endpointUrl)
        val connection = url.openConnection() as HttpURLConnection
        
        connection.apply {
            requestMethod = "GET"
            connectTimeout = CONNECTION_TIMEOUT_MS
            readTimeout = CONNECTION_TIMEOUT_MS
            
            // Appending standard App headers to prevent bot-detection
            val userAgent = "WireGuard/${Build.VERSION.RELEASE} (Android ${Build.VERSION.SDK_INT}; ${Build.MANUFACTURER} ${Build.MODEL})"
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Connection", "Keep-Alive")
        }
        
        val responseCode = connection.responseCode
        if (responseCode == HttpURLConnection.HTTP_OK) {
            return connection.inputStream.bufferedReader().use { it.readText() }
        } else {
            throw Exception("Server responded with HTTP code: $responseCode")
        }
    }

    private suspend fun processConfigResponse(
        responsePayload: String, 
        onSuccess: (String, String, String) -> Unit
    ) {
        try {
            val jsonObject = JSONObject(responsePayload)
            
            val privateKey = jsonObject.optString("private_key", "")
            val address = jsonObject.optString("address", "")
            val endpoint = jsonObject.optString("endpoint", "162.159.192.1:2408")
            
            if (privateKey.isNotBlank() && address.isNotBlank()) {
                withContext(Dispatchers.Main) {
                    onSuccess(privateKey, address, endpoint)
                }
            } else {
                throw Exception("Malformed configuration payload received.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Payload parsing error", e)
            throw e 
        }
    }
}
