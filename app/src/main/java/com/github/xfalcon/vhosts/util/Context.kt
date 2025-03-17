package com.github.xfalcon.vhosts.util

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.bundleOf
import androidx.lifecycle.LifecycleOwner
import androidx.localbroadcastmanager.content.LocalBroadcastManager

inline fun AppCompatActivity.doWhenActivityResultOk(
    crossinline block: (Intent?) -> Unit
): ActivityResultLauncher<Intent> {
    return registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) {
            block(it.data)
        }
    }
}

inline fun broadcastReceiver(crossinline block: (Intent) -> Unit) = object: BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent) {
        block(intent)
    }
}

@JvmInline
value class IntentScope(val intent: Intent) {
    infix fun String.to(value: Boolean) {
        intent.putExtra(this, value)
    }
}

inline fun Context.sendLocalBroadcast(block: (IntentScope) -> Unit) {
    LocalBroadcastManager.getInstance(applicationContext)
        .sendBroadcast(intent(block).intent)
}

inline fun Context.registerLocalBroadcast(
    lifecycleOwner: LifecycleOwner,
    action: String,
    crossinline onReceive: (Intent) -> Unit
) {
    val receiver = broadcastReceiver(onReceive)
    val manager = LocalBroadcastManager.getInstance(applicationContext)
    manager.registerReceiver(receiver, IntentFilter(action))
    lifecycleOwner.lifecycle.observe(
        onDestroy = { manager.unregisterReceiver(receiver) }
    )
}

inline fun AppCompatActivity.registerLocalBroadcast(
    action: String,
    crossinline onReceive: (Intent) -> Unit
) {
    registerLocalBroadcast(this, action, onReceive)
}


inline fun <reified T: Activity> Context.startActivity(vararg args: Pair<String, Any>) {
    startActivity(Intent(this, T::class.java).putExtras(bundleOf(*args)))
}

inline fun intent(block: (IntentScope) -> Unit): IntentScope {
    return IntentScope(Intent()).also(block)
}
