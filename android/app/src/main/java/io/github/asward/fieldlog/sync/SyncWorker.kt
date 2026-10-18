package io.github.asward.fieldlog.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.asward.fieldlog.userMessage
import io.github.asward.fieldlog.vaults
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.fieldlog_core.CoreException
import uniffi.fieldlog_core.SyncReport

/** Pull, merge and push vaults that have a remote. Upload pending images first. */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val vaults = applicationContext.vaults
        val only = inputData.getString(KEY_VAULT)
        var retry = false
        for (rec in vaults.list()) {
            if (only != null && rec.id != only) continue
            if (rec.remoteUrl == null) continue
            val now = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
            try {
                syncNow(applicationContext, rec.id)
            } catch (e: CoreException.LogOpen) {
                // Background sync waits for the open log; `end log` syncs it.
                vaults.setLastSync(rec.id, "Sync paused while a log is open")
            } catch (e: CoreException.Conflict) {
                vaults.setLastSync(rec.id, "Sync failed $now: ${e.userMessage()}")
            } catch (e: CoreException.HostKeyMismatch) {
                vaults.setLastSync(rec.id, "Sync blocked $now: ${e.userMessage()}")
            } catch (e: Exception) {
                vaults.setLastSync(rec.id, "Sync failed $now: ${e.userMessage()}")
                retry = true
            }
        }
        if (retry && runAttemptCount < 5) Result.retry() else Result.success()
    }

    companion object {
        private const val KEY_VAULT = "vault"

        /**
         * Pull, merge and push one vault now (blocking). Pins the SSH host key on
         * first use and records the status line. Throws on failure.
         */
        fun syncNow(context: Context, vaultId: String): SyncReport {
            val vaults = context.vaults
            val rec = vaults.get(vaultId) ?: error("Vault not found")
            val report = vaults.open(vaultId).sync(vaults.gitCredentials(vaultId), vaults.s3Credentials(vaultId))
            val seen = report.hostKeySha256
            if (rec.hostKey == null && seen != null) {
                // Trust on first use: pin the key we just saw.
                vaults.update(rec.copy(hostKey = seen))
            }
            val now = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
            val conflicts = report.conflictCopies.size
            vaults.setLastSync(
                vaultId,
                if (conflicts > 0) "Synced $now · $conflicts log${if (conflicts == 1) "" else "s"} edited on two devices; both kept"
                else "Synced $now",
            )
            return report
        }
        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Sync soon. Repeated calls within the delay collapse into one run. */
        fun enqueue(context: Context, vaultId: String?, delaySeconds: Long = 20) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(network)
                .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setInputData(workDataOf(KEY_VAULT to vaultId))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("sync-${vaultId ?: "all"}", ExistingWorkPolicy.REPLACE, req)
        }

        fun schedulePeriodic(context: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
                .setConstraints(network)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork("sync-periodic", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
