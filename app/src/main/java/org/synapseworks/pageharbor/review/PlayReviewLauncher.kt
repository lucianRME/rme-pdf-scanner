package org.synapseworks.pageharbor.review

import android.app.Activity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.google.android.play.core.review.ReviewManagerFactory

fun interface InAppReviewLauncher {
    fun launch(activity: Activity, isStillEligible: () -> Boolean)
}

class GooglePlayReviewLauncher : InAppReviewLauncher {
    override fun launch(activity: Activity, isStillEligible: () -> Boolean) {
        if (!reviewLaunchIsSafe(activity.isResumedAndUsable(), isStillEligible)) return
        val manager = ReviewManagerFactory.create(activity)
        manager.requestReviewFlow().addOnCompleteListener { request ->
            runCatching {
                if (
                    !request.isSuccessful ||
                    !reviewLaunchIsSafe(activity.isResumedAndUsable(), isStillEligible)
                ) {
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

internal fun reviewLaunchIsSafe(
    isActivityResumedAndUsable: Boolean,
    isStillEligible: () -> Boolean,
): Boolean = isActivityResumedAndUsable && runCatching(isStillEligible).getOrDefault(false)
