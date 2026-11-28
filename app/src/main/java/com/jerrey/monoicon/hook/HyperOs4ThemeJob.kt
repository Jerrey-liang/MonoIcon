package com.jerrey.monoicon.hook

import android.app.job.JobParameters
import android.app.job.JobService
import android.app.job.JobWorkItem
import com.jerrey.monoicon.logging.logw
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class HyperOs4ThemeJob : JobService() {
    private var running: AtomicBoolean? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val active = AtomicBoolean(true)
        running = active
        executor.execute {
            try {
                while (active.get()) {
                    val batch = mutableListOf<JobWorkItem>()
                    while (active.get() && batch.size < 128) batch.add(params.dequeueWork() ?: break)
                    if (batch.isEmpty() || !active.get()) break
                    val done = HyperOs4ThemeSync.run(applicationContext, batch.mapNotNull { it.intent }, active::get)
                    if (!active.get()) break
                    if (!done) HyperOs4ThemeSync.markForReconcile(applicationContext)
                    if (!done && batch.any { it.deliveryCount < 3 }) {
                        jobFinished(params, true)
                        break
                    }
                    batch.forEach { params.completeWork(it) }
                }
            } catch (e: Exception) {
                logw("MonoIcon.Theme", "Job delivery interrupted: ${e.message}")
                if (active.get()) jobFinished(params, true)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running?.set(false)
        return true
    }

    companion object {
        private val executor = Executors.newSingleThreadExecutor { Thread(it, "MonoIcon-ThemeArchive") }
    }
}
