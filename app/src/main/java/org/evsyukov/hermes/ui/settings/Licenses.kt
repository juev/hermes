package org.evsyukov.hermes.ui.settings

import android.content.Context
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.util.withJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.evsyukov.hermes.R

/** A license as the screen shows it: its short name and the full text. */
data class LicenseText(val name: String, val text: String)

/** Something the APK is built from, with the licenses it comes under. */
data class Component(val name: String, val version: String, val licenses: List<LicenseText>)

/** What the Licenses screen lists: the app itself, its libraries and the Go modules of the tailnet node. */
data class Licenses(val app: LicenseText, val libraries: List<Component>, val tailnet: List<Component>)

@Serializable
private data class GoModule(val name: String, val version: String, val license: String)

private val json = Json { ignoreUnknownKeys = true }

/** The Go modules that scripts/build-tailnet.sh found in the node, the standard library first. */
fun tailnetComponents(text: String): List<Component> =
    json.decodeFromString(ListSerializer(GoModule.serializer()), text).map { module ->
        Component(module.name, module.version, listOf(LicenseText(licenseName(module.license), module.license)))
    }

/**
 * The usual names of the licenses in a text, for the list; a module may put several into one file.
 * The text itself is what counts.
 */
fun licenseName(text: String): String = buildList {
    if ("Redistribution and use in source and binary forms" in text) {
        // The clause on endorsement is what the third clause of BSD adds to the second.
        add(if ("endorse or promote products" in text) "BSD-3-Clause" else "BSD-2-Clause")
    }
    if ("Apache License" in text && "Version 2.0" in text) add("Apache-2.0")
    if ("Permission is hereby granted, free of charge" in text) add("MIT")
    if ("Permission to use, copy, modify, and/or distribute" in text || "Permission to use, copy, modify, and distribute" in text) add("ISC")
    if ("Mozilla Public License" in text) add("MPL-2.0")
}.joinToString().ifEmpty { "License" }

/** Reads the texts the build put into the APK; runs off the main thread. */
fun loadLicenses(context: Context): Licenses {
    val assets = context.assets
    val app = assets.open("legal/LICENSE").bufferedReader().use { it.readText() }
    val libraries = Libs.Builder().withJson(context, R.raw.aboutlibraries).build().libraries.map { library ->
        Component(
            library.name,
            library.artifactVersion.orEmpty(),
            library.licenses.map { LicenseText(it.spdxId ?: it.name, it.licenseContent ?: it.url.orEmpty()) },
        )
    }.sortedBy { it.name.lowercase() }
    // Without the node in the build there is no such file, and nothing of Go in the APK.
    val tailnet = if ("tailnet.json" in assets.list("legal").orEmpty()) {
        tailnetComponents(assets.open("legal/tailnet.json").bufferedReader().use { it.readText() })
    } else {
        emptyList()
    }
    return Licenses(LicenseText("MIT", app), libraries, tailnet)
}
