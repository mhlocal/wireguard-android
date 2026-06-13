/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.activity

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.addCallback
import androidx.appcompat.app.ActionBar
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentTransaction
import androidx.fragment.app.commit
import com.wireguard.android.R
import com.wireguard.android.fragment.TunnelDetailFragment
import com.wireguard.android.fragment.TunnelEditorFragment
import com.wireguard.android.model.ObservableTunnel
import android.widget.Toast
import com.wireguard.config.Config
import com.wireguard.config.Interface
import com.wireguard.config.Peer
import com.wireguard.android.backend.Tunnel
import android.util.Log
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.wireguard.android.WarpApiClient

/**
 * CRUD interface for WireGuard tunnels. This activity serves as the main entry point to the
 * WireGuard application, and contains several fragments for listing, viewing details of, and
 * editing the configuration and interface state of WireGuard tunnels.
 */
class MainActivity : BaseActivity(), FragmentManager.OnBackStackChangedListener {
    private var actionBar: ActionBar? = null
    private var isTwoPaneLayout = false
    private var backPressedCallback: OnBackPressedCallback? = null

    private fun handleBackPressed() {
        val backStackEntries = supportFragmentManager.backStackEntryCount
        // If the two-pane layout does not have an editor open, going back should exit the app.
        if (isTwoPaneLayout && backStackEntries <= 1) {
            finish()
            return
        }

        if (backStackEntries >= 1)
            supportFragmentManager.popBackStack()

        // Deselect the current tunnel on navigating back from the detail pane to the one-pane list.
        if (backStackEntries == 1)
            selectedTunnel = null
    }

    override fun onBackStackChanged() {
        val backStackEntries = supportFragmentManager.backStackEntryCount
        backPressedCallback?.isEnabled = backStackEntries >= 1
        if (actionBar == null) return
        // Do not show the home menu when the two-pane layout is at the detail view (see above).
        val minBackStackEntries = if (isTwoPaneLayout) 2 else 1
        actionBar!!.setDisplayHomeAsUpEnabled(backStackEntries >= minBackStackEntries)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main_activity)
        actionBar = supportActionBar
        isTwoPaneLayout = findViewById<View?>(R.id.master_detail_wrapper) != null
        supportFragmentManager.addOnBackStackChangedListener(this)
        backPressedCallback = onBackPressedDispatcher.addCallback(this) { handleBackPressed() }
        onBackStackChanged()

        // မျက်နှာပြင် ပွင့်လာတာနဲ့ WARP Config ကို Auto Generate လုပ်ပါမယ်
        val warpApi = WarpApiClient()
        warpApi.generateWarpConfig(
            onResult = { privateKey, address, endpoint ->
                Log.d("WARP", "Success! PrivateKey: $privateKey, IP: $address, Endpoint: $endpoint")

                try {
                    val configBuilder = Config.Builder()
                    val interfaceBuilder = Interface.Builder()
                    interfaceBuilder.parsePrivateKey(privateKey)
                    interfaceBuilder.parseAddresses(address)
                    interfaceBuilder.parseDnsServers("1.1.1.1, 1.0.0.1")

                    val peerBuilder = Peer.Builder()
                    peerBuilder.parsePublicKey("bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfTz0=") 
                    peerBuilder.parseEndpoint(endpoint)
                    peerBuilder.parseAllowedIPs("0.0.0.0/0, ::/0")

                    configBuilder.setInterface(interfaceBuilder.build())
                    configBuilder.addPeer(peerBuilder.build())
                    val wgConfig = configBuilder.build()

                    // Suspend Function များကို အသုံးပြုရန် Coroutine ဖြင့် Background တွင် အလုပ်လုပ်ခိုင်းခြင်း
                    lifecycleScope.launch {
                        try {
                            val tunnelManager = com.wireguard.android.Application.getTunnelManager()

                            // "WARP" အမည်ဖြင့် ရှိပြီးသား Tunnel ရှိလျှင် အရင်ဖျက်ပါမည်
                            val existingTunnel = tunnelManager.getTunnels()["WARP"]
                            if (existingTunnel != null) {
                                tunnelManager.delete(existingTunnel)
                            }

                            // Tunnel အသစ်ဖန်တီး၍ တိုက်ရိုက် ချိတ်ဆက်ပါမည်
                            val tunnel = tunnelManager.create("WARP", wgConfig)
                            tunnelManager.setTunnelState(tunnel, Tunnel.State.UP)
                            
                            Toast.makeText(this@MainActivity, "Connected to WARP VPN!", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Log.e("WARP", "Tunnel creation failed", e)
                            Toast.makeText(this@MainActivity, "Failed to create VPN", Toast.LENGTH_SHORT).show()
                        }
                    }

                } catch (e: Exception) {
                    Log.e("WARP", "Config Error: ${e.message}")
                }
            },
            onError = { errorMessage ->
                Log.e("WARP", "Error generating config: $errorMessage")
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "API Error: $errorMessage", Toast.LENGTH_LONG).show()
                }
            }
        )
    }
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                // The back arrow in the action bar should act the same as the back button.
                onBackPressedDispatcher.onBackPressed()
                true
            }

            R.id.menu_action_edit -> {
                supportFragmentManager.commit {
                    replace(if (isTwoPaneLayout) R.id.detail_container else R.id.list_detail_container, TunnelEditorFragment())
                    setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
                    addToBackStack(null)
                }
                true
            }
            // This menu item is handled by the editor fragment.
            R.id.menu_action_save -> false
            R.id.menu_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onSelectedTunnelChanged(
        oldTunnel: ObservableTunnel?,
        newTunnel: ObservableTunnel?
    ): Boolean {
        val fragmentManager = supportFragmentManager
        if (fragmentManager.isStateSaved) {
            return false
        }

        val backStackEntries = fragmentManager.backStackEntryCount
        if (newTunnel == null) {
            // Clear everything off the back stack (all editors and detail fragments).
            fragmentManager.popBackStackImmediate(0, FragmentManager.POP_BACK_STACK_INCLUSIVE)
            return true
        }
        if (backStackEntries == 2) {
            // Pop the editor off the back stack to reveal the detail fragment. Use the immediate
            // method to avoid the editor picking up the new tunnel while it is still visible.
            fragmentManager.popBackStackImmediate()
        } else if (backStackEntries == 0) {
            // Create and show a new detail fragment.
            fragmentManager.commit {
                add(if (isTwoPaneLayout) R.id.detail_container else R.id.list_detail_container, TunnelDetailFragment())
                setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
                addToBackStack(null)
            }
        }
        return true
    }
}
