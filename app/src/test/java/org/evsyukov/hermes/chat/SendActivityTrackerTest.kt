package org.evsyukov.hermes.chat

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The transition matrix of [SendActivityTracker]: which local sends make an idle join answer too old
 * to speak for the chat, and which ones a fresh answer may drop. The controller asks exactly these
 * questions in `adopt`; a case here is one more ordering it has to answer.
 */
class SendActivityTrackerTest {
    @Test
    fun `a pending send entered after the answer is newer than it`() {
        val sends = SendActivityTracker()
        val mark = sends.mark()
        sends.begin()
        assertThat(sends.protects(mark)).isTrue()
    }

    @Test
    fun `a pending send entered before the answer is newer still, since the server could not see it`() {
        val sends = SendActivityTracker()
        sends.begin()
        val mark = sends.mark()
        assertThat(sends.protects(mark)).isTrue()
    }

    @Test
    fun `a send submitted after the answer is newer than it`() {
        val sends = SendActivityTracker()
        val id = sends.begin()
        val mark = sends.mark()
        sends.submitted(id)
        assertThat(sends.protects(mark)).isTrue()
    }

    @Test
    fun `a turn started after the answer is newer than it`() {
        val sends = SendActivityTracker()
        val id = sends.begin()
        val mark = sends.mark()
        sends.submitted(id)
        sends.started()
        assertThat(sends.protects(mark)).isTrue()
    }

    @Test
    fun `a send submitted before the answer does not make it older`() {
        val sends = SendActivityTracker()
        val id = sends.begin()
        sends.submitted(id)
        val mark = sends.mark()
        assertThat(sends.protects(mark)).isFalse()
    }

    @Test
    fun `a turn started before the answer does not make it older`() {
        val sends = SendActivityTracker()
        val id = sends.begin()
        sends.submitted(id)
        sends.started()
        val mark = sends.mark()
        assertThat(sends.protects(mark)).isFalse()
    }

    @Test
    fun `a turn ended after the answer no longer makes it older`() {
        val sends = SendActivityTracker()
        val id = sends.begin()
        val mark = sends.mark()
        sends.submitted(id)
        sends.started()
        sends.ended()
        assertThat(sends.protects(mark)).isFalse()
    }

    @Test
    fun `a terminal of an older turn leaves a newer submitted send protected`() {
        val sends = SendActivityTracker()
        val older = sends.begin()
        sends.submitted(older)
        sends.started()
        val newer = sends.begin()
        val mark = sends.mark()
        sends.submitted(newer)
        // The older turn's outcome carries no turn id; the started local send is the older one.
        sends.ended()
        assertThat(sends.protects(mark)).isTrue()
    }

    @Test
    fun `a terminal with no started send leaves a submitted one alone`() {
        val sends = SendActivityTracker()
        val id = sends.begin()
        val mark = sends.mark()
        sends.submitted(id)
        sends.ended()
        assertThat(sends.protects(mark)).isTrue()
    }

    @Test
    fun `a failed send is forgotten, so it cannot poison a fresh answer`() {
        val sends = SendActivityTracker()
        val failed = sends.begin()
        sends.finish(failed)
        val mark = sends.mark()
        assertThat(sends.protects(mark)).isFalse()
    }

    @Test
    fun `a fresh idle answer discards a turn whose outcome was lost`() {
        val sends = SendActivityTracker()
        val id = sends.begin()
        sends.submitted(id)
        sends.started()
        val mark = sends.mark()
        assertThat(sends.protects(mark)).isFalse()
        sends.discard(mark)
        // A later answer is newer still; the lost outcome leaves nothing behind.
        assertThat(sends.protects(sends.mark())).isFalse()
    }

    @Test
    fun `a stop forgets every local send`() {
        val sends = SendActivityTracker()
        sends.begin()
        val submitted = sends.begin()
        sends.submitted(submitted)
        val mark = sends.mark()
        sends.stopAll()
        assertThat(sends.protects(mark)).isFalse()
    }
}
