package com.github.xfalcon.vhosts.util

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.widget.Toast
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

inline fun Context.sendLocalBroadcast(block: () -> Intent) {
    LocalBroadcastManager.getInstance(applicationContext)
        .sendBroadcast(block.invoke())
}

inline fun Context.registerLocalBroadcast(
    action: String,
    crossinline block: (Intent) -> Unit
): BroadcastReceiver {
    val receiver = broadcastReceiver(block)
    LocalBroadcastManager.getInstance(this)
        .registerReceiver(receiver, IntentFilter(action))
    return receiver
}

inline fun Context.registerLocalBroadcast(
    lifecycleOwner: LifecycleOwner,
    action: String,
    crossinline onReceive: (Intent) -> Unit
) {
    val receiver = broadcastReceiver(onReceive)
    val manager = LocalBroadcastManager.getInstance(this)
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

fun Context.removeLocalBroadcast(receiver: BroadcastReceiver) {
    LocalBroadcastManager.getInstance(this)
        .unregisterReceiver(receiver)
}

inline fun <reified T: Activity> Context.startActivity(vararg args: Pair<String, Any>) {
    val intent = Intent(this, T::class.java)

    if (args.isNotEmpty()) {
        intent.putExtras(bundleOf(*args))
    }
    if (this !is Activity) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    startActivity(intent)
}


inline fun <reified T> Context.toast(len: Int = Toast.LENGTH_LONG, block: () -> T) {
    val text = when (val result = block()) {
        is Int -> getString(result)
        else -> result.toString()
    }
    Toast.makeText(this, text, len).show()
}
