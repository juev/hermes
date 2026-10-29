package org.evsyukov.hermes.chat

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlainTextTest {
    @Test
    fun `emphasis, code and links keep only their words`() {
        val text = "**Готово**: *почти* всё, ~~кроме~~ `make test` и [отчёта](https://example.org/r)."
        assertThat(plainText(text)).isEqualTo("Готово: почти всё, кроме make test и отчёта.")
    }

    @Test
    fun `headings, quotes and lists lose their markers`() {
        val text = "## Итог\n\n> важно\n\n- один\n- [x] два\n1. три"
        assertThat(plainText(text)).isEqualTo("Итог\n\nважно\n\n• один\n• два\n1. три")
    }

    @Test
    fun `code fences go, the code stays`() {
        assertThat(plainText("Запусти:\n```bash\nmake test\n```")).isEqualTo("Запусти:\nmake test")
    }

    @Test
    fun `a table becomes rows of cells`() {
        val text = "| Хост | Диск |\n|---|:---:|\n| loffe | 34% |"
        assertThat(plainText(text)).isEqualTo("Хост · Диск\nloffe · 34%")
    }

    @Test
    fun `rules and extra blank lines are dropped`() {
        assertThat(plainText("один\n\n---\n\n\n\nдва")).isEqualTo("один\n\nдва")
    }

    @Test
    fun `identifiers and arithmetic are not emphasis`() {
        assertThat(plainText("snake_case_name и 2 * 3 * 4")).isEqualTo("snake_case_name и 2 * 3 * 4")
    }
}
