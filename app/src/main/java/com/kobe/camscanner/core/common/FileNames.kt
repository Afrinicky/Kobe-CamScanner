package com.kobe.camscanner.core.common

import java.util.Locale

/**
 * Filename hygiene and de-duplication.
 *
 * Names arrive from three places - the user, the smart-naming heuristic, and imports - and all
 * three can produce something a filesystem will reject. Everything funnels through [sanitise].
 */
object FileNames {

    private const val MAX_LENGTH = 120
    private val ILLEGAL = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]")
    private val COLLAPSE_SPACES = Regex("\\s+")
    private val TRAILING_JUNK = Regex("[. ]+$")

    /** Reserved on FAT/exFAT SD cards, which Android still mounts. */
    private val RESERVED = setOf(
        "con", "prn", "aux", "nul",
        "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
        "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9",
    )

    fun sanitise(raw: String, fallback: String = "Scan"): String {
        var name = raw.replace(ILLEGAL, " ")
            .replace(COLLAPSE_SPACES, " ")
            .trim()
            .replace(TRAILING_JUNK, "")
        if (name.length > MAX_LENGTH) name = name.take(MAX_LENGTH).trim()
        if (name.isEmpty()) return fallback
        if (name.lowercase(Locale.US) in RESERVED) return "$name file"
        return name
    }

    /** Appends " (2)", " (3)", ... until the name is unique against [taken]. */
    fun uniquify(desired: String, taken: Set<String>): String {
        val base = sanitise(desired)
        if (base !in taken) return base
        var n = 2
        while ("$base ($n)" in taken) n++
        return "$base ($n)"
    }

    fun withExtension(name: String, extension: String): String {
        val ext = extension.removePrefix(".")
        return if (name.endsWith(".$ext", ignoreCase = true)) name else "$name.$ext"
    }

    fun stripExtension(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0 && name.length - dot <= 6) name.substring(0, dot) else name
    }

    /** Default document name when nothing better is known: "Scan 2026-08-18 14-02-11". */
    fun defaultScanName(timestamp: Long = System.currentTimeMillis()): String =
        "Scan " + Formatting.fileStamp(timestamp)
}
