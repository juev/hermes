package org.evsyukov.hermes.ui.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LicensesTest {
    @Test
    fun `go modules keep their order and get a license name`() {
        val json = """
            [
              {"name": "Go standard library", "version": "go1.27.3", "license": "Copyright 2009 The Go Authors.\n\nRedistribution and use in source and binary forms ... Neither the name of Google LLC nor the names of its contributors may be used to endorse or promote products ..."},
              {"name": "github.com/x448/float16", "version": "v0.8.4", "license": "MIT License\n\nPermission is hereby granted, free of charge, to any person"}
            ]
        """.trimIndent()
        val components = tailnetComponents(json)
        assertThat(components.map { it.name }).containsExactly("Go standard library", "github.com/x448/float16").inOrder()
        assertThat(components.map { it.licenses.single().name }).containsExactly("BSD-3-Clause", "MIT").inOrder()
        assertThat(components[1].licenses.single().text).startsWith("MIT License")
    }

    @Test
    fun `license names come from the usual wording`() {
        assertThat(licenseName("Apache License\nVersion 2.0, January 2004")).isEqualTo("Apache-2.0")
        assertThat(licenseName("Redistribution and use in source and binary forms, with or without modification")).isEqualTo("BSD-2-Clause")
        assertThat(licenseName("Redistribution and use in source and binary forms ... (3) The name of the author may not be used to endorse or promote products"))
            .isEqualTo("BSD-3-Clause")
        // One file of klauspost/compress carries the license of its Go code and two more.
        assertThat(licenseName("Redistribution and use in source and binary forms ... endorse or promote products ... Apache License Version 2.0 ... Permission is hereby granted, free of charge"))
            .isEqualTo("BSD-3-Clause, Apache-2.0, MIT")
        assertThat(licenseName("Permission to use, copy, modify, and/or distribute this software")).isEqualTo("ISC")
        assertThat(licenseName("Mozilla Public License Version 2.0")).isEqualTo("MPL-2.0")
        assertThat(licenseName("Some terms of its own")).isEqualTo("License")
    }

    @Test
    fun `a text is cut into paragraphs at empty lines`() {
        assertThat(paragraphs("First line\nsame paragraph\r\n\r\n\u000cSecond\n  \n\nThird\n"))
            .containsExactly("First line same paragraph", "Second", "Third").inOrder()
    }

    @Test
    fun `wrapped lines are joined while list items keep their own lines`() {
        val text = "are met:\n\n   * Redistributions of source code must retain\nthe notice.\n   * Redistributions in binary form\nmust reproduce it.\n\n(1) One\ncontinued\n(2) Two"
        assertThat(paragraphs(text)).containsExactly(
            "are met:",
            "* Redistributions of source code must retain the notice.\n* Redistributions in binary form must reproduce it.",
            "(1) One continued\n(2) Two",
        ).inOrder()
    }
}
