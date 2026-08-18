package com.kobe.camscanner

import com.google.common.truth.Truth.assertThat
import com.kobe.camscanner.ai.SmartNaming
import com.kobe.camscanner.domain.model.DocumentType
import org.junit.Test

/**
 * Smart naming and classification, checked against the worked example in SDS 40 and against the
 * failure modes that matter more than accuracy: it must never produce an unusable filename, and it
 * must fall back cleanly when a page has no readable text at all.
 */
class SmartNamingTest {

    private val naming = SmartNaming()

    @Test
    fun `builds a name from a letterhead, a descriptor and a date`() {
        val ocr = """
            ST ELIZABETH CATHOLIC HOSPITAL
            MICROBIOLOGY DEPARTMENT
            LABORATORY REPORT
            18 AUGUST 2026
        """.trimIndent()

        val suggestion = naming.suggest(ocr)

        assertThat(suggestion.title).contains("St Elizabeth Catholic Hospital")
        assertThat(suggestion.title).contains("18 August 2026")
        assertThat(suggestion.confidence).isAtLeast(0.6f)
    }

    @Test
    fun `falls back to a timestamped name when there is no text`() {
        val suggestion = naming.suggest("")

        assertThat(suggestion.title).startsWith("Scan ")
        assertThat(suggestion.type).isEqualTo(DocumentType.DOCUMENT)
        assertThat(suggestion.confidence).isEqualTo(0f)
    }

    @Test
    fun `classifies an invoice`() {
        val ocr = """
            ABC LIMITED
            TAX INVOICE
            Invoice No: 00124
            Bill to: Kwame Mensah
            Amount due: GHS 1,250
        """.trimIndent()

        assertThat(naming.classify(ocr)).isEqualTo(DocumentType.INVOICE)
    }

    @Test
    fun `classifies a receipt`() {
        val ocr = """
            CITY SUPERMARKET
            Subtotal 42.00
            Total 45.60
            Cashier: 04
            Thank you for shopping
        """.trimIndent()

        assertThat(naming.classify(ocr)).isEqualTo(DocumentType.RECEIPT)
    }

    @Test
    fun `classifies ordinary prose as a plain document`() {
        val ocr = "Dear Ama, thank you for your letter of last week. I will visit on Friday."

        assertThat(naming.classify(ocr)).isEqualTo(DocumentType.DOCUMENT)
    }

    @Test
    fun `a single keyword is not enough to classify`() {
        // "total" alone appears in all sorts of documents; one hit must not make it a receipt.
        assertThat(naming.classify("The total length of the pipeline is 40 km."))
            .isEqualTo(DocumentType.DOCUMENT)
    }

    @Test
    fun `suggested names never contain characters a filesystem rejects`() {
        val ocr = """
            ACME / GLOBAL: HOLDINGS
            INVOICE No: 12*34?
            18/08/2026
        """.trimIndent()

        val title = naming.suggest(ocr).title

        assertThat(title).doesNotContain("/")
        assertThat(title).doesNotContain(":")
        assertThat(title).doesNotContain("*")
        assertThat(title).doesNotContain("?")
        assertThat(title).isNotEmpty()
    }
}
