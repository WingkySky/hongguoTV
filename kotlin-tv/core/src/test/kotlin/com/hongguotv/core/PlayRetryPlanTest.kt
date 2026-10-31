// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayRetryPlanTest {
    @Test
    fun `retries rotate hosts and force fresh connections after the first attempt`() {
        assertEquals(4, PlayRetryPlan.maxAttempts)
        assertEquals(
            listOf("b", "hl", "lf", "b"),
            (0 until PlayRetryPlan.maxAttempts).map { PlayRetryPlan.tag(PlayRetryPlan.host(it)) },
        )
        assertFalse(PlayRetryPlan.freshConnection(0))
        assertTrue(PlayRetryPlan.freshConnection(1))
        assertTrue(PlayRetryPlan.freshConnection(3))
    }

    @Test
    fun `backoff stays within about seven seconds including four attempts`() {
        assertEquals(listOf(700L, 1500L, 3000L), PlayRetryPlan.delaysMillis)
        assertTrue(PlayRetryPlan.delaysMillis.sum() < 7000L)
    }

    @Test
    fun `empty responses and connection failures are temporary but malformed replies are not`() {
        assertTrue(PlayRetryPlan.temporary(EmptyPlaybackResponseException()))
        assertTrue(PlayRetryPlan.temporary(java.net.SocketTimeoutException()))
        assertTrue(
            PlayRetryPlan.temporary(ContentHttpException(503)),
        )
        assertFalse(PlayRetryPlan.temporary(ContentHttpException(404)))
        assertFalse(PlayRetryPlan.temporary(java.io.EOFException("视频分段提前断开")))
    }

    @Test
    fun `exhausted empty ladder carries counts and host tags without addresses`() {
        val error =
            EmptyPlaybackResponseException("空响应x4 host=b,hl,lf,b")
        assertEquals("空响应x4 host=b,hl,lf,b", error.detail)
        assertFalse(error.detail!!.contains("https://"))
    }
}
