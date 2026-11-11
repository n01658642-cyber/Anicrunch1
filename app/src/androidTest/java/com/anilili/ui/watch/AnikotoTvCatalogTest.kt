package com.anilili.ui.watch

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anilili.data.AppGraph
import com.anilili.data.settings.SettingsStore
import com.anilili.ui.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real long-series TV catalogue merge, including the Miruro-selected branch. */
@RunWith(AndroidJUnit4::class)
class AnikotoTvCatalogTest {
    @Test
    fun bothRoutesKeepAnikotoSelectableWithoutReplacingTheActiveStream() = runBlocking {
        AppGraph.init(ApplicationProvider.getApplicationContext<Context>())
        assertTrue("Run this integration check on a television", AppGraph.isTv)
        SettingsStore.awaitLoaded()
        val oldPreferred = SettingsStore.preferredProvider.value
        val oldDub = SettingsStore.preferDub.value
        val oldDisabled = SettingsStore.disabledProviders.value
        try {
            withContext(Dispatchers.Main) {
                SettingsStore.setPreferDub(false)
                SettingsStore.setDisabledProviders(oldDisabled - setOf("anikoto", "bee", "anibd"))
            }
            for (provider in listOf("bee", "anikoto")) {
                val store = ViewModelStore()
                val started = SystemClock.elapsedRealtime()
                val viewModel = withContext(Dispatchers.Main) {
                    SettingsStore.setPreferredProvider(provider)
                    WatchViewModel().also {
                        store.put("watch", it)
                        it.start(269, provider, "sub", "298")
                    }
                }
                try {
                    val resolvedState = withTimeout(45_000) {
                        viewModel.state.first { it is UiState.Error ||
                            (it is UiState.Success && it.data.chosenStream != null && !it.data.isResolving) }
                    }
                    check(resolvedState is UiState.Success) { "Watch failed: $resolvedState" }
                    val data = resolvedState.data
                    val resolvedMs = SystemClock.elapsedRealtime() - started
                    assertEquals("Requested route should play", provider, data.provider)
                    assertTrue("Expected native playback", data.chosenStream!!.isHls)
                    val picker = withTimeout(15_000) {
                        viewModel.state.first { it is UiState.Success && it.data.sourceOptions.any { option ->
                            option.provider == "anikoto" && option.hasCurrentEpisode
                        } } as UiState.Success
                    }.data
                    assertEquals(provider, picker.provider)
                    assertEquals(data.chosenStream, picker.chosenStream)
                    Log.i("AnikotoTvCatalog", "provider=$provider episodeResolvedMs=$resolvedMs " +
                        "anikotoListedMs=${SystemClock.elapsedRealtime() - started} episodes=${picker.episodes.size}")
                } finally {
                    withContext(Dispatchers.Main) { store.clear() }
                }
            }
        } finally {
            withContext(Dispatchers.Main) {
                SettingsStore.setPreferredProvider(oldPreferred)
                SettingsStore.setPreferDub(oldDub)
                SettingsStore.setDisabledProviders(oldDisabled)
            }
        }
    }
}
