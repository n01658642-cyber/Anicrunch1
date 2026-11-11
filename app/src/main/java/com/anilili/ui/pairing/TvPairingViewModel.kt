package com.anilili.ui.pairing

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anilili.data.auth.AccountAdoption
import com.anilili.data.auth.AccountService
import com.anilili.data.auth.MalAuthManager
import com.anilili.data.pairing.AniListTokenCredential
import com.anilili.data.pairing.MalCodeCredential
import com.anilili.data.pairing.PAIRING_SERVICE_ANILIST
import com.anilili.data.pairing.PAIRING_SERVICE_MAL
import com.anilili.data.pairing.PairingAdvertiser
import com.anilili.data.pairing.PairingResult
import com.anilili.data.pairing.PairingServer
import com.anilili.data.pairing.localPairingAddresses
import com.anilili.diagnostics.DiagnosticsLog
import com.anilili.errors.AnililiError
import com.anilili.errors.AnililiFailure
import com.anilili.errors.report
import com.anilili.errors.toFailure
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** What the television is showing while it waits for a phone to sign it in. */
sealed interface TvPairingState {
    data object Starting : TvPairingState

    /**
     * Listening. Every reachable address is printed for the manual fallback when mDNS is blocked —
     * all of them, because a television with Ethernet and Wi-Fi both up has two and only one of
     * them may be on the phone's subnet.
     */
    data class Waiting(val addresses: List<String>, val port: Int) : TvPairingState

    data class Confirm(val code: String, val serviceLabel: String) : TvPairingState

    /** Both viewers accepted; the phone is signing in. */
    data object Linking : TvPairingState

    data class Linked(val serviceLabel: String) : TvPairingState

    /** The catalogued failure: `PAIR-08` for a window nobody used, `PAIR-09` for anything else. */
    data class Failed(val failure: AnililiFailure) : TvPairingState
}

/**
 * The television half of pairing: advertise, listen, show a code, take the credential.
 *
 * The window is deliberately short and the socket is torn down by every exit path — a television
 * left listening is a television anyone on the network can talk to.
 */
class TvPairingViewModel : ViewModel() {
    private val _state = MutableStateFlow<TvPairingState>(TvPairingState.Starting)
    val state = _state.asStateFlow()

    private var job: Job? = null
    private var server: PairingServer? = null
    private var advertiser: PairingAdvertiser? = null
    private var confirmGate: CompletableDeferred<Boolean>? = null

    fun start(context: Context) {
        if (job?.isActive == true) return
        val appContext = context.applicationContext
        _state.value = TvPairingState.Starting
        job = viewModelScope.launch {
            val pairingServer = PairingServer(
                deviceLabel = deviceLabel(),
                newVerifier = MalAuthManager::newPkceVerifier,
            )
            server = pairingServer
            try {
                val port = withContext(Dispatchers.IO) { pairingServer.bind() }
                advertiser = PairingAdvertiser(appContext).also {
                    it.register(port, newSessionId(), setOf(PAIRING_SERVICE_ANILIST, PAIRING_SERVICE_MAL))
                }
                _state.value = TvPairingState.Waiting(
                    addresses = localPairingAddresses().map { (iface, address) -> "$address:$port  ($iface)" },
                    port = port,
                )

                var linkedService = ""
                withTimeout(PAIRING_WINDOW_MS) {
                    pairingServer.serve(
                        onCodeShown = { code, service ->
                            _state.value = TvPairingState.Confirm(code, serviceLabel(service))
                        },
                        awaitLocalConfirm = ::awaitLocalConfirm,
                        onCredential = { result ->
                            linkedService = serviceLabel(result.service)
                            adopt(result)
                        },
                    )
                }
                _state.value = TvPairingState.Linked(linkedService)
                DiagnosticsLog.event(
                    category = "pairing",
                    name = "pairing.linked",
                    attributes = mapOf("service" to linkedService),
                )
            } catch (cancelled: CancellationException) {
                if (cancelled is TimeoutCancellationException) {
                    _state.value = TvPairingState.Failed(
                        AnililiError.PAIRING_TIMED_OUT.report(
                            detail = "No phone connected before the pairing window closed. " +
                                "Start again when you're ready.",
                        ),
                    )
                } else {
                    throw cancelled
                }
            } catch (failure: Exception) {
                _state.value = TvPairingState.Failed(
                    failure.toFailure(AnililiError.PAIRING_FAILED),
                )
            } finally {
                // NonCancellable so a cancelled window still closes the socket and stops the
                // advertisement; a television must never be left listening.
                withContext(NonCancellable) { teardown() }
            }
        }
    }

    fun confirm() {
        confirmGate?.complete(true)
    }

    fun decline() {
        confirmGate?.complete(false)
    }

    fun cancel() {
        job?.cancel()
        job = null
        viewModelScope.launch { withContext(NonCancellable) { teardown() } }
    }

    override fun onCleared() {
        cancel()
    }

    private suspend fun awaitLocalConfirm(): Boolean {
        val gate = CompletableDeferred<Boolean>()
        confirmGate = gate
        val accepted = withTimeoutOrNull(CONFIRM_TIMEOUT_MS) { gate.await() } ?: false
        confirmGate = null
        if (accepted) _state.value = TvPairingState.Linking
        return accepted
    }

    private suspend fun adopt(result: PairingResult) {
        when (val credential = result.credential) {
            is AniListTokenCredential -> AccountAdoption.adoptAniList(credential.token)
            is MalCodeCredential -> AccountAdoption.adoptMalCode(credential.code, result.malVerifier)
        }
    }

    private fun teardown() {
        advertiser?.unregister()
        advertiser = null
        server?.close()
        server = null
        confirmGate?.complete(false)
        confirmGate = null
    }

    private fun deviceLabel(): String =
        android.os.Build.MODEL.orEmpty().ifBlank { "this television" }

    private fun newSessionId(): String = ByteArray(4)
        .also(SecureRandom()::nextBytes)
        .joinToString("") { "%02x".format(it) }

    private fun serviceLabel(service: String): String = when (service) {
        PAIRING_SERVICE_ANILIST -> AccountService.ANILIST.label
        PAIRING_SERVICE_MAL -> AccountService.MAL.label
        else -> service
    }

    private companion object {
        /**
         * Three minutes was not enough. Measured end to end on a real phone the whole flow —
         * discover, compare the code, confirm twice, then actually sign in to AniList including a
         * Cloudflare check — took about 2m50s with the login page already warm and a viewer who
         * knew their password. Anyone hunting for it would have been timed out mid-typing.
         *
         * Ten minutes is still bounded, and the socket is not idle-exposed for it: the window only
         * covers a confirm-gated conversation that is limited to three attempts.
         */
        const val PAIRING_WINDOW_MS = 10 * 60 * 1000L
        const val CONFIRM_TIMEOUT_MS = 2 * 60 * 1000L
    }
}
