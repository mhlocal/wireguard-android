// ---------------------------------------------------------------------------------
    // မျက်နှာပြင်ပေါ်ရှိ ခလုတ် (Switch) ကို ဖွင့်/ပိတ် လုပ်သည့်အခါ အလုပ်လုပ်မည့် Function
    // ---------------------------------------------------------------------------------
    fun onSwitchChanged(view: View, checked: Boolean) {
        val safeContext = context ?: return
        val safeActivity = activity ?: return
        
        // XML တွင် သတ်မှတ်ထားသော android:tag မှတစ်ဆင့် Tunnel ကို လှမ်းယူပါမည်
        val tunnel = view.tag as? ObservableTunnel ?: return 
        val tunnelManager = Application.getTunnelManager()
        
        lifecycleScope.launch {
            if (checked) {
                Toast.makeText(safeContext, "Connecting...", Toast.LENGTH_SHORT).show()
                try {
                    // VPN ကို အရင်ဖွင့်လိုက်ပါမည် (အစိမ်းရောင် ပြောင်းသွားပါမည်)
                    tunnelManager.setTunnelState(tunnel, Tunnel.State.UP)
                    
                    // နောက်ကွယ်မှ အင်တာနက် တကယ်ရ/မရ စစ်ဆေးပါမည်
                    withContext(Dispatchers.IO) {
                        delay(2500) // ၂.၅ စက္ကန့် စောင့်ပါမည်
                        try {
                            val ipAddr = InetAddress.getByName("1.1.1.1")
                            val isInternetWorking = ipAddr.isReachable(3000)
                            
                            safeActivity.runOnUiThread {
                                if (isInternetWorking) {
                                    Toast.makeText(safeContext, "Connected Successfully!", Toast.LENGTH_SHORT).show()
                                } else {
                                    // အင်တာနက် မရလျှင် VPN ကို အလိုအလျောက် ပြန်ပိတ်ချပါမည် (မီးခိုးရောင် ပြန်ဖြစ်သွားပါမည်)
                                    Toast.makeText(safeContext, "No Internet! Disconnecting...", Toast.LENGTH_LONG).show()
                                    lifecycleScope.launch { tunnelManager.setTunnelState(tunnel, Tunnel.State.DOWN) }
                                }
                            }
                        } catch (e: Exception) {
                            safeActivity.runOnUiThread {
                                Toast.makeText(safeContext, "Connection Failed! Disconnecting...", Toast.LENGTH_LONG).show()
                                lifecycleScope.launch { tunnelManager.setTunnelState(tunnel, Tunnel.State.DOWN) }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Toast.makeText(safeContext, "Failed to start VPN", Toast.LENGTH_SHORT).show()
                }
            } else {
                // Switch ကို ပိတ်လိုက်လျှင် VPN ကို ပိတ်ပါမည်
                try {
                    tunnelManager.setTunnelState(tunnel, Tunnel.State.DOWN)
                } catch (e: Exception) {
                    Log.e("WireGuard/TunnelListFragment", "Failed to stop VPN", e)
                }
            }
        }
    }
