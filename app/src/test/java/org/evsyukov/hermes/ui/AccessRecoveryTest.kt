package org.evsyukov.hermes.ui

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccessRecoveryTest {
    @Test
    fun `a server found offline is checked again when the network comes back`() = runTest {
        val online = MutableSharedFlow<Unit>()
        val access = MutableStateFlow(Access.OFFLINE)
        var checks = 0
        val recovery = OfflineRecovery(access) { checks++; access.value = Access.UNKNOWN }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { recovery.follow(online) }

        online.emit(Unit)
        assertThat(checks).isEqualTo(1)

        // The check is under way, then it found the server: more networks bring no more checks.
        online.emit(Unit)
        access.value = Access.OK
        online.emit(Unit)
        assertThat(checks).isEqualTo(1)
    }

    @Test
    fun `a server that needs a sign-in is not checked on every network`() = runTest {
        val online = MutableSharedFlow<Unit>()
        val access = MutableStateFlow(Access.SIGN_IN)
        var checks = 0
        val recovery = OfflineRecovery(access) { checks++ }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { recovery.follow(online) }

        online.emit(Unit)

        assertThat(checks).isEqualTo(0)
    }

    @Test
    fun `a network that comes during a check that then fails brings one more check, and no more`() = runTest {
        val online = MutableSharedFlow<Unit>()
        val access = MutableStateFlow(Access.UNKNOWN)
        var checks = 0
        val recovery = OfflineRecovery(access) { checks++; access.value = Access.UNKNOWN }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { recovery.follow(online) }

        // A check started while the phone was offline; the network comes back while it runs.
        val first = recovery.checkStarting()
        online.emit(Unit)
        assertThat(checks).isEqualTo(0)

        // The check fails on the old network: the one that came meanwhile is worth another.
        access.value = Access.OFFLINE
        recovery.checkFailed(first)
        assertThat(checks).isEqualTo(1)

        // That one fails too, with no network since: the server is down, nothing polls it.
        val second = recovery.checkStarting()
        access.value = Access.OFFLINE
        recovery.checkFailed(second)
        assertThat(checks).isEqualTo(1)
    }
}
