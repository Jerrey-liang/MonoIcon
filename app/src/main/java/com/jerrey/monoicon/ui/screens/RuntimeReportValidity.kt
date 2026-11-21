package com.jerrey.monoicon.ui.screens

/** A current native report must answer this request and belong to the same process lifetime. */
internal fun currentNativeReport(
    pid: Int,
    startTicks: Long,
    reportBoot: String,
    currentBoot: String,
    reportNonce: String,
    requestedNonce: String,
    processes: Map<Int, Long>,
): Boolean = pid > 0 && startTicks > 0 && reportBoot.isNotBlank() && reportBoot == currentBoot &&
    requestedNonce.isNotBlank() && reportNonce == requestedNonce && processes[pid] == startTicks
