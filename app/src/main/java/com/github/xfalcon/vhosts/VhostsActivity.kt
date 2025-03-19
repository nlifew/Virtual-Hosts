package com.github.xfalcon.vhosts

import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import com.github.xfalcon.vhosts.databinding.ActivityVhostsBinding
import com.github.xfalcon.vhosts.util.doWhenActivityResultOk
import com.github.xfalcon.vhosts.util.registerLocalBroadcast
import com.github.xfalcon.vhosts.util.sendLocalBroadcast
import com.github.xfalcon.vhosts.util.settings
import com.github.xfalcon.vhosts.util.startActivity
import com.github.xfalcon.vhosts.util.withSpans
import com.github.xfalcon.vhosts.vservice.BROADCAST_CLOSE_SERVICE
import com.github.xfalcon.vhosts.vservice.BROADCAST_VPN_STATE
import com.github.xfalcon.vhosts.vservice.VhostsService2
import com.github.xfalcon.vhosts.vservice.isVhostsServiceRunning

private const val TAG = "VhostsActivity"

class VhostsActivity: AppCompatActivity() {

    private var waitingForVPNStart = false
    private lateinit var viewBinding: ActivityVhostsBinding

    private val selectFileLauncher = doWhenActivityResultOk {
        setUriByPREFS(it)
    }
    private val requestVpnPermissionLauncher = doWhenActivityResultOk {
        startVPN()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launch()

        viewBinding = ActivityVhostsBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        viewBinding.buttonStartVpn.setOnCheckedChangeListener { view, isChecked ->
            if (isChecked) {
                if (checkHostUri() == -1) {
                    showDialog()
                } else {
                    startVPN()
                }
            } else {
                shutdownVPN()
            }
        }

        viewBinding.buttonSelectHosts.also {
            it.setOnClickListener { selectFile() }
            it.setOnLongClickListener {
                startActivity<SettingsActivity>()
                false
            }
            if (checkHostUri() == -1) {
                it.text = getString(R.string.select_hosts)
            }
        }
        registerLocalBroadcast(BROADCAST_VPN_STATE) {
            if (isVhostsServiceRunning) {
                waitingForVPNStart = false
            }
        }
    }

    private fun launch() {
        val data = intent?.data?.toString() ?: return
        if ("on" == data) {
            if (!isVhostsServiceRunning) {
                startService(Intent(this, VhostsService2::class.java))
            }
            finish()
        } else if ("off" == data) {
            sendLocalBroadcast { Intent(BROADCAST_CLOSE_SERVICE) }
            finish()
        }
    }

    private fun selectFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.setType("*/*")
        try {
            val SHOW_ADVANCED = "android.content.extra.SHOW_ADVANCED"
            intent.putExtra(SHOW_ADVANCED, true)
        } catch (e: Throwable) {
            Log.e(TAG, "SET EXTRA_SHOW_ADVANCED", e)
        }

        try {
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            selectFileLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.file_select_error, Toast.LENGTH_LONG).show()
            Log.e(TAG, "START SELECT_FILE_ACTIVE FAIL", e)
            val settings = getSharedPreferences(SettingsFragment.PREFS_NAME, MODE_PRIVATE)
            val editor = settings.edit()
            editor.putBoolean(SettingsFragment.IS_NET, true)
            editor.apply()
            startActivity<SettingsActivity>()
        }
    }

    private fun startVPN() {
        waitingForVPNStart = false
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            requestVpnPermissionLauncher.launch(vpnIntent)
        }
        else {
            waitingForVPNStart = true
            startService(Intent(this, VhostsService2::class.java))
            setButton(false)
        }
    }

    private fun checkHostUri(): Int {
        val settings = settings()
        if (settings.getBoolean(SettingsFragment.IS_NET, false)) {
            try {
                openFileInput(SettingsFragment.NET_HOST_FILE).close()
                return 2
            } catch (e: Exception) {
                Log.e(TAG, "NET HOSTS FILE NOT FOUND", e)
                return -2
            }
        } else {
            try {
                contentResolver.openInputStream(
                    Uri.parse(
                        settings.getString(
                            SettingsFragment.HOSTS_URI,
                            null
                        )
                    )
                )!!
                    .close()
                return 1
            } catch (e: Exception) {
                Log.e(TAG, "HOSTS FILE NOT FOUND", e)
                return -1
            }
        }
    }

    private fun setUriByPREFS(intent: Intent?) {
        val settings = PreferenceManager.getDefaultSharedPreferences(this)
        val editor = settings.edit()
        val uri = intent!!.data
        try {
            contentResolver.takePersistableUriPermission(
                uri!!, Intent.FLAG_GRANT_READ_URI_PERMISSION
                        or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            editor.putString(SettingsFragment.HOSTS_URI, uri.toString())
            editor.apply()
            when (checkHostUri()) {
                1 -> {
                    setButton(true)
                    setButton(false)
                }

                -1 -> {
                    Toast.makeText(this, R.string.permission_error, Toast.LENGTH_LONG).show()
                }

                2 -> {}
                -2 -> {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "permission error", e)
        }
    }

    private fun shutdownVPN() {
        sendLocalBroadcast { Intent(BROADCAST_CLOSE_SERVICE) }
        setButton(true)
    }

    override fun onResume() {
        super.onResume()
        setButton(!waitingForVPNStart && !isVhostsServiceRunning)
    }

    private fun setButton(enable: Boolean) {
        val vpnButton = viewBinding.buttonStartVpn
        val selectHosts = viewBinding.buttonSelectHosts
        if (enable) {
            vpnButton.isChecked = false
            selectHosts.alpha = 1.0f
            selectHosts.isEnabled = true
        } else {
            vpnButton.isChecked = true
            selectHosts.alpha = .5f
            selectHosts.isEnabled = false
        }
    }

    private fun showDialog() {
        AlertDialog.Builder(this)
            .setCancelable(false)
            .setTitle(R.string.dialog_title)
            .setMessage(R.string.dialog_message)
            .setPositiveButton(R.string.dialog_confirm) { _, _ -> selectFile() }
            .setNegativeButton(R.string.dialog_cancel) { _, _ -> setButton(true) }
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.activity_vhost, menu)
        for (i in 0 until menu.size()) {
            menu.getItem(i).title = menu.getItem(i).title?.withSpans(
                ForegroundColorSpan(getColor(R.color.primary_text))
            )
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_settings -> {
                startActivity<SettingsActivity>()
                true
            }
            else -> {
                super.onOptionsItemSelected(item)
            }
        }
    }
}