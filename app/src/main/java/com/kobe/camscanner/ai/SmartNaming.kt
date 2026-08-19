package com.kobe.camscanner.ai

import com.kobe.camscanner.core.common.FileNames
import com.kobe.camscanner.core.common.Formatting
import com.kobe.camscanner.domain.model.DocumentType
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local document understanding (SDS 39, 40, 41).
 *
 * This is deliberately a heuristic over the OCR text rather than a model. It runs in under a
 * millisecond, needs no download, has no accuracy cliff on an unusual document, and produces a name
 * the user can read and correct — which is what SDS 40 actually asks for ("The user may edit the
 * suggested name"). A learned classifier is a Phase 9 upgrade behind this same interface.
 */
@Singleton
class SmartNaming @Inject constructor() {

    /**
     * Produces the suggested filename and type for a scan.
     *
     * The shape mirrors the worked example in SDS 40: organisation, then what the document is,
     * then its date — "St Elizabeth Hospital - Microbiology Report - 18 Aug 2026".
     */
    fun suggest(ocrText: String, scannedAt: Long = System.currentTimeMillis()): Suggestion {
        val lines = ocrText.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(MAX_LINES_CONSIDERED)
            .toList()

        if (lines.isEmpty()) {
            return Suggestion(
                title = FileNames.defaultScanName(scannedAt),
                type = DocumentType.DOCUMENT,
                confidence = 0f,
            )
        }

        val type = classify(ocrText)
        val organisation = findOrganisation(lines)
        val descriptor = findDescriptor(lines, type)
        val date = findDate(ocrText) ?: Formatting.prettyDate(scannedAt)

        val parts = listOfNotNull(organisation, descriptor, date)
            .distinctBy { it.lowercase(Locale.US) }

        val title = if (parts.size < 2) {
            // Not enough signal for a composed name; a dated type is still better than "Scan".
            "${type.label} - $date"
        } else {
            parts.joinToString(" - ")
        }

        val confidence = when {
            organisation != null && descriptor != null -> 0.85f
            organisation != null || descriptor != null -> 0.6f
            else -> 0.3f
        }

        return Suggestion(FileNames.sanitise(title), type, confidence)
    }

    /** Best-effort document classification (SDS 33 / 41). */
    fun classify(ocrText: String): DocumentType {
        val text = ocrText.lowercase(Locale.US)
        // Ordered most specific first: an invoice usually also contains the word "total", and a
        // passport usually also contains an ID-card vocabulary, so the first match must win.
        TYPE_SIGNALS.forEach { (type, keywords) ->
            val hits = keywords.count { text.contains(it) }
            if (hits >= MIN_SIGNAL_HITS) return type
        }
        return DocumentType.DOCUMENT
    }

    /**
     * Organisations are set in caps at the top of almost every official document — letterheads,
     * lab reports, invoices, certificates. That typographic convention is a far more reliable
     * signal than any keyword list.
     */
    private fun findOrganisation(lines: List<String>): String? {
        val candidate = lines.take(4).firstOrNull { line ->
            val letters = line.filter { it.isLetter() }
            letters.length >= 6 &&
                letters.count { it.isUpperCase() }.toFloat() / letters.length > 0.8f &&
                line.length <= 60 &&
                NON_ORGANISATION.none { line.lowercase(Locale.US).contains(it) }
        } ?: return null
        return titleCase(candidate)
    }

    /** The line that says what the document is: "Laboratory Report", "Tax Invoice". */
    private fun findDescriptor(lines: List<String>, type: DocumentType): String? {
        val explicit = lines.take(8).firstOrNull { line ->
            DESCRIPTOR_WORDS.any { line.lowercase(Locale.US).contains(it) } && line.length <= 60
        }
        if (explicit != null) return titleCase(explicit)
        return type.label.takeIf { type != DocumentType.DOCUMENT }
    }

    /** Finds a date in the text and re-renders it consistently. */
    private fun findDate(text: String): String? {
        DATE_PATTERNS.forEach { pattern ->
            val match = pattern.find(text) ?: return@forEach
            return titleCase(match.value.replace(Regex("[,]"), "")).trim()
        }
        return null
    }

    /**
     * Letterheads are set in capitals, so the whole line arrives shouting. Short all-caps tokens
     * are kept as-is because they are usually acronyms (ID, GHS, NHS) — except for the handful of
     * short words that only *look* like acronyms in a letterhead. "ST ELIZABETH" is a saint, not
     * an initialism, and the SDS example expects "St Elizabeth Hospital".
     */
    private fun titleCase(raw: String): String = raw
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
        .joinToString(" ") { word ->
            when {
                word.any { it.isDigit() } -> word
                word.length <= 3 &&
                    word.all { it.isUpperCase() } &&
                    word.uppercase(Locale.US) !in NOT_ACRONYMS -> word
                else -> word.lowercase(Locale.US).replaceFirstChar { it.titlecase(Locale.US) }
            }
        }
        .trim()

    data class Suggestion(
        val title: String,
        val type: DocumentType,
        val confidence: Float,
    )

    private companion object {
        const val MAX_LINES_CONSIDERED = 40

        /** How many keyword hits a specific type needs before it beats the generic default. */
        const val MIN_SIGNAL_HITS = 2

        val TYPE_SIGNALS: List<Pair<DocumentType, List<String>>> = listOf(
            DocumentType.PASSPORT to listOf("passport", "nationality", "date of issue", "authority"),
            DocumentType.ID_CARD to listOf("identity card", "id no", "id number", "date of birth", "sex", "holder"),
            DocumentType.INVOICE to listOf("invoice", "bill to", "amount due", "vat", "tax", "invoice no"),
            DocumentType.RECEIPT to listOf("receipt", "total", "change", "cashier", "subtotal", "thank you for"),
            DocumentType.BUSINESS_CARD to listOf("mobile", "email", "www.", "director", "manager"),
            DocumentType.CERTIFICATE to listOf("certificate", "is hereby", "awarded", "certify", "diploma"),
        )

        /**
         * Short words that appear in capitals on a letterhead but are ordinary words, not
         * initialisms, so they should be title-cased like everything else.
         */
        val NOT_ACRONYMS = setOf(
            "ST", "DR", "MR", "MS", "MRS", "CO", "LTD", "PLC", "INC", "AND", "THE", "OF", "FOR",
        )

        /** Lines that are formatted like a letterhead but are not the organisation's name. */
        val NON_ORGANISATION = listOf("invoice", "receipt", "page", "copy", "original", "confidential")

        val DESCRIPTOR_WORDS = listOf(
            "report", "invoice", "receipt", "statement", "certificate", "letter", "summary",
            "results", "prescription", "contract", "agreement", "application", "form", "notice",
        )

        val DATE_PATTERNS: List<Regex> = listOf(
            // 18 August 2026 / 18 Aug 2026
            Regex(
                "\\b\\d{1,2}\\s+(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\.?\\s+\\d{4}\\b",
                RegexOption.IGNORE_CASE,
            ),
            // August 18, 2026
            Regex(
                "\\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\.?\\s+\\d{1,2},?\\s+\\d{4}\\b",
                RegexOption.IGNORE_CASE,
            ),
            // 18/08/2026 or 18-08-2026
            Regex("\\b\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4}\\b"),
        )
    }
}
