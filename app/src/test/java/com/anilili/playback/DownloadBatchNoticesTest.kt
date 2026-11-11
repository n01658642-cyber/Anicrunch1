package com.anilili.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadBatchNoticesTest {

    private fun done(label: String) = DownloadBatchNotice(label, failed = false)

    @Test
    fun `a batch collects into one list, newest first`() {
        val notices = DownloadBatchNotices()
        notices.record("a", done("Ep 1"), queueIdle = false)
        notices.record("b", done("Ep 2"), queueIdle = false)
        val batch = notices.record("c", done("Ep 3"), queueIdle = true)
        assertEquals(listOf("Ep 3", "Ep 2", "Ep 1"), batch.map { it.label })
    }

    @Test
    fun `a repeated terminal callback does not list the episode twice`() {
        // Media3 repeats onDownloadChanged for a download already completed.
        val notices = DownloadBatchNotices()
        notices.record("a", done("Ep 1"), queueIdle = false)
        notices.record("b", done("Ep 2"), queueIdle = false)
        val batch = notices.record("a", done("Ep 1"), queueIdle = false)
        assertEquals(listOf("Ep 1", "Ep 2"), batch.map { it.label })
    }

    @Test
    fun `the first episode after the queue went idle starts a new batch`() {
        val notices = DownloadBatchNotices()
        notices.record("a", done("Ep 1"), queueIdle = false)
        notices.record("b", done("Ep 2"), queueIdle = true)
        val batch = notices.record("c", done("Ep 3"), queueIdle = true)
        assertEquals(listOf("Ep 3"), batch.map { it.label })
    }

    @Test
    fun `a failure keeps its place in the batch`() {
        val notices = DownloadBatchNotices()
        notices.record("a", done("Ep 1"), queueIdle = false)
        val batch = notices.record("b", DownloadBatchNotice("Ep 2", failed = true), queueIdle = true)
        assertEquals(listOf(true, false), batch.map { it.failed })
    }
}
