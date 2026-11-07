package org.evsyukov.hermes.ui

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class ReadyFixturePermanentConnectionTest {
    @Test
    fun `READY permanent transport stays independent of stale ViewModel admission`() {
        val fixture = LocalActionLifetimeTest()
        var setupCompleted = false
        var bodyCompleted = false
        var mainTerminated = false
        var primary: Throwable? = null
        fun record(error: Throwable) {
            val first = primary
            if (first == null) primary = error else if (first !== error) first.addSuppressed(error)
        }
        try {
            fixture.setUp()
            setupCompleted = true
            runBlocking { fixture.fixtureRunPermanentLane() }
            bodyCompleted = true
        } catch (error: Throwable) {
            record(error)
            println("READY permanent captured body: type=${error.javaClass.name}")
        } finally {
            fun safety(action: () -> Unit) { try { action() } catch (error: Throwable) { record(error) } }
            if (setupCompleted) safety { fixture.tearDown() }
            safety {
                mainTerminated = fixture.fixtureAwaitMainTermination()
                check(mainTerminated) { "Permanent fixture Main executor did not terminate" }
            }
            if (setupCompleted) safety {
                check(fixture.fixtureDependenciesClosed) { "Permanent fixture dependencies remained open" }
                check(fixture.fixtureUserRoots.all { it.isCompleted }) { "Permanent fixture users remained live" }
                check(fixture.fixtureOwnedStoreRoots.all { it.isCompleted }) { "Permanent fixture stores remained live" }
                check(fixture.fixtureOwnedDirectory?.exists() != true) { "Permanent fixture owned files remained" }
            }
            println("READY permanent safety: bodyCompleted=$bodyCompleted dependenciesClosed=${fixture.fixtureDependenciesClosed} " +
                "mainExecutorTerminated=$mainTerminated setupCompleted=$setupCompleted")
        }
        primary?.let { throw it }
    }
}
