package org.evsyukov.hermes.ui

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class ReadyFixtureProtocolCompletionTest {
    @Test
    fun `READY replacement setup waits for admitted initial protocol completion`() {
        val fixture = LocalActionLifetimeTest()
        var control: LocalActionLifetimeTest.ReadyProtocolControl? = null
        var setupCompleted = false
        var driverJoined = false
        var driverFinallyObserved = false
        var oracleAttempted = false
        var positiveCompleted = false
        var mainTerminated = false
        var primary: Throwable? = null
        fun record(error: Throwable) {
            val first = primary
            if (first == null) primary = error else if (first !== error) first.addSuppressed(error)
        }
        try {
            fixture.setUp()
            setupCompleted = true
            val actualControl = fixture.fixtureProtocolControl()
            control = actualControl
            runBlocking {
                supervisorScope {
                    val driver = async { fixture.fixtureRunProtocolReady(actualControl) }
                    var cancellationRequested = false
                    try {
                        val (entry, prefix) = withTimeout(10_000) {
                            actualControl.protocolEntered.await() to actualControl.prefix.await()
                        }
                        val sentinel = withTimeout(10_000) { fixture.fixtureProtocolSentinel(actualControl) }
                        actualControl.protocolFailure.get()?.let { throw it }
                        assertThat(actualControl.driverEntered.get()).isNotNull()
                        assertThat(prefix.startupChild).isNotSameInstanceAs(prefix.vmRoot)
                        assertThat(prefix.access.isCompleted).isTrue()
                        assertThat(prefix.access.isCancelled).isFalse()
                        assertThat(prefix.list.isCompleted).isTrue()
                        assertThat(prefix.list.isCancelled).isFalse()
                        assertThat(prefix.writers.all { it.isCompleted }).isTrue()
                        assertThat(entry.requestId).isNotEmpty()
                        assertThat(entry.serverSocket).isNotNull()
                        assertThat(fixture.fixtureProtocolCurrent).isSameInstanceAs(prefix.origin)
                        assertThat(prefix.origin.session.owner()).isEqualTo(prefix.owner)
                        assertThat(prefix.transport).isGreaterThan(0L)
                        assertThat(prefix.origin.socket.transport.value).isEqualTo(prefix.transport)
                        assertThat(prefix.counted).isEqualTo(0)
                        assertThat(prefix.origin.socket.connections.value).isEqualTo(prefix.counted)
                        assertThat(actualControl.protocolRelease.count).isEqualTo(1L)
                        assertThat(actualControl.callbackAfterReply.isCompleted).isFalse()
                        assertThat(actualControl.postReturn.isCompleted).isFalse()
                        assertThat(sentinel.thread).isEqualTo(prefix.thread)
                        assertThat(sentinel.returned).isSameInstanceAs(actualControl.returnRecord.get())
                        sentinel.returned?.let {
                            assertThat(it).isSameInstanceAs(prefix)
                            assertThat(actualControl.returnThread.get()).isEqualTo(sentinel.thread)
                        }
                        println("READY protocol return opportunity: initial=${System.identityHashCode(prefix.origin)} " +
                            "owner=${prefix.owner.hashCode()} socket=${System.identityHashCode(prefix.origin.socket)} " +
                            "serverSocket=${System.identityHashCode(entry.serverSocket)} rpc=${entry.requestId.hashCode()} " +
                            "transport=${prefix.transport} counted=${prefix.counted} " +
                            "startupChild=${prefix.startupChild} vmRoot=${prefix.vmRoot} " +
                            "accessCompleted=${prefix.access.isCompleted} listCompleted=${prefix.list.isCompleted} " +
                            "writersCompleted=${prefix.writers.all { it.isCompleted }} protocolHeld=${actualControl.protocolRelease.count == 1L} " +
                            "postReturnHeld=${!actualControl.postReturn.isCompleted} prefixMain=${prefix.thread} " +
                            "sentinelMain=${sentinel.thread} returnMain=${actualControl.returnThread.get()} " +
                            "actualSetupReturned=${sentinel.returned != null}")
                        oracleAttempted = true
                        assertWithMessage("READY_INITIAL_PROTOCOL_COMPLETION_NOT_AWAITED")
                            .that(sentinel.returned).isNull()
                        // Release independently, then require the real counted connection and same setup return.
                        actualControl.protocolRelease.countDown()
                        withTimeout(10_000) {
                            actualControl.callbackAfterReply.await()
                            prefix.origin.socket.connections.first { it > prefix.counted }
                            assertThat(actualControl.returned.await()).isSameInstanceAs(prefix)
                        }
                        actualControl.protocolFailure.get()?.let { throw it }
                        assertThat(fixture.fixtureProtocolCurrent).isSameInstanceAs(prefix.origin)
                        assertThat(prefix.origin.session.owner()).isEqualTo(prefix.owner)
                        assertThat(prefix.origin.socket.transport.value).isEqualTo(prefix.transport)
                        actualControl.postReturn.complete(Unit)
                        withTimeout(10_000) { driver.await() }
                        assertThat(driver.isCancelled).isFalse()
                        assertThat(actualControl.remainderCompleted.get()).isTrue()
                        positiveCompleted = true
                        println("READY protocol positive: sameInitialCounted=true sameSetupReturned=true " +
                            "fullOriginalRouteRemainder=true freshRecoveryDraftCache=true driverNormal=true")
                    } catch (error: Throwable) {
                        record(error)
                        println("READY protocol captured body: oracleAttempted=$oracleAttempted type=${error.javaClass.name}")
                    } finally {
                        // Both gates FIRST, before cancellation, joining, or fixture dependency closure.
                        actualControl.releaseBoth()
                        if (!driver.isCompleted) { cancellationRequested = true; driver.cancel() }
                        try {
                            withTimeout(10_000) { driver.join() }
                            driverJoined = driver.isCompleted
                            if (actualControl.driverEntered.get() != null) {
                                check(actualControl.driverExited.isCompleted) { "Structured Main driver finally did not execute" }
                                val exitThread = actualControl.driverExited.await()
                                driverFinallyObserved = true
                                println("READY protocol driver exit: actualJoined=$driverJoined actualFinallyMain=$exitThread " +
                                    "ownCancellationRequested=$cancellationRequested remainderCompleted=${actualControl.remainderCompleted.get()}")
                            }
                        } catch (error: Throwable) { record(error) }
                        if (driverJoined) try { driver.await() } catch (error: CancellationException) {
                            if (!cancellationRequested) record(error)
                        } catch (error: Throwable) { record(error) }
                    }
                }
            }
        } catch (error: Throwable) { record(error) } finally {
            control?.releaseBoth()
            fun safety(action: () -> Unit) { try { action() } catch (error: Throwable) { record(error) } }
            if (control == null || driverJoined) {
                if (setupCompleted) safety { fixture.tearDown() }
                safety {
                    mainTerminated = fixture.fixtureAwaitMainTermination()
                    check(mainTerminated) { "Protocol fixture Main executor did not terminate" }
                }
            } else safety { error("Protocol driver did not exit; dependencies remain open") }
            println("READY protocol safety: oracleAttempted=$oracleAttempted positiveCompleted=$positiveCompleted " +
                "protocolReleased=${control?.protocolRelease?.count == 0L} postReturnReleased=${control?.postReturn?.isCompleted == true} " +
                "actualDriverJoined=$driverJoined actualDriverFinally=$driverFinallyObserved " +
                "dependenciesClosed=${fixture.fixtureDependenciesClosed} mainExecutorTerminated=$mainTerminated")
        }
        primary?.let { throw it }
    }
}
