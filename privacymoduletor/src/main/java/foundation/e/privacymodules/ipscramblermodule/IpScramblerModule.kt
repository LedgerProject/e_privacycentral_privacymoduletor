/*
 * Copyright (C) 2021 E FOUNDATION
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package foundation.e.privacymodules.ipscrambler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import foundation.e.privacymodules.ipscramblermodule.IIpScramblerModule
import foundation.e.privacymodules.ipscramblermodule.IIpScramblerModule.Listener
import foundation.e.privacymodules.ipscramblermodule.IIpScramblerModule.Status
import org.torproject.android.service.OrbotService
import org.torproject.android.service.TorServiceConstants
import org.torproject.android.service.util.Prefs
import org.torproject.android.service.vpn.VpnPrefs
import android.service.notification.StatusBarNotification

import android.content.Context.NOTIFICATION_SERVICE

import androidx.core.content.ContextCompat.getSystemService

import android.app.NotificationManager
import android.os.Bundle
import java.security.InvalidParameterException
import java.util.*


class IpScramblerModule(private val context: Context): IIpScramblerModule {
    companion object {
        const val TAG = "IpScramblerModule"

        private val EXIT_COUNTRY_CODES = setOf("DE", "AT", "SE", "CH", "IS", "CA", "US", "ES", "FR", "BG", "PL", "AU", "BR", "CZ", "DK", "FI", "GB", "HU", "NL", "JP", "RO", "RU", "SG", "SK")

        // Key where exit country is stored by orbot service.
        private const val PREFS_KEY_EXIT_NODES = "pref_exit_nodes"
        // Copy of the package private OrbotService.NOTIFY_ID value.
        const val ORBOT_SERVICE_NOTIFY_ID_COPY = 1
    }

    private var currentStatus: Status? = null
    private val listeners = mutableSetOf<Listener>()

    private val localBroadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return
            val msg = messageHandler.obtainMessage()
            msg.obj = action
            msg.data = intent.extras
            messageHandler.sendMessage(msg)
        }
    }

    private val messageHandler: Handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            val action = msg.obj as? String ?: return
            val data = msg.data
            try {
                data.getString(TorServiceConstants.EXTRA_STATUS)?.let {
                    val newStatus = Status.valueOf(it)
                    if (currentStatus == Status.STARTING && newStatus == Status.ON) {
                        // Wait for bandwidth action to ensure true start.
                        if (action == TorServiceConstants.LOCAL_ACTION_BANDWIDTH) {
                            updateStatus(newStatus, force = true)
                        }
                    } else {
                        updateStatus(newStatus,
                            force = action == TorServiceConstants.ACTION_STATUS)
                    }
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

        Prefs.getSharedPrefs(context).edit()
            .putInt(VpnPrefs.PREFS_DNS_PORT, TorServiceConstants.TOR_DNS_PORT_DEFAULT)
            .apply()
    }

    private fun updateStatus(status: Status, force: Boolean = false) {
        if (force || status != currentStatus) {
            currentStatus = status
            listeners.forEach {
                it.onStatusChanged(status)
            }
        }
    }

    private fun isServiceRunning(): Boolean {
        // Check if the service is working, by looking at the presence of the notification
        val notificationManager = context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager?
        return notificationManager?.activeNotifications?.find { it.id == ORBOT_SERVICE_NOTIFY_ID_COPY } != null
    }

    private fun newLog(message: String) {
        listeners.forEach { it.log(message) }
    }

    private fun trafficUpdate(upload: Long, download: Long, read: Long, write: Long) {
        listeners.forEach { it.onTrafficUpdate(upload, download, read, write) }
    }

    private fun sendIntentToService(action: String, extra: Bundle? = null) {
        val intent = Intent(context, OrbotService::class.java)
        intent.action = action
        extra?.let { intent.putExtras(it) }
        context.startService(intent)
    }

    private fun saveTorifiedApps(packageNames: Collection<String>) {
        packageNames.joinToString("|")
        Prefs.getSharedPrefs(context).edit().putString(
            VpnPrefs.PREFS_KEY_TORIFIED, packageNames.joinToString("|")
        ).commit()

        if (isServiceRunning()) {
            sendIntentToService(TorServiceConstants.ACTION_STOP_VPN)
            sendIntentToService(TorServiceConstants.ACTION_START_VPN)
        }
    }

    private fun getTorifiedApps(): Set<String> {
        val list = Prefs.getSharedPrefs(context).getString(VpnPrefs.PREFS_KEY_TORIFIED, "")
            ?.split("|")
        return if (list == null ||list == listOf("")) {
            emptySet()
        } else {
            list.toSet()
        }
    }

    private fun setExitCountryCode(countryCode: String) {
        val countryParam = if (countryCode.isEmpty()) ""
        else if (countryCode in EXIT_COUNTRY_CODES) "{$countryCode}"
        else throw InvalidParameterException("Only these countries are available: ${EXIT_COUNTRY_CODES.joinToString { ", " } }")

        if (isServiceRunning()) {
            val extra = Bundle()
            extra.putString("exit", countryParam)
            sendIntentToService(TorServiceConstants.CMD_SET_EXIT, extra)
        } else {
            Prefs.getSharedPrefs(context)
                .edit().putString(PREFS_KEY_EXIT_NODES, countryParam)
                .commit()
        }
    }

    private fun getExitCountryCode(): String {
        val raw = Prefs.getExitNodes()
        return if (raw.isEmpty()) raw else raw.slice(1..3)
    }

    override fun prepareAndroidVpn(): Intent? {
        return VpnService.prepare(context)
    }

    override fun start() {
        Prefs.putUseVpn(true)
        Prefs.putStartOnBoot(true)

        // TODO: should check for prepare VPN ?
        sendIntentToService(TorServiceConstants.ACTION_START)
        sendIntentToService(TorServiceConstants.ACTION_START_VPN)
    }

    override fun stop() {
        Prefs.putUseVpn(false)
        Prefs.putStartOnBoot(false)

        sendIntentToService(TorServiceConstants.ACTION_STOP_VPN)
        context.stopService(Intent(context, OrbotService::class.java))
    }

    override fun requestStatus() {
        if (isServiceRunning()) {
            sendIntentToService(TorServiceConstants.ACTION_STATUS)
        } else {
            updateStatus(Status.OFF, force = true)
        }
    }

    override var appList: Set<String>
        get() = getTorifiedApps()
        set(value) = saveTorifiedApps(value)

    override var exitCountry: String
        get() = getExitCountryCode()
        set(value) = setExitCountryCode(value)

    override fun getAvailablesLocations(): Set<String> = EXIT_COUNTRY_CODES


    override var httpProxyPort: Int = -1
        private set

    override var socksProxyPort: Int = -1
        private set


    override fun addListener(listener: Listener) {
        listeners.add(listener)
    }
    override fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }
    override fun clearListeners() {
        listeners.clear()
    }

    override fun onCleared() {
        LocalBroadcastManager.getInstance(context).unregisterReceiver(localBroadcastReceiver)
    }
}
