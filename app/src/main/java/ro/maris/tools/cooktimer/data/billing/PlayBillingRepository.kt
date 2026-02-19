package ro.maris.tools.cooktimer.data.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import ro.maris.tools.cooktimer.domain.model.EntitlementSource
import ro.maris.tools.cooktimer.domain.model.EntitlementState
import ro.maris.tools.cooktimer.domain.repository.BillingRepository
import ro.maris.tools.cooktimer.domain.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class PlayBillingRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository
) : BillingRepository, PurchasesUpdatedListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _entitlement = MutableStateFlow(EntitlementState(isPremium = false, source = EntitlementSource.NONE))
    override val entitlement: StateFlow<EntitlementState> = _entitlement.asStateFlow()

    private val billingClient = BillingClient.newBuilder(context)
        .enablePendingPurchases()
        .setListener(this)
        .build()

    private var connectionDeferred: CompletableDeferred<Boolean>? = null
    private var cachedProductDetails: ProductDetails? = null

    init {
        scope.launch {
            val cachedPremium = settingsRepository.getSettings().isPremium
            val source = if (cachedPremium) EntitlementSource.CACHE else EntitlementSource.NONE
            _entitlement.value = EntitlementState(isPremium = cachedPremium, source = source)
            refreshEntitlement()
        }
    }

    override fun refreshEntitlement() {
        scope.launch {
            if (!ensureConnected()) return@launch
            val purchases = queryPurchases()
            handlePurchases(purchases, EntitlementSource.PURCHASES)
        }
    }

    override fun launchPurchaseFlow(activity: Activity) {
        scope.launch {
            if (!ensureConnected()) return@launch
            val productDetails = getProductDetails() ?: return@launch
            val params = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(
                    listOf(
                        BillingFlowParams.ProductDetailsParams.newBuilder()
                            .setProductDetails(productDetails)
                            .build()
                    )
                )
                .build()
            withContext(Dispatchers.Main) {
                billingClient.launchBillingFlow(activity, params)
            }
        }
    }

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: MutableList<Purchase>?) {
        if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) return
        if (purchases == null) return
        handlePurchases(purchases, EntitlementSource.PURCHASES)
    }

    private suspend fun ensureConnected(): Boolean {
        if (billingClient.isReady) return true
        val existing = connectionDeferred
        if (existing != null) return existing.await()

        val deferred = CompletableDeferred<Boolean>()
        connectionDeferred = deferred
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                val connected = billingResult.responseCode == BillingClient.BillingResponseCode.OK
                if (!deferred.isCompleted) {
                    deferred.complete(connected)
                }
            }

            override fun onBillingServiceDisconnected() {
                connectionDeferred?.let { pending ->
                    if (!pending.isCompleted) {
                        pending.complete(false)
                    }
                }
                connectionDeferred = null
            }
        })
        val connected = deferred.await()
        if (connected) {
            connectionDeferred = null
        }
        return connected
    }

    private suspend fun queryPurchases(): List<Purchase> {
        if (!billingClient.isReady) return emptyList()
        return suspendCancellableCoroutine { continuation ->
            billingClient.queryPurchasesAsync(BillingClient.ProductType.INAPP) { result, purchases ->
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    continuation.resume(purchases)
                } else {
                    continuation.resume(emptyList())
                }
            }
        }
    }

    private suspend fun getProductDetails(): ProductDetails? {
        cachedProductDetails?.let { return it }
        if (!billingClient.isReady) return null
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(BillingConfig.premiumProductId)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                )
            )
            .build()

        return suspendCancellableCoroutine { continuation ->
            billingClient.queryProductDetailsAsync(params) { result, productDetailsList ->
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    val details = productDetailsList.firstOrNull()
                    cachedProductDetails = details
                    continuation.resume(details)
                } else {
                    continuation.resume(null)
                }
            }
        }
    }

    private fun handlePurchases(purchases: List<Purchase>, source: EntitlementSource) {
        val matching = purchases.filter { purchase ->
            purchase.products.contains(BillingConfig.premiumProductId)
        }
        val purchased = matching.firstOrNull { it.purchaseState == Purchase.PurchaseState.PURCHASED }
        val pending = matching.any { it.purchaseState == Purchase.PurchaseState.PENDING }

        if (purchased != null) {
            if (!purchased.isAcknowledged) {
                acknowledgePurchase(purchased)
            }
            updateEntitlement(isPremium = true, source = source)
            return
        }

        if (!pending) {
            updateEntitlement(isPremium = false, source = source)
        }
    }

    private fun acknowledgePurchase(purchase: Purchase) {
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        billingClient.acknowledgePurchase(params) { /* No-op */ }
    }

    private fun updateEntitlement(isPremium: Boolean, source: EntitlementSource) {
        if (_entitlement.value.isPremium == isPremium && _entitlement.value.source == source) return
        _entitlement.value = EntitlementState(isPremium = isPremium, source = source)
        scope.launch {
            settingsRepository.setIsPremium(isPremium)
        }
    }
}
