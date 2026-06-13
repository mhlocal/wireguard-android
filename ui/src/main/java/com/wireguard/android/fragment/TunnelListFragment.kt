/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.fragment

import android.app.Activity
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
import com.google.zxing.qrcode.QRCodeReader
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.wireguard.android.Application
import com.wireguard.android.R
import com.wireguard.android.WarpApiClient
import com.wireguard.android.activity.TunnelCreatorActivity
import com.wireguard.android.backend.Tunnel
import com.wireguard.android.databinding.ObservableKeyedRecyclerViewAdapter.RowConfigurationHandler
import com.wireguard.android.databinding.TunnelListFragmentBinding
import com.wireguard.android.databinding.TunnelListItemBinding
import com.wireguard.android.model.ObservableTunnel
import com.wireguard.android.updater.SnackbarUpdateShower
import com.wireguard.android.util.ErrorMessages
import com.wireguard.android.util.QrCodeFromFileScanner
import com.wireguard.android.util.TunnelImporter
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

    // VPN Permission တောင်းခံရန် သိမ်းထားမည့် နေရာ
    private var pendingTunnelToConnect: ObservableTunnel? = null
    
    // Connecting အခြေအနေကို မှတ်သားထားမည့် နေရာ (UI အရောင်ပြောင်းရန်)
    private val connectingTunnels = mutableSetOf<String>()

    // XML ဖိုင်မှ ဤ Function ကိုလှမ်းခေါ်၍ Connecting စာသား ပြ/မပြ ဆုံးဖြတ်ပါမည်
    fun isConnecting(tunnel: ObservableTunnel): Boolean {
        return connectingTunnels.contains(tunnel.name)
    }

    // UI ပေါ်ရှိ စာသားကို Connecting နှင့် Connected အဖြစ် အလိုအလျောက် ပြောင်းပေးမည့် Function
    private fun setConnectingState(tunnel: ObservableTunnel, isConnecting: Boolean) {
        if (isConnecting) {
            connectingTunnels.add(tunnel.name)
        } else {
            connectingTunnels.remove(tunnel.name)
        }
        
        // မျက်နှာပြင်ရှိ ခလုတ်စာသား အရောင်များကို Update လုပ်ခိုင်းပါမည်
        lifecycleScope.launch {
            val tunnels = Application.getTunnelManager().getTunnels()
            val position = tunnels.indexOf(tunnel)
            if (position >= 0) {
                binding?.tunnelList?.adapter?.notifyItemChanged(position)
            }
        }
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

    private val tunnelFileImportResultLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { data ->
        if (data == null) return@registerForActivityResult
        val activity = activity ?: return@registerForActivityResult
        val contentResolver = activity.contentResolver ?: return@registerForActivityResult
        activity.lifecycleScope.launch {
            if (QrCodeFromFileScanner.validContentType(contentResolver, data)) {
                try {
                    val qrCodeFromFileScanner = QrCodeFromFileScanner(contentResolver, QRCodeReader())
                    val result = qrCodeFromFileScanner.scan(data)
                    TunnelImporter.importTunnel(parentFragmentManager, result.text) { showSnackbar(it) }
                } catch (e: Exception) {
                    val error = ErrorMessages[e]
                    val message = Application.get().resources.getString(R.string.import_error, error)
                    Log.e(TAG, message, e)
                    showSnackbar(message)
                }
            } else {
                TunnelImporter.importTunnel(contentResolver, data) { showSnackbar(it) }
            }
        }
    }

    private val qrImportResultLauncher = registerForActivityResult(ScanContract()) { result ->
        val qrCode = result.contents
        val activity = activity
        if (qrCode != null && activity != null) {
            activity.lifecycleScope.launch { TunnelImporter.importTunnel(parentFragmentManager, qrCode) { showSnackbar(it) } }
        }
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
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        super.onCreateView(inflater, container, savedInstanceState)
        binding = TunnelListFragmentBinding.inflate(inflater, container, false)
        val bottomSheet = AddTunnelsSheet()

        binding?.apply {
            createFab.setOnClickListener {
                if (childFragmentManager.findFragmentByTag("BOTTOM_SHEET") != null)
                    return@setOnClickListener
                childFragmentManager.setFragmentResultListener(AddTunnelsSheet.REQUEST_KEY_NEW_TUNNEL, viewLifecycleOwner) { _, bundle ->
                    when (bundle.getString(AddTunnelsSheet.REQUEST_METHOD)) {
                        AddTunnelsSheet.REQUEST_CREATE -> {
                            startActivity(Intent(requireActivity(), TunnelCreatorActivity::class.java))
                        }
                        AddTunnelsSheet.REQUEST_IMPORT -> {
                            tunnelFileImportResultLauncher.launch("*/*")
                        }
                        AddTunnelsSheet.REQUEST_SCAN -> {
                            qrImportResultLauncher.launch(
                                ScanOptions()
                                    .setOrientationLocked(false)
                                    .setBeepEnabled(false)
                                    .setPrompt(getString(R.string.qr_code_hint))
                            )
                        }
                        "REQUEST_GENERATE_WARP" -> {
                            generateWarpConfigAndConnect()
                        }
                    }
                }
                bottomSheet.showNow(childFragmentManager, "BOTTOM_SHEET")
            }
            executePendingBindings()
            snackbarUpdateShower.attach(mainContainer, createFab)
        }
        backPressedCallback = requireActivity().onBackPressedDispatcher.addCallback(this) { actionMode?.finish() }
        backPressedCallback?.isEnabled = false

        return binding?.root
    }

    // ---------------------------------------------------------------------------------
    // VPN ချိတ်ဆက်ခြင်းနှင့် အင်တာနက် (Ping) စစ်ဆေးခြင်း ပင်မလုပ်ငန်းစဉ် 
    // ---------------------------------------------------------------------------------
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
            // ၁။ UI ပေါ်တွင် "Connecting..." (လိမ္မော်ရောင်) အဖြစ် စတင်ပြသပါမည်
            setConnectingState(tunnel, true)

            try {
                // VPN စတင်ဖွင့်ပါမည်
                tunnelManager.setTunnelState(tunnel, Tunnel.State.UP)

                withContext(Dispatchers.IO) {
                    delay(2500) 
                    try {
                        val ipAddr = InetAddress.getByName("1.1.1.1")
                        val isInternetWorking = ipAddr.isReachable(3000)

                        safeActivity.runOnUiThread {
                            // ၂။ Ping စစ်ဆေးပြီးပါက Connecting အခြေအနေကို ဖျက်ပါမည်
                            setConnectingState(tunnel, false)

                            if (!isInternetWorking) {
                                // အင်တာနက်မရလျှင် ပြန်ပိတ်ချပါမည် (UI သည် "Connect" မီးခိုးရောင် ပြန်ဖြစ်သွားမည်)
                                Toast.makeText(safeContext, "No Internet Access!", Toast.LENGTH_LONG).show()
                                lifecycleScope.launch { tunnelManager.setTunnelState(tunnel, Tunnel.State.DOWN) }
                            }
                            // အင်တာနက်ရလျှင် ဘာမှလုပ်စရာမလိုပါ။ UI သည် အလိုအလျောက် "Connected" (အစိမ်းရောင်) ဖြစ်နေပါမည်။
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
                Log.e(TAG, "Failed to start VPN", e)
            }
        }
    }

    private fun generateWarpConfigAndConnect() {
        val safeContext = context ?: return
        
        Toast.makeText(safeContext, "Generating WARP Config...", Toast.LENGTH_SHORT).show()

        val warpApi = WarpApiClient()
        warpApi.generateWarpConfig(
            onResult = { privateKey, address, endpoint ->
                try {
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
                    val wgConfig = configBuilder.build()

                    viewLifecycleOwner.lifecycleScope.launch {
                        try {
                            val tunnelManager = Application.getTunnelManager()
                            val existingTunnel = tunnelManager.getTunnels()["WARP"]
                            if (existingTunnel != null) {
                                tunnelManager.delete(existingTunnel)
                            }
                            
                            val tunnel = tunnelManager.create("WARP", wgConfig)
                            connectTunnel(tunnel)

                        } catch (e: Exception) {
                            Toast.makeText(safeContext, "Failed to create VPN", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    Log.e("WARP", "Config Error: ${e.message}")
                }
            },
            onError = { errorMessage ->
                activity?.runOnUiThread {
                    Toast.makeText(safeContext, "API Error: $errorMessage", Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    fun onSwitchChanged(view: View, checked: Boolean) {
        val tunnel = view.tag as? ObservableTunnel ?: return 
        if (checked) {
            connectTunnel(tunnel)
        } else {
            lifecycleScope.launch {
                try {
                    // Switch ကို ပိတ်ပါက ချက်ချင်း ပိတ်ပေးပါမည်
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
            /*    binding.root.setOnClickListener {
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
    }*/

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
    }
}
