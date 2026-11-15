package hu.personal.phoneagent

import android.app.job.JobParameters
import android.app.job.JobService

/** Óránkénti háttérfeladat: letölti a Solaris állapotát, és ha kell, értesít. */
class SolarisJobService : JobService() {
    @Volatile private var worker: Thread? = null

    override fun onStartJob(params: JobParameters): Boolean {
        worker = Thread {
            val result = SolarisFeed.fetch(applicationContext)
            if (result is SolarisFeed.FetchResult.Success && result.isNew) {
                SolarisFeed.maybeNotify(applicationContext, result.snapshot)
            }
            jobFinished(params, result is SolarisFeed.FetchResult.Failure)
        }.also { it.start() }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        worker?.interrupt()
        return true
    }
}
