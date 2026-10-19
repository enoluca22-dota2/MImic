package com.example.mimic.downloader.download

import android.util.Log
import java.io.File

/**
 * Last line of defence against zombie processes. `YoutubeDL.destroyProcessById` kills the python
 * process running yt-dlp, but ffmpeg children it spawned for merging can outlive it. Every job's
 * processes receive the job's private temp dir on their command line, so we find and kill any
 * process of our own UID whose command line mentions that dir.
 */
object ProcessReaper {
    private const val TAG = "ProcessReaper"

    /** True if a NUL-separated /proc cmdline references [marker] (the job's temp dir). */
    fun cmdlineMatches(cmdline: String, marker: String): Boolean =
        marker.isNotEmpty() && cmdline.split('\u0000').any { arg -> arg.contains(marker) }

    /** Kills every process of this app whose command line references [marker]; returns how many. */
    fun killProcessesReferencing(marker: String): Int {
        val self = android.os.Process.myPid()
        var killed = 0
        File("/proc").listFiles()?.forEach { dir ->
            val pid = dir.name.toIntOrNull() ?: return@forEach
            if (pid == self) return@forEach
            // Other apps' /proc entries are unreadable to us (hidepid), so this only ever sees our own.
            val cmdline = runCatching { File(dir, "cmdline").readText() }.getOrNull() ?: return@forEach
            if (cmdlineMatches(cmdline, marker)) {
                runCatching { android.os.Process.killProcess(pid) }
                    .onSuccess { killed++ }
            }
        }
        if (killed > 0) Log.i(TAG, "Killed $killed leftover process(es) for $marker")
        return killed
    }
}
