package com.anilili.data.auth

import com.anilili.data.library.LibraryStore

/**
 * Adopting a freshly obtained credential as this device's one active account.
 *
 * Shared, because a television can now receive its credential from a paired phone instead of from
 * its own WebView. The account-switch rules — which service wins, when the outgoing one is
 * dropped, and what has to happen to the local library in between — must not drift between the
 * two paths.
 */
internal object AccountAdoption {
    fun adoptAniList(token: String) {
        AuthManager.setToken(token)
        // AccountService promises one active service. Only clear the previous service after the
        // replacement login has succeeded, so a cancelled login never disconnects the user.
        MalAuthManager.logout()
        resetLocalLibraryForNewAccount()
    }

    /** The local login's own code, exchanged with the verifier it is already holding. */
    suspend fun adoptMalCode(code: String) {
        MalAuthManager.exchangeCode(code)
        AuthManager.logout()
        resetLocalLibraryForNewAccount()
    }

    /** A code a paired phone obtained on this device's behalf, with this device's verifier. */
    suspend fun adoptMalCode(code: String, verifier: String) {
        MalAuthManager.exchangeCodeWithVerifier(code, verifier)
        AuthManager.logout()
        resetLocalLibraryForNewAccount()
    }

    /**
     * The snapshot on disk still describes the account being left, and nothing in it says so. A
     * profile fetch does not hold LibraryStore's sync mutex, so it can hydrate before the push
     * below has landed — and would then read every title the old account had and the new one does
     * not as a save deleted elsewhere.
     */
    private fun resetLocalLibraryForNewAccount() {
        LibraryStore.clearRemoteLibrary()
        LibraryStore.syncSavedToRemote()
    }
}
