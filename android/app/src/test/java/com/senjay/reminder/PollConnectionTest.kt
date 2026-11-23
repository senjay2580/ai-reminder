package com.senjay.reminder

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class PollConnectionTest {
    private class Connection : HttpURLConnection(URL("https://cortex.example")) {
        var closed = false
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun disconnect() { closed = true }
    }

    @Test
    fun networkRecoveryWakesThirtySecondBackoffImmediately() = runBlocking {
        val poll = PollConnection()
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { poll.pause(30_000) }
        poll.reconnect()
        assertTrue(withTimeout(1_000) { waiting.await() })
        poll.close()
    }

    @Test
    fun repeatedNetworkEventsAreCoalesced() = runBlocking {
        val poll = PollConnection()
        repeat(5) { poll.reconnect() }
        assertTrue(poll.pause(1_000))
        assertFalse(poll.pause(10))
        poll.close()
    }

    @Test
    fun oldRequestCompletionPreservesNewRequest() {
        val poll = PollConnection()
        val old = Connection()
        val current = Connection()
        poll.opened(old)
        poll.reconnect()
        assertTrue(old.closed)
        poll.opened(current)
        poll.finished(old)
        poll.reconnect()
        assertTrue(current.closed)
        poll.close()
    }

    @Test
    fun stoppingClosesActiveRequestAndRejectsLateRequests() {
        val poll = PollConnection()
        val active = Connection()
        poll.opened(active)
        poll.close()
        assertTrue(active.closed)
        val late = Connection()
        assertThrows(CancellationException::class.java) { poll.opened(late) }
        assertTrue(late.closed)
    }
}
