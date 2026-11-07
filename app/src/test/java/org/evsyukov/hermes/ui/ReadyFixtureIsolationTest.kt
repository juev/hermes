package org.evsyukov.hermes.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.evsyukov.hermes.AppContainer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class ReadyFixtureIsolationTest {
    @Test
    fun `READY fixture state is independent of a previous permanent-connection fixture`() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        var fixtureA: LocalActionLifetimeTest? = null
        var fixtureB: LocalActionLifetimeTest? = null
        var containerA: AppContainer? = null
        var containerB: AppContainer? = null
        var setupACompleted = false
        var setupBCompleted = false
        var teardownAAttempted = false
        var teardownBAttempted = false
        var previousKeep: Boolean? = null
        var keepEditAttempted = false
        var oracleAttempted = false
        var mainAExited = false
        var mainBExited = false
        var restoredByActualApi = false
        var primary: Throwable? = null
        try {
            val a = LocalActionLifetimeTest()
            fixtureA = a
            assertThat(a.fixtureApplication).isSameInstanceAs(application)
            a.setUp()
            setupACompleted = true
            val actualA = a.fixtureCreateContainer()
            containerA = actualA
            val aRoot = actualA.scope.coroutineContext.job
            val aCacheRoot = a.fixtureProducerScope().coroutineContext.job
            val initialLoaded = runBlocking {
                withTimeout(10_000) { actualA.settings.keepConnected.filterNotNull().first() }
            }
            previousKeep = initialLoaded
            keepEditAttempted = true
            val editedLoaded = runBlocking {
                actualA.settings.update { it.copy(keepConnected = true) }
                withTimeout(10_000) { actualA.settings.keepConnected.filterNotNull().first { it } }
            }
            assertThat(editedLoaded).isTrue()
            println("READY isolation A edit-read: application=${System.identityHashCode(application)} " +
                "fixture=${System.identityHashCode(a)} container=${System.identityHashCode(actualA)} " +
                "initialLoaded=$initialLoaded actualEditedLoaded=$editedLoaded actualRoot=$aRoot")
            teardownAAttempted = true
            a.tearDown()
            mainAExited = a.fixtureAwaitMainTermination()
            assertThat(aRoot.isCompleted).isTrue()
            assertThat(aCacheRoot.isCompleted).isTrue()
            assertThat(a.fixtureDependenciesClosed).isTrue()
            assertThat(mainAExited).isTrue()
            println("READY isolation A teardown: containerRootCompleted=${aRoot.isCompleted} " +
                "cacheRootCompleted=${aCacheRoot.isCompleted} dependenciesClosed=${a.fixtureDependenciesClosed} " +
                "mainExecutorTerminated=$mainAExited delegateBackingScopeReceipt=not-owned-not-asserted")

            val b = LocalActionLifetimeTest()
            fixtureB = b
            assertThat(b).isNotSameInstanceAs(a)
            assertThat(b.fixtureApplication).isSameInstanceAs(application)
            b.setUp()
            setupBCompleted = true
            val actualB = b.fixtureCreateContainer()
            containerB = actualB
            val bRoot = actualB.scope.coroutineContext.job
            assertThat(actualB).isNotSameInstanceAs(actualA)
            // First actual loaded value, never a wait for the desired default false.
            val firstLoadedB = runBlocking {
                withTimeout(10_000) { actualB.settings.keepConnected.filterNotNull().first() }
            }
            println("READY isolation B loaded: application=${System.identityHashCode(b.fixtureApplication)} " +
                "sameApplication=${b.fixtureApplication === application} fixture=${System.identityHashCode(b)} " +
                "container=${System.identityHashCode(actualB)} actualFirstLoaded=$firstLoadedB " +
                "aDependenciesClosed=${a.fixtureDependenciesClosed} aMainExecutorTerminated=$mainAExited actualRoot=$bRoot")
            oracleAttempted = true
            assertWithMessage("READY_FIXTURE_INHERITED_PERMANENT_SETTING").that(firstLoadedB).isFalse()
            assertThat(bRoot.isActive).isTrue()
        } catch (error: Throwable) {
            primary = error
            println("READY isolation captured body: oracleAttempted=$oracleAttempted type=${error.javaClass.name}")
        } finally {
            fun safety(action: () -> Unit) {
                try { action() } catch (later: Throwable) {
                    val first = primary
                    if (first == null) primary = later else if (later !== first) first.addSuppressed(later)
                }
            }
            // Restore only after the B oracle/body outcome was captured, never at B startup.
            val original = previousKeep
            val restoreTarget = containerB ?: containerA
            if (keepEditAttempted && original != null && restoreTarget != null) safety {
                runBlocking { withTimeout(10_000) { restoreTarget.settings.update { it.copy(keepConnected = original) } } }
                restoredByActualApi = true
            }
            val b = fixtureB
            if (b != null && setupBCompleted && !teardownBAttempted) {
                teardownBAttempted = true
                safety { b.tearDown() }
            }
            if (b != null) safety {
                mainBExited = b.fixtureAwaitMainTermination()
                check(mainBExited) { "Fixture B Main executor did not terminate" }
            }
            val a = fixtureA
            if (a != null && setupACompleted && !teardownAAttempted) {
                teardownAAttempted = true
                safety { a.tearDown() }
            }
            if (a != null) safety {
                mainAExited = a.fixtureAwaitMainTermination()
                check(mainAExited) { "Fixture A Main executor did not terminate" }
            }
            println("READY isolation safety: oracleAttempted=$oracleAttempted restoredAfterBodyByActualApi=$restoredByActualApi " +
                "aDependenciesClosed=${a?.fixtureDependenciesClosed} bDependenciesClosed=${b?.fixtureDependenciesClosed} " +
                "aMainExecutorTerminated=$mainAExited bMainExecutorTerminated=$mainBExited " +
                "delegatedStoreScopeTermination=not-owned-not-asserted")
        }
        primary?.let { throw it }
    }
}
