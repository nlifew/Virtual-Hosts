package com.github.xfalcon.vhosts.util

import android.util.Log
import java.io.IOException

private const val TAG = "IO"

fun AutoCloseable.safeClose() {
    try {
        close()
    } catch (e: IOException) {
        Log.e(TAG, "safeClose: error", e)
    }
}