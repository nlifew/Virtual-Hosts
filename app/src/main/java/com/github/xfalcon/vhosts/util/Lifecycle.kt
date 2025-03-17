package com.github.xfalcon.vhosts.util

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner

inline fun Lifecycle.observe(
    crossinline onCreate: () -> Unit = {},
    crossinline onStart: () -> Unit = {},
    crossinline onResume: () -> Unit = {},
    crossinline onPause: () -> Unit = {},
    crossinline onStop: () -> Unit = {},
    crossinline onDestroy: () -> Unit = {}
) {
    addObserver(object: DefaultLifecycleObserver {
        override fun onCreate(owner: LifecycleOwner) {
            onCreate.invoke()
        }

        override fun onStart(owner: LifecycleOwner) {
            onStart.invoke()
        }

        override fun onResume(owner: LifecycleOwner) {
            onResume.invoke()
        }

        override fun onPause(owner: LifecycleOwner) {
            onPause.invoke()
        }

        override fun onStop(owner: LifecycleOwner) {
            onStop.invoke()
        }

        override fun onDestroy(owner: LifecycleOwner) {
            onDestroy.invoke()
        }
    })
}