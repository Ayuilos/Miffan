package me.rerere.workspace

import android.system.Os
import android.system.OsConstants

object AndroidPageSize {
    fun currentBytes(): Long = Os.sysconf(OsConstants._SC_PAGESIZE).also { pageSize ->
        check(pageSize > 0 && pageSize and (pageSize - 1) == 0L) {
            "Unable to determine Android page size: $pageSize"
        }
    }
}
