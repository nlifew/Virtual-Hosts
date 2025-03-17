package com.github.xfalcon.vhosts.util

import android.text.SpannableStringBuilder
import androidx.core.text.buildSpannedString

fun SpannableStringBuilder.appendSpans(
    text: CharSequence?,
    vararg args: Any
) {
    text?.takeIf { it.isNotEmpty() } ?: return

    val begin = length
    append(text)
    val end = length
    args.forEach { setSpan(it, begin, end, SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE) }
}

fun CharSequence.withSpans(vararg args: Any) = buildSpannedString {
    appendSpans(this@withSpans, *args)
}
