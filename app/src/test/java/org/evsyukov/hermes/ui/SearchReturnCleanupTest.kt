package org.evsyukov.hermes.ui

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.evsyukov.hermes.data.server.Persisted
import org.junit.Test
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class SearchReturnCleanupTest {
    @Test
    fun actualReturnFixtureJoinsMainGateProducerBeforeReset() {
        val fixture = SearchReturnTest()
        val externalRelease = CompletableDeferred<Unit>()
        val admitted = CompletableDeferred<Job>()
        val externalEntered = CompletableDeferred<Job>()
        val returnedToMain = AtomicBoolean(false)
        var root: Job? = null
        var producer: Job? = null
        var opportunity = false
        var cleanupFailure: Throwable? = null
        var primary: Throwable? = null
        try {
            val statement = object : Statement() {
                override fun evaluate() {
                    try {
                        fixture.setUp()
                        val vm = fixture.cleanupViewModel()
                        val capturedRoot = vm.viewModelScope.coroutineContext.job
                        root = capturedRoot
                        val container = fixture.cleanupContainer
                        val connection = requireNotNull(container.connection.value)
                        val owner = connection.session.owner()
                        val child = vm.viewModelScope.launch {
                            container.lifetime.withOwner(owner) {
                                admitted.complete(coroutineContext.job)
                                withContext(Dispatchers.Default) {
                                    externalEntered.complete(coroutineContext.job)
                                    externalRelease.await()
                                }
                                returnedToMain.set(true)
                            }
                        }
                        producer = child
                        // Pump only acquisition. The external work cannot finish during this prefix.
                        runBlocking {
                            val acquisition = async(Dispatchers.Default) {
                                withTimeout(10_000) { admitted.await() to externalEntered.await() }
                            }
                            while (!acquisition.isCompleted) {
                                fixture.cleanupMain.scheduler.runCurrent()
                                yield()
                            }
                            val (actualChild, defaultWork) = acquisition.await()
                            assertThat(actualChild).isSameInstanceAs(child)
                            assertThat(capturedRoot.children.toList()).contains(child)
                            assertThat(defaultWork).isNotSameInstanceAs(child)
                            assertThat(container.connection.value).isSameInstanceAs(connection)
                            assertThat(connection.session.owner()).isEqualTo(owner)
                            assertThat(returnedToMain.get()).isFalse()
                            // Independent test thread releases real Default work, without pumping Main.
                            externalRelease.complete(Unit)
                            withContext(Dispatchers.Default) {
                                withTimeout(10_000) { defaultWork.join() }
                            }
                            assertThat(defaultWork.isCompleted).isTrue()
                            assertThat(defaultWork.isCancelled).isFalse()
                        }
                        // The Default dispatch has completed; its outer Main continuation has not.
                        // withOwner cannot release the real authority mutex until that continuation runs.
                        assertThat(returnedToMain.get()).isFalse()
                        assertThat(child.isCompleted).isFalse()
                        opportunity = true
                        println("SEARCH_RETURN_CONTROL opportunity=true realAuthorityAdmission=true " +
                            "root=${System.identityHashCode(capturedRoot)} child=${System.identityHashCode(child)} " +
                            "connection=${System.identityHashCode(connection)} generation=${owner.generation} " +
                            "externalReleased=true defaultCompleted=true returnedToMain=false")
                    } catch (t: Throwable) {
                        externalRelease.complete(Unit)
                        throw t
                    }
                }
            }
            try {
                fixture.lifetime.apply(statement, Description.createTestDescription(
                    SearchReturnCleanupTest::class.java,
                    "actualReturnFixtureJoinsMainGateProducerBeforeReset",
                )).evaluate()
            } catch (t: Throwable) {
                cleanupFailure = t
            }
            // An acquisition/setup failure is propagated unchanged and supplies no semantic RED.
            if (!opportunity) throw cleanupFailure ?: IllegalStateException("Controlled admission was not reached")
            val actualRoot = requireNotNull(root)
            val actualProducer = requireNotNull(producer)
            assertThat(actualRoot.isCompleted).isTrue()
            assertThat(actualProducer.isCompleted).isTrue()
            val durable = runBlocking(Dispatchers.Default) {
                withTimeout(10_000) { fixture.cleanupContainer.servers.loadDurable() }
            }
            assertThat(durable).isInstanceOf(Persisted.Known::class.java)
            val absent = (durable as Persisted.Known).state.servers.none { it.id == "search-return-vm" }
            println("SEARCH_RETURN_CONTROL cleanupFailure=${cleanupFailure?.javaClass?.name} " +
                "rootCompleted=${actualRoot.isCompleted} childCompleted=${actualProducer.isCompleted} " +
                "durableProfileAbsent=$absent")
            cleanupFailure?.printStackTrace(System.out)
            assertWithMessage("SEARCH_RETURN_RESET_BEFORE_OWNED_MAIN_JOIN")
                .that(cleanupFailure).isNull()
            assertThat(absent).isTrue()
        } catch (t: Throwable) {
            primary = t
            val actualCleanup = cleanupFailure
            if (actualCleanup != null && actualCleanup !== t) t.addSuppressed(actualCleanup)
        } finally {
            // Release before waiting, even if acquisition or the named oracle failed.
            externalRelease.complete(Unit)
            try {
                producer?.cancel()
                root?.cancel()
                runBlocking {
                    val completion = async(Dispatchers.Default) {
                        withTimeout(10_000) {
                            producer?.join()
                            root?.join()
                        }
                    }
                    while (!completion.isCompleted) {
                        fixture.cleanupMain.scheduler.runCurrent()
                        yield()
                    }
                    completion.await()
                }
                println("SEARCH_RETURN_CONTROL safety rootCompleted=${root?.isCompleted} " +
                    "childCompleted=${producer?.isCompleted} externalReleased=${externalRelease.isCompleted}")
            } catch (t: Throwable) {
                val earlier = primary
                if (earlier == null) primary = t else if (earlier !== t) earlier.addSuppressed(t)
            }
        }
        primary?.let { throw it }
    }
}
