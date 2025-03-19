package com.github.xfalcon.vhosts.vservice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.github.xfalcon.vhosts.R
import com.github.xfalcon.vhosts.SettingsFragment
import com.github.xfalcon.vhosts.util.registerLocalBroadcast
import com.github.xfalcon.vhosts.util.removeLocalBroadcast
import com.github.xfalcon.vhosts.util.safeClose
import com.github.xfalcon.vhosts.util.sendLocalBroadcast
import com.github.xfalcon.vhosts.util.settings
import com.github.xfalcon.vhosts.util.toast
import java.io.IOException
import java.nio.channels.SocketChannel

private const val TAG = "VhostsService"

private const val PREFIX = "com.github.xfalcon.vhosts.VhostsActivity"
const val BROADCAST_VPN_STATE = "$PREFIX.VPN_STATE"
const val BROADCAST_CLOSE_SERVICE = "$PREFIX.BROADCAST_CLOSE_SERVICE"


var isVhostsServiceRunning = false
    private set


class VhostsService2: VpnService(), IProtector {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var ioWorkers: IOWorkers? = null

    private var stopServiceReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        setupNotification()
        setupHostsFile()
        setupVpn()
        vpnInterface ?: return

        setupIOWorkers()

        stopServiceReceiver = registerLocalBroadcast(BROADCAST_CLOSE_SERVICE) {
            vpnInterface?.safeClose()
            vpnInterface = null
        }
        isVhostsServiceRunning = true
        sendLocalBroadcast { Intent(BROADCAST_VPN_STATE) }
    }

    private fun setupNotification() {
        val channelId = "vhosts_channel_id"

        val channel = NotificationChannel(
            channelId,
            getString(R.string.runing_notice),
            NotificationManager.IMPORTANCE_HIGH
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)

        val notification = Notification.Builder(this, channelId)
            .setSmallIcon(R.mipmap.ic_launcher_foreground)
            .setContentText(getString(R.string.runing_notice))
            .setWhen(System.currentTimeMillis())
            .build()
        startForeground(1, notification)
    }

    private fun setupHostsFile() {
        val settings = settings()
        val hostsFileUri = settings.getString(SettingsFragment.HOSTS_URI, "")

        val error = try {
            // TODO 为什么要打开 SettingsFragment.NET_HOST_FILE ?
            contentResolver.openInputStream(Uri.parse(hostsFileUri))
                .use { DnsChange.handle_hosts(it) }
        } catch (e: IOException) {
            Log.e(TAG, "setupHostsFile: error setup host file service", e)
            -1
        }
        if (error <= 0) {
            toast {
                if (settings.getBoolean(SettingsFragment.IS_NET, false))
                    R.string.no_net_record
                else
                    R.string.no_local_record
            }
            stopSelf()
        }
    }

    private fun setupVpn() {
        val builder = Builder()
            .addAddress("192.0.2.111", 32)
//            .addAddress("fe80:49b1:7e4f:def2:e91f:95bf:fbb6:1111", 128)

        val settings = settings()
        val defaultDnsServer4 = getString(R.string.dns_server)
        val hasCustomDns = settings.getBoolean(SettingsFragment.IS_CUS_DNS, false)

        val dnsServer4 = if (hasCustomDns)
            settings.getString(SettingsFragment.IPV4_DNS, defaultDnsServer4) !!
        else
            defaultDnsServer4
        Log.d(TAG, "use dns: '$dnsServer4'")

        // 所有 dns 查询会先转发到 dnsServer4。由于 dnsServer4 命中了路由规则，
        // 这些 dns 查询会走到 vpn 网卡上，从而被我们所截获。
        // TODO 使用 0.0.0.0 做路由地址行不行 ?
        builder.addRoute(dnsServer4, 32)
            .addDnsServer(dnsServer4)
//            .addRoute("2001:4860:4860::8888", 128)
//            .addDnsServer("2001:4860:4860::8888")

        // shall we really need these ?
        arrayOf(
            "com.android.vending",
            "com.google.android.apps.docs",
            "com.google.android.apps.photos",
            "com.google.android.gm",
            "com.google.android.apps.translate",
        ).forEach {
            try {
                builder.addDisallowedApplication(it)
            } catch (e: PackageManager.NameNotFoundException) {
                Log.d(TAG, "setupVPN: failed to add app name to vpn service", e)
            }
        }

        vpnInterface = builder
            .setBlocking(true)
            .setSession(getString(R.string.app_name))
            .establish()

        // vpn 未授权或撤销授权
        if (vpnInterface == null) {
            stopSelf()
        }
    }

    private fun setupIOWorkers() {
        val fd = vpnInterface?.fileDescriptor ?: return
        ioWorkers = IOWorkers(fd, this).also { it.start() }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "onDestroy: dead")
        stopForeground(STOP_FOREGROUND_REMOVE)

        ioWorkers?.safeClose()
        ioWorkers = null

        vpnInterface?.safeClose()
        vpnInterface = null

        stopServiceReceiver?.let { removeLocalBroadcast(it) }
        stopServiceReceiver = null

        if (isVhostsServiceRunning) {
            isVhostsServiceRunning = false
            sendLocalBroadcast { Intent(BROADCAST_VPN_STATE) }
        }
    }

}