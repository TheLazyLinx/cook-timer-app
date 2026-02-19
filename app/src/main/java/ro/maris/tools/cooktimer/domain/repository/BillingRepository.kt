package ro.maris.tools.cooktimer.domain.repository

import android.app.Activity
import kotlinx.coroutines.flow.StateFlow
import ro.maris.tools.cooktimer.domain.model.EntitlementState

interface BillingRepository {
    val entitlement: StateFlow<EntitlementState>
    fun refreshEntitlement()
    fun launchPurchaseFlow(activity: Activity)
}
