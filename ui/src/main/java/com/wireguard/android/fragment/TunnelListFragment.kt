/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.fragment

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.net.VpnService
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.wireguard.android.Application
import com.wireguard.android.PremiumManager
import com.wireguard.android.R
import com.wireguard.android.WarpApiClient
import com.wireguard.android.backend.Tunnel
import com.wireguard.android.databinding.ObservableKeyedRecyclerViewAdapter.RowConfigurationHandler
import com.wireguard.android.databinding.TunnelListFragmentBinding
import com.wireguard.android.databinding.TunnelListItemBinding
import com.wireguard.android.model.ObservableTunnel
import com.wireguard.android.updater.SnackbarUpdateShower
import com.wireguard.android.util.ErrorMessages
import com.wireguard.android.widget.MultiselectableRelativeLayout
import com.wireguard.config.Config
import com.wireguard.config.Interface
import com.wireguard.config.Peer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress

class TunnelListFragment : BaseFragment() {
    private val actionModeListener = ActionModeListener()
    private var actionMode: ActionMode? = null
    private var backPressedCallback: OnBackPressedCallback? = null
    private var binding: TunnelListFragmentBinding? = null

    private var pendingTunnelToConnect: ObservableTunnel? = null
    private val connectingTunnels = mutableSetOf<String>()
    
    private var loadingDialog: AlertDialog? = null
    private var statusCheckDialog: AlertDialog? = null
    
    private var userStatusText: String = "Checking Status..."

    fun isConnecting(tunnel: ObservableTunnel): Boolean {
        return connectingTunnels.contains(tunnel.name)
    }

    private fun setConnectingState(tunnel: ObservableTunnel, isConnecting: Boolean) {
        if (isConnecting) {
            connectingTunnels.add(tunnel.name)
        } else {
            connectingTunnels.remove(tunnel.name)
        }
        lifecycleScope.launch {
            val tunnels = Application.getTunnelManager().getTunnels()
            val position = tunnels.indexOf(tunnel)
            if (position >= 0) {
                binding?.tunnelList?.adapter?.notifyItemChanged(position)
            }
        }
    }

    private fun showStatusCheckDialog() {
        if (statusCheckDialog?.isShowing == true) return
        val safeContext = context ?: return
        val builder = AlertDialog.Builder(safeContext)
        builder.setTitle("Authenticating")
        builder.setMessage("Checking premium status...\n(Requires Internet)")
        builder.setCancelable(false)
        statusCheckDialog = builder.create()
        statusCheckDialog?.show()
    }

    private fun hideStatusCheckDialog() {
        statusCheckDialog?.dismiss()
        statusCheckDialog = null
    }

    private fun showLoadingDialog() {
        if (loadingDialog?.isShowing == true) return
        val safeContext = context ?: return
        val builder = AlertDialog.Builder(safeContext)
        builder.setTitle("Generating Servers")
        builder.setMessage("Please wait loading...")
        builder.setCancelable(false)
        loadingDialog = builder.create()
        loadingDialog?.show()
    }

    private fun hideLoadingDialog() {
        loadingDialog?.dismiss()
        loadingDialog = null
    }

    private val vpnPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            pendingTunnelToConnect?.let { connectTunnel(it) }
        } else {
            Toast.makeText(context, "VPN Permission Denied!", Toast.LENGTH_SHORT).show()
            binding?.tunnelList?.adapter?.notifyDataSetChanged()
        }
        pendingTunnelToConnect = null
    }

    private val snackbarUpdateShower = SnackbarUpdateShower(this)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (savedInstanceState != null) {
            val checkedItems = savedInstanceState.getIntegerArrayList(CHECKED_ITEMS)
            if (checkedItems != null) {
                for (i in checkedItems) actionModeListener.setItemChecked(i, true)
            }
        }

        // 🌟 ယခင်က စမ်းသပ်ခဲ့သော 'WARP' Tunnel အဟောင်းကြီးကို အလိုလို ရှင်းလင်းပေးမည့်အပိုင်း 🌟
        lifecycleScope.launch {
            try {
                val tunnels = Application.getTunnelManager().getTunnels()
                withContext(Dispatchers.IO) {
                    tunnels.firstOrNull { it.name.equals("WARP", ignoreCase = true) }?.let { Application.getTunnelManager().delete(it) }
                    tunnels.firstOrNull { it.name == "Server 1" }?.let { Application.getTunnelManager().delete(it) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Cleanup old tunnels failed", e)
            }
        }

        val premiumManager = PremiumManager(requireContext())
        val deviceId = android.provider.Settings.Secure.getString(requireContext().contentResolver, android.provider.Settings.Secure.ANDROID_ID)

        lifecycleScope.launch {
            premiumManager.checkTrialStatus(
                onLoading = {
                    lifecycleScope.launch { showStatusCheckDialog() }
                },
                onStatusResult = { daysLeft ->
                    lifecycleScope.launch {
                        hideStatusCheckDialog()
                        
                        userStatusText = "Premium ($daysLeft Days left)"
                        (activity as? AppCompatActivity)?.supportActionBar?.subtitle = "ID: $deviceId | $userStatusText"
                        
                        val prefs = Application.get().getSharedPreferences("VPN_PREFS", Context.MODE_PRIVATE)
                        val hasGenerated = prefs.getBoolean("has_generated_servers", false)
                        
                        // တစ်ခါမှ မထုတ်ရသေးမှသာ အသစ်ထုတ်ပေးမည်
                        if (!hasGenerated && !isGenerating) {
                            generateDualServers()
                        }
                    }
                },
                onExpired = {
                    lifecycleScope.launch {
                        hideStatusCheckDialog()
                        userStatusText = "EXPIRED"
                        (activity as? AppCompatActivity)?.supportActionBar?.subtitle = "ID: $deviceId | EXPIRED"
                        showPremiumExpiredDialog()
                    }
                }
            )
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        super.onCreateView(inflater, container, savedInstanceState)
        binding = TunnelListFragmentBinding.inflate(inflater, container, false)

        binding?.apply {
            createFab.setOnClickListener {
                val deviceId = android.provider.Settings.Secure.getString(requireContext().contentResolver, android.provider.Settings.Secure.ANDROID_ID)

                val premiumDialog = AlertDialog.Builder(requireContext())
                premiumDialog.setTitle("👑 User Information")
                premiumDialog.setMessage(
                    "User ID:\n$deviceId\n\n" +
                    "Status: $userStatusText\n\n" +
                    "--- Premium Plans ---\n" +
                    "5 month - 5000 Ks\n" +
                    "1 year - 10000 Ks\n\n" +
                    "ဆက်သွယ်ရန် Telegram: @mhwarpadmin"
                )
                
                premiumDialog.setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                
                premiumDialog.setNeutralButton("Copy ID") { _, _ ->
                    val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val clip = android.content.ClipData.newPlainText("Device ID", deviceId)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(requireContext(), "Device ID Copied!", Toast.LENGTH_SHORT).show()
                }
                
                premiumDialog.show()
            }
            executePendingBindings()
            snackbarUpdateShower.attach(mainContainer, createFab)
        }
        backPressedCallback = requireActivity().onBackPressedDispatcher.addCallback(this) { actionMode?.finish() }
        backPressedCallback?.isEnabled = false

        return binding?.root
    }

    private fun showPremiumExpiredDialog() {
        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle("Trial Expired")
        val deviceId = android.provider.Settings.Secure.getString(requireContext().contentResolver, android.provider.Settings.Secure.ANDROID_ID)
        builder.setMessage(
            "သင်၏ 7 ရက် Free Trial သက်တမ်း ကုန်ဆုံးသွားပါပြီ။ ဆက်လက်အသုံးပြုရန် Premium ဝယ်ယူပါ။\n\n" +
            "User ID:\n$deviceId\n\n" +
            "5 month - 5000 Ks\n" +
            "1 year - 10000 Ks\n\n" +
            "ဆက်သွယ်ရန် Telegram တွင် @mhwarpadmin လို့ရိုက်ရှာပြီး ဆက်သွယ်နိုင်ပါသည်။"
        )
        builder.setCancelable(false) 
        
        builder.setPositiveButton("Exit App") { _, _ ->
            activity?.finish() 
        }
        
        builder.setNeutralButton("Copy ID", null)
        
        val dialog = builder.create()
        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("Device ID", deviceId)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(requireContext(), "Device ID Copied!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun connectTunnel(tunnel: ObservableTunnel) {
        val safeContext = context ?: return
        val safeActivity = activity ?: return

        val vpnIntent = VpnService.prepare(safeContext)
        if (vpnIntent != null) {
            pendingTunnelToConnect = tunnel
            vpnPermissionLauncher.launch(vpnIntent)
            return
        }

        val tunnelManager = Application.getTunnelManager()
        lifecycleScope.launch {
            setConnectingState(tunnel, true)

            try {
                tunnelManager.setTunnelState(tunnel, Tunnel.State.UP)

                withContext(Dispatchers.IO) {
                    delay(2500) 
                    try {
                        val ipAddr = InetAddress.getByName("1.1.1.1")
                        val isInternetWorking = ipAddr.isReachable(3000)

                        safeActivity.runOnUiThread {
                            setConnectingState(tunnel, false)

                            if (!isInternetWorking) {
                                Toast.makeText(safeContext, "No Internet Access!", Toast.LENGTH_LONG).show()
                                lifecycleScope.launch { tunnelManager.setTunnelState(tunnel, Tunnel.State.DOWN) }
                            }
                        }
                    } catch (e: Exception) {
                        safeActivity.runOnUiThread {
                            setConnectingState(tunnel, false)
                            Toast.makeText(safeContext, "Connection Failed!", Toast.LENGTH_LONG).show()
                            lifecycleScope.launch { tunnelManager.setTunnelState(tunnel, Tunnel.State.DOWN) }
                        }
                    }
                }
            } catch (e: Exception) {
                setConnectingState(tunnel, false)
                Toast.makeText(safeContext, "Failed to start VPN", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun generateDualServers() {
        if (isGenerating) return
        isGenerating = true
        
        val safeContext = context ?: return
        val safeActivity = activity ?: return
        
        safeActivity.runOnUiThread { showLoadingDialog() }

        val warpApi = WarpApiClient()
        warpApi.generateWarpConfig(
            onResult = { privateKey, address, _ -> 
                try {
                    val config1 = buildWarpConfig(privateKey, address, "162.159.192.1:500")
                    val config2 = buildWarpConfig(privateKey, address, "162.159.195.4:500")

                    viewLifecycleOwner.lifecycleScope.launch {
                        try {
                            val tunnelManager = Application.getTunnelManager()
                            val tunnels = tunnelManager.getTunnels()
                            
                            tunnels.firstOrNull { it.name == "Server1" }?.let { tunnelManager.delete(it) }
                            tunnels.firstOrNull { it.name == "Server2" }?.let { tunnelManager.delete(it) }
                            
                            withContext(Dispatchers.IO) {
                                tunnelManager.create("Server1", config1)
                                tunnelManager.create("Server2", config2)
                            }
                            
                            val prefs = Application.get().getSharedPreferences("VPN_PREFS", Context.MODE_PRIVATE)
                            prefs.edit().putBoolean("has_generated_servers", true).apply()
                            
                            isGenerating = false
                            safeActivity.runOnUiThread { 
                                hideLoadingDialog() 
                                Toast.makeText(safeContext, "Servers generated successfully!", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            isGenerating = false
                            safeActivity.runOnUiThread {
                                hideLoadingDialog()
                                Log.e(TAG, "Save Error: ${e.message}", e)
                                Toast.makeText(safeContext, "Failed to save servers: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                } catch (e: Exception) {
                    isGenerating = false
                    safeActivity.runOnUiThread { hideLoadingDialog() }
                }
            },
            onError = { errorMessage ->
                isGenerating = false
                safeActivity.runOnUiThread {
                    hideLoadingDialog()
                    Toast.makeText(safeContext, errorMessage, Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    private fun buildWarpConfig(privateKey: String, address: String, endpoint: String): Config {
        val configBuilder = Config.Builder()
        val interfaceBuilder = Interface.Builder()
        interfaceBuilder.parsePrivateKey(privateKey)
        interfaceBuilder.parseAddresses(address)
        interfaceBuilder.parseDnsServers("1.1.1.1, 1.0.0.1")
        interfaceBuilder.parseMtu("1280") 

        val peerBuilder = Peer.Builder()
        peerBuilder.parsePublicKey("bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfTz0=") 
        peerBuilder.parseEndpoint(endpoint)
        peerBuilder.parseAllowedIPs("0.0.0.0/0, ::/0")
        peerBuilder.parsePersistentKeepalive("25") 

        configBuilder.setInterface(interfaceBuilder.build())
        configBuilder.addPeer(peerBuilder.build())
        return configBuilder.build()
    }

    // 🌟 Auto ချိတ်ခြင်းကို ကာကွယ်ရန် သီးသန့် Logic (UI လည်ထွက်ခြင်းကို တားဆီးထားသည်) 🌟
    fun onSwitchChanged(view: View, checked: Boolean) {
        val tunnel = view.tag as? ObservableTunnel ?: return 
        
        // DataBinding ကနေ UI ကို Update လုပ်ပေးတဲ့အခါမှာ ခလုတ်အနှိပ်ခံရသလို Auto ဝင်လာတတ်ပါတယ်။
        // ဒါကြောင့် အစစ်အမှန် အခြေအနေနဲ့ တူနေရင် (ဥပမာ - ပိတ်ထားတာကို ပိတ်တယ်လို့ ထပ်ပို့ရင်) လျစ်လျူရှုပါမည်။
        val isCurrentlyUp = tunnel.state == Tunnel.State.UP
        if (checked == isCurrentlyUp) return
        
        if (checked) {
            connectTunnel(tunnel)
        } else {
            lifecycleScope.launch {
                try {
                    setConnectingState(tunnel, false)
                    Application.getTunnelManager().setTunnelState(tunnel, Tunnel.State.DOWN)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to stop VPN", e)
                }
            }
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putIntegerArrayList(CHECKED_ITEMS, actionModeListener.getCheckedItems())
    }

    override fun onSelectedTunnelChanged(oldTunnel: ObservableTunnel?, newTunnel: ObservableTunnel?) {
        binding ?: return
        lifecycleScope.launch {
            val tunnels = Application.getTunnelManager().getTunnels()
            if (newTunnel != null) viewForTunnel(newTunnel, tunnels)?.setSingleSelected(true)
            if (oldTunnel != null) viewForTunnel(oldTunnel, tunnels)?.setSingleSelected(false)
        }
    }

    private fun onTunnelDeletionFinished(count: Int, throwable: Throwable?) {
        val message: String
        val ctx = activity ?: Application.get()
        if (throwable == null) {
            message = ctx.resources.getQuantityString(R.plurals.delete_success, count, count)
        } else {
            val error = ErrorMessages[throwable]
            message = ctx.resources.getQuantityString(R.plurals.delete_error, count, count, error)
            Log.e(TAG, message, throwable)
        }
        showSnackbar(message)
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        binding ?: return
        binding!!.fragment = this
        lifecycleScope.launch { binding!!.tunnels = Application.getTunnelManager().getTunnels() }
        binding!!.rowConfigurationHandler = object : RowConfigurationHandler<TunnelListItemBinding, ObservableTunnel> {
            override fun onConfigureRow(binding: TunnelListItemBinding, item: ObservableTunnel, position: Int) {
                binding.fragment = this@TunnelListFragment
                
                binding.root.setOnClickListener {
                    if (actionMode == null) {
                        selectedTunnel = item
                    } else {
                        actionModeListener.toggleItemChecked(position)
                    }
                }
                
                binding.root.setOnLongClickListener {
                    actionModeListener.toggleItemChecked(position)
                    true
                }
                
                if (actionMode != null)
                    (binding.root as MultiselectableRelativeLayout).setMultiSelected(actionModeListener.checkedItems.contains(position))
                else
                    (binding.root as MultiselectableRelativeLayout).setSingleSelected(selectedTunnel == item)
            }
        }
    }

    private fun showSnackbar(message: CharSequence) {
        val binding = binding
        if (binding != null)
            Snackbar.make(binding.mainContainer, message, Snackbar.LENGTH_LONG)
                .setAnchorView(binding.createFab)
                .show()
        else
            Toast.makeText(activity ?: Application.get(), message, Toast.LENGTH_SHORT).show()
    }

    private fun viewForTunnel(tunnel: ObservableTunnel, tunnels: List<*>): MultiselectableRelativeLayout? {
        return binding?.tunnelList?.findViewHolderForAdapterPosition(tunnels.indexOf(tunnel))?.itemView as? MultiselectableRelativeLayout
    }

    private inner class ActionModeListener : ActionMode.Callback {
        val checkedItems: MutableCollection<Int> = HashSet()
        private var resources: Resources? = null

        fun getCheckedItems(): ArrayList<Int> {
            return ArrayList(checkedItems)
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            return when (item.itemId) {
                R.id.menu_action_delete -> {
                    val activity = activity ?: return true
                    val copyCheckedItems = HashSet(checkedItems)
                    binding?.createFab?.apply {
                        visibility = View.VISIBLE
                        scaleX = 1f
                        scaleY = 1f
                    }
                    activity.lifecycleScope.launch {
                        try {
                            val tunnels = Application.getTunnelManager().getTunnels()
                            val tunnelsToDelete = ArrayList<ObservableTunnel>()
                            for (position in copyCheckedItems) tunnelsToDelete.add(tunnels[position])
                            val futures = tunnelsToDelete.map { async(SupervisorJob()) { it.deleteAsync() } }
                            onTunnelDeletionFinished(futures.awaitAll().size, null)
                        } catch (e: Throwable) {
                            onTunnelDeletionFinished(0, e)
                        }
                    }
                    checkedItems.clear()
                    mode.finish()
                    true
                }

                R.id.menu_action_select_all -> {
                    lifecycleScope.launch {
                        val tunnels = Application.getTunnelManager().getTunnels()
                        for (i in 0 until tunnels.size) {
                            setItemChecked(i, true)
                        }
                    }
                    true
                }

                else -> false
            }
        }

        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            actionMode = mode
            backPressedCallback?.isEnabled = true
            if (activity != null) {
                resources = activity!!.resources
            }
            animateFab(binding?.createFab, false)
            mode.menuInflater.inflate(R.menu.tunnel_list_action_mode, menu)
            binding?.tunnelList?.adapter?.notifyDataSetChanged()
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            actionMode = null
            backPressedCallback?.isEnabled = false
            resources = null
            animateFab(binding?.createFab, true)
            checkedItems.clear()
            binding?.tunnelList?.adapter?.notifyDataSetChanged()
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            updateTitle(mode)
            return false
        }

        fun setItemChecked(position: Int, checked: Boolean) {
            if (checked) {
                checkedItems.add(position)
            } else {
                checkedItems.remove(position)
            }
            val adapter = if (binding == null) null else binding!!.tunnelList.adapter
            if (actionMode == null && !checkedItems.isEmpty() && activity != null) {
                (activity as AppCompatActivity).startSupportActionMode(this)
            } else if (actionMode != null && checkedItems.isEmpty()) {
                actionMode!!.finish()
            }
            adapter?.notifyItemChanged(position)
            updateTitle(actionMode)
        }

        fun toggleItemChecked(position: Int) {
            setItemChecked(position, !checkedItems.contains(position))
        }

        private fun updateTitle(mode: ActionMode?) {
            if (mode == null) {
                return
            }
            val count = checkedItems.size
            if (count == 0) {
                mode.title = ""
            } else {
                mode.title = resources!!.getQuantityString(R.plurals.delete_title, count, count)
            }
        }

        private fun animateFab(view: View?, show: Boolean) {
            view ?: return
            val animation = AnimationUtils.loadAnimation(
                context, if (show) R.anim.scale_up else R.anim.scale_down
            )
            animation.setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationRepeat(animation: Animation?) {}
                override fun onAnimationEnd(animation: Animation?) {
                    if (!show) view.visibility = View.GONE
                }
                override fun onAnimationStart(animation: Animation?) {
                    if (show) view.visibility = View.VISIBLE
                }
            })
            view.startAnimation(animation)
        }
    }

    companion object {
        private const val CHECKED_ITEMS = "CHECKED_ITEMS"
        private const val TAG = "WireGuard/TunnelListFragment"
        
        private var isGenerating = false
    }
}
