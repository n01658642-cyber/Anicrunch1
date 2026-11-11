package com.anilili.ui.watch

import com.anilili.data.model.Category
import com.anilili.data.model.EpisodeItem
import com.anilili.data.model.EpisodesResult
import com.anilili.data.model.ProviderData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class WatchInitialCatalogTest {
    private fun catalog(name: String, sub: Int = 25, dub: Int = 0) = EpisodesResult(listOf(
        ProviderData(name, rows(sub), rows(dub)),
    ))
    private fun rows(count: Int) = (1..count).map { EpisodeItem("id-$it", it.toDouble(), null, null, false) }

    @Test fun `ready Anikoto episode starts while Miruro remains pending`() = runBlocking {
        val miruro = CompletableDeferred<Result<EpisodesResult>>()
        val native = CompletableDeferred<EpisodesResult>()
        val selection = async(start = CoroutineStart.UNDISPATCHED) {
            awaitInitialWatchCatalogs(miruro, native, "anikoto", Category.SUB, 25.0, false, 8_000)
        }
        native.complete(catalog("anikoto"))
        val result = withTimeout(1_000) { selection.await() }
        assertTrue(result.nativeStartedFirst)
        assertEquals(listOf("anikoto"), result.native!!.providerNames)
        assertFalse(miruro.isCompleted)
        // The outstanding pipe is still usable for the late merge, not cancelled by the race.
        miruro.complete(Result.success(catalog("bee")))
        assertEquals(listOf("bee"), miruro.await().getOrThrow().providerNames)
    }

    @Test fun `selecting Miruro preserves its priority even with a ready native catalogue`() = runBlocking {
        val miruro = CompletableDeferred<Result<EpisodesResult>>()
        val native = CompletableDeferred(catalog("anikoto"))
        val selection = async(start = CoroutineStart.UNDISPATCHED) {
            awaitInitialWatchCatalogs(miruro, native, "bee", Category.SUB, 25.0, false, 8_000)
        }
        yield()
        assertFalse(selection.isCompleted)
        miruro.complete(Result.success(catalog("bee")))
        assertFalse(selection.await().nativeStartedFirst)
        assertFalse(native.isCancelled)
    }

    @Test fun `Miruro arriving first does not cancel the native picker lookup`() = runBlocking {
        val native = CompletableDeferred<EpisodesResult>()
        val result = awaitInitialWatchCatalogs(
            CompletableDeferred(Result.success(catalog("bee"))), native,
            "bee", Category.SUB, 25.0, false, 8_000,
        )
        assertNotNull(result.miruro)
        assertFalse(native.isCancelled)
        native.complete(catalog("anikoto"))
        assertEquals(listOf("anikoto"), native.await().providerNames)
    }

    @Test fun `native catalogue missing requested episode continues waiting for Miruro`() = runBlocking {
        val miruro = CompletableDeferred<Result<EpisodesResult>>()
        val selection = async(start = CoroutineStart.UNDISPATCHED) {
            awaitInitialWatchCatalogs(miruro, CompletableDeferred(catalog("anikoto", sub = 24)),
                "anikoto", Category.SUB, 25.0, false, 8_000)
        }
        yield()
        assertFalse(selection.isCompleted)
        miruro.complete(Result.success(catalog("bee")))
        assertFalse(selection.await().nativeStartedFirst)
    }

    @Test fun `another native provider cannot override the selected provider`() {
        assertFalse(nativeCatalogCanStartWatch(catalog("anibd"), "anikoto", Category.SUB, 25.0, false))
    }

    @Test fun `a lagging dub does not cause premature sub fallback`() {
        val native = catalog("anikoto", dub = 24)
        assertFalse(nativeCatalogCanStartWatch(native, "anikoto", Category.DUB, 25.0, false))
        assertFalse(nativeCatalogCanStartWatch(native, "anikoto", Category.SUB, 25.0, true))
        assertTrue(nativeCatalogCanStartWatch(native, "anikoto", Category.SUB, 24.0, true))
    }

    @Test fun `Miruro timeout leaves both deferreds available to their consumers`() = runBlocking {
        val miruro = CompletableDeferred<Result<EpisodesResult>>()
        val native = CompletableDeferred<EpisodesResult>()
        val result = awaitInitialWatchCatalogs(miruro, native, "bee", Category.SUB, 25.0, false, 1)
        assertNull(result.miruro)
        assertFalse(result.nativeStartedFirst)
        assertFalse(miruro.isCancelled)
        assertFalse(native.isCancelled)
        miruro.cancel()
        native.cancel()
    }

    @Test fun `navigation cancellation prevents a selected catalogue from being returned`() = runBlocking {
        val miruro = CompletableDeferred<Result<EpisodesResult>>()
        val native = CompletableDeferred<EpisodesResult>()
        val selection = async(start = CoroutineStart.UNDISPATCHED) {
            awaitInitialWatchCatalogs(miruro, native, "anikoto", Category.SUB, 25.0, false, 8_000)
        }
        selection.cancel()
        native.complete(catalog("anikoto"))
        selection.join()
        assertTrue(selection.isCancelled)
        miruro.cancel()
    }
}
