package com.ezfiles.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ezfiles.app.FilyBench

/** Debug builds only; started by scripts/phone-bench.sh. */
class FilyBenchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val spec = intent.getStringExtra("spec") ?: return
        FilyBench.start((context.applicationContext as EzFilesApplication).dependencies, spec)
    }
}
