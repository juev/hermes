package org.evsyukov.hermes.ui

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class ReadyFixtureCleanupFailureTest {
    private data class DeletionEntry(
        val directory: File,
        val userRoots: List<Job>,
        val storeRoots: List<Job>,
        val usersCompleted: List<Boolean>,
        val storesCompleted: List<Boolean>,
        val existingFiles: List<File>,
    )

    @Test
    fun `READY owned file deletion failure releases independent resources and preserves close errors`() {
        val fixture = LocalActionLifetimeTest()
        val removal = IOException("synthetic owned-directory removal refusal")
        val additionalClose = IllegalStateException("independent close failure marker")
        val entries = mutableListOf<DeletionEntry>()
        var setupCompleted = false
        var oracleAttempted = false
        var primary: Throwable? = null
        var capturedTeardown: Throwable? = null
        var directory: File? = null
        var retainedFiles: List<File> = emptyList()
        try {
            fixture.setUp()
            setupCompleted = true
            val actualContainer = fixture.fixtureCreateContainer()
            runBlocking {
                withTimeout(10_000) {
                    actualContainer.settings.keepConnected.filterNotNull().first()
                    actualContainer.settings.update { it.copy(haptics = !it.haptics) }
                }
            }
            val users = fixture.fixtureUserRoots
            val stores = fixture.fixtureOwnedStoreRoots
            assertThat(users).contains(actualContainer.scope.coroutineContext.job)
            assertThat(stores).hasSize(6)
            assertThat(stores.toSet()).hasSize(6)
            val ownedDirectory = checkNotNull(fixture.fixtureOwnedDirectory)
            directory = ownedDirectory
            val writtenFiles = fixture.fixtureOwnedFiles.filter { it.exists() }
            retainedFiles = writtenFiles
            assertThat(ownedDirectory.isDirectory).isTrue()
            assertThat(writtenFiles).isNotEmpty()
            fixture.fixtureDeleteOwnedDirectory = { actualDirectory ->
                entries += DeletionEntry(
                    actualDirectory, users.toList(), stores.toList(),
                    users.map { it.isCompleted }, stores.map { it.isCompleted },
                    fixture.fixtureOwnedFiles.filter { it.exists() },
                )
                throw removal
            }
            fixture.closeFailure = additionalClose
            try { fixture.tearDown() } catch (actual: Throwable) { capturedTeardown = actual }
            val actual = capturedTeardown
            assertThat(actual).isSameInstanceAs(removal)
            assertThat(entries).hasSize(1)
            val entry = entries.single()
            assertThat(entry.directory).isSameInstanceAs(ownedDirectory)
            assertThat(entry.userRoots).containsExactlyElementsIn(users).inOrder()
            assertThat(entry.storeRoots).containsExactlyElementsIn(stores).inOrder()
            assertThat(entry.usersCompleted.all { it }).isTrue()
            assertThat(entry.storesCompleted).hasSize(6)
            assertThat(entry.storesCompleted.all { it }).isTrue()
            assertThat(users.all { it.isCompleted }).isTrue()
            assertThat(stores.all { it.isCompleted }).isTrue()
            assertThat(entry.existingFiles).containsAtLeastElementsIn(writtenFiles)
            println("READY owned deletion opportunity: entries=${entries.size} sameRemoval=${actual === removal} " +
                "actualType=${actual?.javaClass?.name} userRoots=${entry.userRoots} " +
                "usersCompletedAtEntry=${entry.usersCompleted} storeRoots=${entry.storeRoots} " +
                "storesCompletedAtEntry=${entry.storesCompleted} directoryExists=${ownedDirectory.exists()} " +
                "writtenFilesRetained=${writtenFiles.all { it.exists() }} " +
                "independentDependenciesClosed=${fixture.fixtureIndependentDependenciesClosed} " +
                "additionalCloseRetained=${removal.suppressed.any { it === additionalClose }}")
            println("READY owned deletion actual teardown throwable:")
            removal.printStackTrace(System.out)
            oracleAttempted = true
            assertWithMessage("READY_OWNED_DELETE_FAILURE_SKIPPED_INDEPENDENT_CLEANUP")
                .that(fixture.fixtureIndependentDependenciesClosed).isTrue()
            val actualMainTerminated = fixture.fixtureAwaitMainTermination()
            assertThat(actualMainTerminated).isTrue()
            assertThat(ownedDirectory.isDirectory).isTrue()
            assertThat(writtenFiles.all { it.exists() }).isTrue()
            assertThat(removal.suppressed.toList()).contains(additionalClose)
            println("READY owned deletion positive: independentDependenciesClosed=${fixture.fixtureIndependentDependenciesClosed} " +
                "mainExecutorTerminated=$actualMainTerminated directoryRetained=${ownedDirectory.isDirectory} " +
                "writtenFilesRetained=${writtenFiles.all { it.exists() }} additionalCloseRetained=true")
        } catch (error: Throwable) {
            primary = error
            println("READY owned deletion captured body: oracleAttempted=$oracleAttempted type=${error.javaClass.name}")
        } finally {
            fun safety(action: () -> Unit) {
                try { action() } catch (later: Throwable) {
                    val first = primary
                    if (first == null) primary = later else if (later !== first) first.addSuppressed(later)
                }
            }
            if (setupCompleted) {
                // Clear only after the body/oracle outcome, then retry the real teardown.
                fixture.fixtureDeleteOwnedDirectory = null
                fixture.closeFailure = null
                safety { fixture.tearDown() }
            }
            var mainTerminated = false
            safety {
                mainTerminated = fixture.fixtureAwaitMainTermination()
                check(mainTerminated) { "Cleanup probe Main executor did not terminate" }
            }
            if (setupCompleted) safety {
                check(fixture.fixtureDependenciesClosed) { "Cleanup probe dependencies remained open after retry" }
                check(fixture.fixtureOwnedStoreRoots.all { it.isCompleted }) { "Cleanup probe stores remained live" }
                check(fixture.fixtureUserRoots.all { it.isCompleted }) { "Cleanup probe users remained live" }
                check(directory?.exists() != true && retainedFiles.none { it.exists() }) { "Cleanup probe owned files survived retry" }
            }
            println("READY owned deletion safety: oracleAttempted=$oracleAttempted setupCompleted=$setupCompleted " +
                "sameCapturedRemoval=${capturedTeardown === removal} faultEntries=${entries.size} " +
                "dependenciesClosed=${fixture.fixtureDependenciesClosed} mainExecutorTerminated=$mainTerminated " +
                "directoryAbsent=${directory?.exists() != true} writtenFilesAbsent=${retainedFiles.none { it.exists() }}")
        }
        primary?.let { throw it }
    }
}
