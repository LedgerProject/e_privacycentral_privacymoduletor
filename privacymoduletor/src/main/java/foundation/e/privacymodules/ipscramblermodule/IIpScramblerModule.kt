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

package foundation.e.privacymodules.ipscramblermodule

import android.content.Intent

interface IIpScramblerModule {
    fun prepareAndroidVpn(): Intent?

    fun start()

    fun stop()

    fun requestStatus()

    var appList: Set<String>

    var exitCountry: String

    val httpProxyPort: Int
    val socksProxyPort: Int



    fun addListener(listener: Listener)
    fun removeListener(listener: Listener)
    fun clearListeners()

    fun onCleared()

    interface Listener {
        fun onStatusChanged(newStatus: Status)
        fun log(message: String)
        fun onTrafficUpdate(upload: Long, download: Long, read: Long, write: Long)
    }

    enum class Status {
        OFF, ON, STARTING, STOPPING, START_DISABLED
    }
}