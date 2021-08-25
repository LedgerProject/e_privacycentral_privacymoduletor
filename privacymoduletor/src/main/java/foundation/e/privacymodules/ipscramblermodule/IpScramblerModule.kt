package foundation.e.privacymodules.ipscrambler

import android.content.*
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import org.torproject.android.service.OrbotService
import org.torproject.android.service.TorServiceConstants

import org.torproject.android.service.util.Prefs
import org.torproject.android.service.vpn.TorifiedApp
import org.torproject.android.service.vpn.VpnPrefs
import java.lang.Exception
import java.lang.StringBuilder

class IpScramblerModule(private val context: Context) {
    companion object {
        const val TAG = "IpScramblerModule"
    }

    private var currentStatus: Status? = null
    private val listeners = mutableSetOf<Listener>()

    /**
     * The state and log info from [OrbotService] are sent to the UI here in
     * the form of a local broadcast. Regular broadcasts can be sent by any app,
     * so local ones are used here so other apps cannot interfere with Orbot's
     * operation.
     */
    private val localBroadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return
            val msg = messageHandler.obtainMessage()
            msg.obj = action
            msg.data = intent.extras
            messageHandler.sendMessage(msg)
        }
    }

    // this is what takes messages or values from the callback threads or other non-mainUI threads
    //and passes them back into the main UI thread for display to the user
    private val messageHandler: Handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            val action = msg.obj as? String ?: return
            val data = msg.data
            try {
                data.getString(TorServiceConstants.EXTRA_STATUS)?.let {
                    updateStatus(Status.valueOf(it))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Can't parse Orbot service status.")
            }

            when (action) {
                TorServiceConstants.LOCAL_ACTION_LOG ->
                    data.getString(TorServiceConstants.LOCAL_EXTRA_LOG)?.let { newLog(it) }

                TorServiceConstants.LOCAL_ACTION_BANDWIDTH -> {
                    trafficUpdate(
                        data.getLong("up", 0),
                        data.getLong("down", 0),
                        data.getLong("written", 0),
                        data.getLong("read", 0)
                    )
                }

                TorServiceConstants.LOCAL_ACTION_PORTS -> {
                    httpProxyPort = data.getInt(OrbotService.EXTRA_HTTP_PROXY_PORT, -1)
                    socksProxyPort = data.getInt(OrbotService.EXTRA_SOCKS_PROXY_PORT, -1)
                }
            }
            super.handleMessage(msg)
        }
    }

    init {
        Prefs.setContext(context)
/* receive the internal status broadcasts, which are separate from the public
         * status broadcasts to prevent other apps from sending fake/wrong status
         * info to this app */

        /* receive the internal status broadcasts, which are separate from the public
         * status broadcasts to prevent other apps from sending fake/wrong status
         * info to this app */
        val lbm = LocalBroadcastManager.getInstance(context)
        lbm.registerReceiver(
            localBroadcastReceiver,
            IntentFilter(TorServiceConstants.ACTION_STATUS)
        )
        lbm.registerReceiver(
            localBroadcastReceiver,
            IntentFilter(TorServiceConstants.LOCAL_ACTION_BANDWIDTH)
        )
        lbm.registerReceiver(
            localBroadcastReceiver,
            IntentFilter(TorServiceConstants.LOCAL_ACTION_LOG)
        )
        lbm.registerReceiver(
            localBroadcastReceiver,
            IntentFilter(TorServiceConstants.LOCAL_ACTION_PORTS)
        )

        //mPrefs = Prefs.getSharedPrefs(getApplicationContext())

        /**
         * Resets previous DNS Port to the default
         */
        /**
         * Resets previous DNS Port to the default
         */
        Prefs.getSharedPrefs(context).edit()
            .putInt(VpnPrefs.PREFS_DNS_PORT, TorServiceConstants.TOR_DNS_PORT_DEFAULT)
            .apply()
    }


    fun onCleared() {
        LocalBroadcastManager.getInstance(context).unregisterReceiver(localBroadcastReceiver)
    }


    ////////////////////////////////////////////////////////////////////////////////////////////////
    // interface

    fun prepareAndroidVpn(): Intent? {
        return VpnService.prepare(context)
    }

    fun start() {
        Prefs.putUseVpn(true)
        Prefs.putStartOnBoot(true)

        // TODO: should check for prepare VPN ?
        sendIntentToService(TorServiceConstants.ACTION_START)
        sendIntentToService(TorServiceConstants.ACTION_START_VPN)
    }

    fun stop() {
        Prefs.putUseVpn(false)
        Prefs.putStartOnBoot(false)

        sendIntentToService(TorServiceConstants.ACTION_STOP_VPN)
        context.stopService(Intent(context, OrbotService::class.java))
    }

    fun requestStatus() {
        sendIntentToService(TorServiceConstants.ACTION_STATUS)
    }

    // TODO: fix interface
    var appList: Set<String>
        get() = getTorifiedApps()
        set(value) = saveTorifiedApps(value)

    var httpProxyPort: Int = -1
        private set

    var socksProxyPort: Int = -1
        private set


    fun addListener(listener: Listener) {
        listeners.add(listener)
    }
    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }
    fun clearListeners() {
        listeners.clear()
    }


    interface Listener {
        fun onStatusChanged(newStatus: Status)
        fun log(message: String)
        fun onTrafficUpdate(upload: Long, download: Long, read: Long, write: Long)
    }

    enum class Status {
        OFF, ON, STARTING, STOPPING, START_DISABLED
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////

    private fun updateStatus(status: Status) {
        if (status != currentStatus) {
            currentStatus = status
            listeners.forEach {
                it.onStatusChanged(status)
            }
        }
    }

    private fun newLog(message: String) {
        listeners.forEach { it.log(message) }
    }

    private fun trafficUpdate(upload: Long, download: Long, read: Long, write: Long) {
        listeners.forEach { it.onTrafficUpdate(upload, download, read, write) }
    }

    private fun sendIntentToService(action: String) {
        val intent = Intent(context, OrbotService::class.java)
        intent.action = action
        context.startService(intent)
    }

    private fun saveTorifiedApps(packageNames: Collection<String>) {
        packageNames.joinToString("|")
        Prefs.getSharedPrefs(context).edit().putString(
            VpnPrefs.PREFS_KEY_TORIFIED, packageNames.joinToString("|")
        ).commit()
    }

    private fun getTorifiedApps(): Set<String> {
        return Prefs.getSharedPrefs(context).getString(VpnPrefs.PREFS_KEY_TORIFIED, "")
            ?.split("|")?.toSet() ?: emptySet()
    }
}


