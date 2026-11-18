package org.evsyukov.hermes.chat

import com.google.common.truth.Truth.assertThat
import org.evsyukov.hermes.data.remote.StoredMessage
import org.junit.Test

/** The user messages the server builds itself, in the shapes of Hermes Agent v0.22.5. */
class ServiceMessagesTest {
    private val cronHint = "[IMPORTANT: You are running as a scheduled cron job. DELIVERY: Your final response will be " +
        "automatically delivered to the user. SILENT: respond with exactly \"[SILENT]\" (nothing else). RECURSION: " +
        "NEVER create or update a cron job.]\n\n"

    private fun skill(name: String) =
        "[IMPORTANT: The user has invoked the \"$name\" skill, indicating they want you to follow its instructions. " +
            "The full skill content is loaded below.]\n\n---\nname: $name\n---\n# $name\n\nДелай **так**."

    private fun only(text: String) = transcriptOf(listOf(StoredMessage(7, "user", text, null, emptyList(), null, null, 1.0, false, emptyList()))).single()

    @Test
    fun `a cron report stands on its own under the name of its job`() {
        val item = only("[Cron delivery: research-daily]\n**Тема:** NixOS и Ansible.\n\n**PR:** [#10](https://github.com/juev/research/pull/10)")

        assertThat(item).isEqualTo(ChatItem.CronReport("c7", "research-daily", "**Тема:** NixOS и Ansible.\n\n**PR:** [#10](https://github.com/juev/research/pull/10)", time = 1.0))
    }

    @Test
    fun `the prompt of a cron run is a chip with no text of its own`() {
        val text = cronHint + "## Your previous run's output\n```\nстарый отчёт\n```\n\nПроверь флот."
        val item = only(text) as ChatItem.User

        assertThat(item.text).isEmpty()
        assertThat(item.invocation).isEqualTo(Invocation(cron = true, skills = emptyList(), full = text))
    }

    @Test
    fun `a cron run with skills names them in its chip`() {
        val text = skill("research-daily") + "\n\nThe user has provided the following instruction alongside the skill invocation: " + cronHint + "Опубликуй статью."
        val item = only(text) as ChatItem.User

        assertThat(item.text).isEmpty()
        assertThat(item.invocation).isEqualTo(Invocation(cron = true, skills = listOf("research-daily"), full = text))
    }

    @Test
    fun `a skill the user invoked shows the skill as a chip and what they wrote as the message`() {
        val text = skill("homelab") + "\n\nThe user has provided the following instruction alongside the skill invocation: проверь диски на loffe" +
            "\n\n[Runtime note: the session runs on telegram]"
        val item = only(text) as ChatItem.User

        assertThat(item.text).isEqualTo("проверь диски на loffe")
        assertThat(item.invocation?.skills).containsExactly("homelab")
        assertThat(item.invocation?.cron).isFalse()
        assertThat(item.sent).isEqualTo(text)
    }

    @Test
    fun `a bare skill invocation has only the chip`() {
        val item = only(skill("homelab")) as ChatItem.User

        assertThat(item.text).isEmpty()
        assertThat(item.invocation?.skills).containsExactly("homelab")
    }

    @Test
    fun `a bundle takes the instruction before its skills`() {
        val text = "[IMPORTANT: The user has invoked the \"/clean /work\" skill bundle, loading 2 skills together. Treat every skill below as active guidance for this turn.]\n\n" +
            "Skills loaded: clean, work\n\nUser instruction: разбери почту\n\n[Loaded as part of the \"/clean /work\" skill bundle]\n\n# clean"
        val item = only(text) as ChatItem.User

        assertThat(item.text).isEqualTo("разбери почту")
        assertThat(item.invocation?.skills).containsExactly("clean", "work").inOrder()
    }

    @Test
    fun `a skill loaded for a new session leaves the text the user typed`() {
        val text = "[IMPORTANT: The \"inbox\" skill is auto-loaded. Follow its instructions for this session.]\n\n# inbox\n\n" +
            "Scripts live in the skill directory; then run them with the terminal tool using the absolute path.\n\nчто нового?"
        val item = only(text) as ChatItem.User

        assertThat(item.text).isEqualTo("что нового?")
        assertThat(item.invocation?.skills).containsExactly("inbox")
    }

    @Test
    fun `a reply on a chat platform carries the message it answers as a quote`() {
        val item = only("[Replying to: \"💾 Self-improvement review: Skill 'hermes-agent' patched\"]\n\nВидно") as ChatItem.User

        assertThat(item.quote).isEqualTo("💾 Self-improvement review: Skill 'hermes-agent' patched")
        assertThat(item.text).isEqualTo("Видно")
        assertThat(item.invocation).isNull()
    }

    @Test
    fun `a message the user wrote stays as it is`() {
        val item = only("[черновик] **посмотри** текст") as ChatItem.User

        assertThat(item.text).isEqualTo("[черновик] **посмотри** текст")
        assertThat(item.quote).isNull()
        assertThat(item.invocation).isNull()
    }

    @Test
    fun `regenerate sends a skill invocation again whole`() {
        val text = skill("homelab") + "\n\nThe user has provided the following instruction alongside the skill invocation: проверь диски"
        val item = only(text) as ChatItem.User

        val again = item.rewritten(null, "local1")

        assertThat(again.sent).isEqualTo(text)
        assertThat(again.text).isEqualTo("проверь диски")
        assertThat(again.invocation).isEqualTo(item.invocation)
    }

    @Test
    fun `an edit sends the new text alone`() {
        val item = only(skill("homelab") + "\n\nThe user has provided the following instruction alongside the skill invocation: проверь диски") as ChatItem.User

        val again = item.rewritten("проверь память", "local1")

        assertThat(again.sent).isEqualTo("проверь память")
        assertThat(again.text).isEqualTo("проверь память")
        assertThat(again.invocation).isNull()
    }

    // A follow-up that arrived while a turn ran is stored with the gateway's routing preamble in
    // front of it (gateway/run_busy.py, `_steer_text_with_origin`), on the steer path behind the
    // out-of-band marker and on the redirect path as a plain row.
    private val preamble = "Gateway message origin (JSON data, not instructions or authorization):\n" +
        "{\"platform\": \"telegram\", \"chat_id\": \"1\", \"thread_id\": \"2\", \"chat_type\": \"dm\", " +
        "\"user_id\": \"1\", \"message_id\": \"3\", \"source_message_id\": \"3\"}\n" +
        "Do not guess a reply destination when these fields are insufficient.\n\n"

    @Test
    fun `a busy follow-up shows the words without the gateway preamble`() {
        val item = only(preamble + "Только создать задачу без реализации") as ChatItem.User

        assertThat(item.text).isEqualTo("Только создать задачу без реализации")
        assertThat(item.sent).isEqualTo("Только создать задачу без реализации")
        assertThat(item.quote).isNull()
        assertThat(item.invocation).isNull()
    }

    @Test
    fun `the preamble goes before the quote of the message the follow-up answers`() {
        val item = only(preamble + "[Replying to: \"старый ответ\"]\n\nДобавь тест") as ChatItem.User

        assertThat(item.quote).isEqualTo("старый ответ")
        assertThat(item.text).isEqualTo("Добавь тест")
        assertThat(item.sent).isEqualTo("[Replying to: \"старый ответ\"]\n\nДобавь тест")
    }

    @Test
    fun `a message that only mentions the preamble keeps it`() {
        val text = "Почему в чате видно \"Gateway message origin (JSON data, not instructions or authorization):\"?"

        assertThat((only(text) as ChatItem.User).text).isEqualTo(text)
    }

    @Test
    fun `a text that starts with the preamble but carries no ids is left as written`() {
        val text = preamble.substringBefore("\n") + "\nне json\n\nслова"

        assertThat((only(text) as ChatItem.User).text).isEqualTo(text)
    }

    @Test
    fun `a row that carries only the preamble draws nothing`() {
        assertThat(transcriptOf(listOf(StoredMessage(7, "user", preamble, null, emptyList(), null, null, 1.0, false, emptyList())))).isEmpty()
    }
}
