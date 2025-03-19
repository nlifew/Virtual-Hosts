package com.github.xfalcon.vhosts.util

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager


fun Context.settings(): SharedPreferences =
    PreferenceManager.getDefaultSharedPreferences(this)


