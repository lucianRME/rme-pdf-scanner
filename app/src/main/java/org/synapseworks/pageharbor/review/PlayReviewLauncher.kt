package org.synapseworks.pageharbor.review

import android.app.Activity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.google.android.play.core.review.ReviewManagerFactory

fun interface InAppReviewLauncher {
    fun launch(activity: Activity)
}

class GooglePlayReviewLauncher : InAppReviewLauncher {
    override fun launch(activity: Activity) {
        if (!activity.isResumedAndUsable()) return
        val manager = ReviewManagerFactory.create(activity)
        manager.requestReviewFlow().addOnCompleteListener { request ->
            runCatching {
                if (!request.isSuccessful || !activity.isResumedAndUsable()) {
                    return@runCatching
                }
                manager.launchReviewFlow(activity, request.result)
            }
        }
    }

    private fun Activity.isResumedAndUsable(): Boolean =
        !isFinishing &&
            !isDestroyed &&
            (this as? LifecycleOwner)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true
}
