package com.anilili.ui.pairing

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anilili.data.auth.AccountService
import com.anilili.data.auth.AuthManager
import com.anilili.data.auth.MalAuthManager
import com.anilili.data.pairing.AniListTokenCredential
import com.anilili.data.pairing.DiscoveredTv
import com.anilili.data.pairing.MalCodeCredential
import com.anilili.data.pairing.PAIRING_SERVICE_ANILIST
import com.anilili.data.pairing.PAIRING_SERVICE_MAL
import com.anilili.data.pairing.PairingBrowser
import com.anilili.data.pairing.PairingClient
import com.anilili.data.pairing.PairingCredential
import com.anilili.data.pairing.PairingDiagnostics
import com.anilili.data.pairing.PairingProtocolException
import com.anilili.data.pairing.describePairingFailure
import com.anilili.data.pairing.localPairingAddresses
import com.anilili.diagnostics.DiagnosticsLog
import com.anilili.errors.AnililiError
import com.anilili.errors.AnililiFailure
import com.anilili.errors.report
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** What the phone is showing while it signs a television in. */
sealed interface PhonePairingState {
    data object Browsing : PhonePairingState

    data class Connecting(val tvName: String) : PhonePairingState

    data class Confirm(val code: String, val tvName: String) : PhonePairingState

    /** The sign-in itself, driven by the shared login WebView. */
    data class Authorizing(val authorizeUrl: String, val service: String) : PhonePairingState

    data object Sending : PhonePairingState

    data class Linked(val tvName: String, val serviceLabel: String) : PhonePairingState

    /**
     * Carries the catalogued failure: the line, the long hint behind it, and the code.
     *
     * Pairing is the one failure a viewer cannot photograph a player for — it happens on a network
     * nobody else can reproduce, between two devices — so quoting `PAIR-02` is the difference between
     * a report that can be acted on and "it didn't work".
     */
    data class Failed(val failure: AnililiFailure) : PhonePairingState
}

/**
 * The phone half of pairing.
 *
 * Nothing obtained here is stored on the phone: the token or code belongs to the television, and
 * this device's own login is untouched throughout — which is why the OAuth runs against the
 * caller-owned `state` variants rather than the ones backed by the auth singletons.
 */
class PhonePairingViewModel : ViewModel() {
    private val _state = MutableStateFlow<PhonePairingState>(PhonePairingState.Browsing)
    val state = _state.asStateFlow()

    private var browser: PairingBrowser? = null
    private var job: Job? = null
    private var confirmGate: CompletableDeferred<Boolean>? = null
    private var authorizationGate: CompletableDeferred<PairingCredential>? = null
    private var oauthState: String? = null
    private var pendingService: String? = null

    private val _televisions = MutableStateFlow<List<DiscoveredTv>>(emptyList())

    /**
     * Televisions currently advertising themselves.
     *
     * Owned here rather than delegated to the browser's own flow: the browser does not exist at
     * first composition, and a collector that captured a placeholder then would never see the
     * real one appear.
     */
    val televisions: StateFlow<List<DiscoveredTv>> = _televisions.asStateFlow()

    private var browseJob: Job? = null

    fun startBrowsing(context: Context) {
        if (browser != null) return
        val started = PairingBrowser(context, viewModelScope)
        browser = started
        started.start()
        browseJob = viewModelScope.launch { started.found.collect { _televisions.value = it } }
        // Nothing found after a while is the single most reported failure, and an endless spinner
        // says nothing about why. Name the usual causes once, on screen, rather than making people
        // guess or ask.
        viewModelScope.launch {
            delay(NOTHING_FOUND_HINT_MS)
            if (_televisions.value.isEmpty() && browser != null) {
                // Reported as well as shown: "discovery finds nothing" is the most common pairing
                // failure there is, and counting it needs one code rather than a prose search.
                PairingDiagnostics.log(
                    AnililiError.PAIRING_NONE_FOUND.report(
                        attributes = mapOf("afterMs" to NOTHING_FOUND_HINT_MS),
                    ).withCode(),
                )
                PairingDiagnostics.log("- Is the television showing its \"Sign in from your phone\" screen right now?")
                PairingDiagnostics.log("- Are both on the same Wi-Fi? A guest network will not work.")
                PairingDiagnostics.log("- TV on Ethernet and phone on Wi-Fi often blocks discovery.")
                PairingDiagnostics.log("- Many routers drop this traffic (\"AP isolation\"). Use the address box below.")
            }
        }
    }

    fun stopBrowsing() {
        browseJob?.cancel()
        browseJob = null
        browser?.stop()
        browser = null
        _televisions.value = emptyList()
    }

    fun connect(tv: DiscoveredTv, service: String) {
        if (job?.isActive == true) return
        _state.value = PhonePairingState.Connecting(tv.name)
        job = viewModelScope.launch {
            try {
                stopBrowsing()
                PairingClient.pair(
                    host = tv.host,
                    port = tv.port,
                    service = service,
                    onCodeShown = { code, peerLabel ->
                        _state.value = PhonePairingState.Confirm(code, peerLabel.ifBlank { tv.name })
                    },
                    awaitLocalConfirm = ::awaitLocalConfirm,
                    authorize = { challenge -> authorize(service, challenge) },
                )
                _state.value = PhonePairingState.Linked(tv.name, serviceLabel(service))
                DiagnosticsLog.event(
                    category = "pairing",
                    name = "pairing.phone_linked",
                    attributes = mapOf("service" to service),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val advice = describePairingFailure(
                    error = failure,
                    host = tv.host,
                    port = tv.port,
                    phoneAddresses = localPairingAddresses().map { it.second },
                )
                // describePairingFailure is pure; this is where the failure becomes real, so this is
                // where it is written down — under its code, with the address it was reaching for.
                _state.value = PhonePairingState.Failed(
                    advice.failure.report(
                        cause = failure,
                        attributes = mapOf("host" to tv.host, "port" to tv.port, "name" to tv.name),
                    ),
                )
            } finally {
                confirmGate = null
                authorizationGate = null
            }
        }
    }

    fun confirm() {
        confirmGate?.complete(true)
    }

    fun decline() {
        confirmGate?.complete(false)
    }

    /** Pulls the token or code out of a redirect, for the login WebView to hand back. */
    fun extractPairingResult(url: String): String? {
        val state = oauthState ?: return null
        return when (pendingService) {
            PAIRING_SERVICE_ANILIST -> AuthManager.extractPairingToken(url, state)
            PAIRING_SERVICE_MAL -> MalAuthManager.extractPairingCode(url, state)
            else -> null
        }
    }

    fun isPairingRedirect(url: String): Boolean = when (pendingService) {
        PAIRING_SERVICE_ANILIST -> AuthManager.isRedirect(url)
        PAIRING_SERVICE_MAL -> MalAuthManager.isRedirect(url)
        else -> false
    }

    fun onAuthorizationResult(value: String) {
        val gate = authorizationGate ?: return
        val credential = when (pendingService) {
            PAIRING_SERVICE_ANILIST -> AniListTokenCredential(value)
            PAIRING_SERVICE_MAL -> MalCodeCredential(value)
            else -> null
        }
        if (credential == null) {
            gate.completeExceptionally(PairingProtocolException("The sign-in returned nothing usable"))
        } else {
            _state.value = PhonePairingState.Sending
            gate.complete(credential)
        }
    }

    fun cancelAuthorization() {
        authorizationGate?.completeExceptionally(PairingProtocolException("The sign-in was cancelled"))
    }

    fun cancel() {
        job?.cancel()
        job = null
        stopBrowsing()
        _state.value = PhonePairingState.Browsing
    }

    fun restart(context: Context) {
        cancel()
        startBrowsing(context)
    }

    override fun onCleared() {
        job?.cancel()
        stopBrowsing()
    }

    private suspend fun awaitLocalConfirm(): Boolean {
        val gate = CompletableDeferred<Boolean>()
        confirmGate = gate
        val accepted = withTimeoutOrNull(CONFIRM_TIMEOUT_MS) { gate.await() } ?: false
        confirmGate = null
        return accepted
    }

    private suspend fun authorize(service: String, challenge: String?): PairingCredential {
        val state = when (service) {
            PAIRING_SERVICE_ANILIST -> AuthManager.newOAuthState()
            else -> MalAuthManager.newOAuthState()
        }
        val url = when (service) {
            PAIRING_SERVICE_ANILIST -> AuthManager.pairingAuthorizeUrl(state)
            else -> MalAuthManager.pairingAuthorizeUrl(
                state = state,
                challenge = challenge ?: throw PairingProtocolException("The TV supplied no PKCE challenge"),
            )
        }
        oauthState = state
        pendingService = service
        val gate = CompletableDeferred<PairingCredential>()
        authorizationGate = gate
        _state.value = PhonePairingState.Authorizing(url, service)
        return try {
            gate.await()
        } finally {
            authorizationGate = null
            oauthState = null
            pendingService = null
        }
    }

    private fun serviceLabel(service: String): String = when (service) {
        PAIRING_SERVICE_ANILIST -> AccountService.ANILIST.label
        PAIRING_SERVICE_MAL -> AccountService.MAL.label
        else -> service
    }

    private companion object {
        // Matches the television's gate; see TvPairingViewModel for why three minutes was short.
        const val CONFIRM_TIMEOUT_MS = 2 * 60 * 1000L
        const val NOTHING_FOUND_HINT_MS = 12_000L
    }
}
