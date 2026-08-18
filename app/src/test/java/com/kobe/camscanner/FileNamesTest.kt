package com.kobe.camscanner

import com.google.common.truth.Truth.assertThat
import com.kobe.camscanner.core.common.FileNames
import com.kobe.camscanner.core.common.Formatting
import org.junit.Test

class FileNamesTest {

    @Test
    fun `strips characters a filesystem cannot store`() {
        val name = FileNames.sanitise("""Report: 12/08 <draft> "final"?""")

        listOf("/", ":", "<", ">", "\"", "?", "|", "*", "\\").forEach {
            assertThat(name).doesNotContain(it)
        }
        assertThat(name).isNotEmpty()
    }

    @Test
    fun `collapses runs of whitespace`() {
        assertThat(FileNames.sanitise("Hospital     Report")).isEqualTo("Hospital Report")
    }

    @Test
    fun `falls back when the name reduces to nothing`() {
        assertThat(FileNames.sanitise("///", fallback = "Scan")).isEqualTo("Scan")
    }

    @Test
    fun `renames FAT reserved words so an SD card can hold them`() {
        assertThat(FileNames.sanitise("CON")).isEqualTo("CON file")
        assertThat(FileNames.sanitise("nul")).isEqualTo("nul file")
    }

    @Test
    fun `truncates names too long for a filesystem entry`() {
        val name = FileNames.sanitise("x".repeat(400))
        assertThat(name.length).isAtMost(120)
    }

    @Test
    fun `leaves a unique name untouched`() {
        assertThat(FileNames.uniquify("Invoice", setOf("Receipt"))).isEqualTo("Invoice")
    }

    @Test
    fun `numbers a name that already exists`() {
        assertThat(FileNames.uniquify("Invoice", setOf("Invoice"))).isEqualTo("Invoice (2)")
        assertThat(FileNames.uniquify("Invoice", setOf("Invoice", "Invoice (2)")))
            .isEqualTo("Invoice (3)")
    }

    @Test
    fun `adds an extension only when it is missing`() {
        assertThat(FileNames.withExtension("Report", "pdf")).isEqualTo("Report.pdf")
        assertThat(FileNames.withExtension("Report.pdf", "pdf")).isEqualTo("Report.pdf")
        assertThat(FileNames.withExtension("Report.PDF", ".pdf")).isEqualTo("Report.PDF")
    }

    @Test
    fun `strips an extension without eating a dotted title`() {
        assertThat(FileNames.stripExtension("Report.pdf")).isEqualTo("Report")
        // "2026" is far too long to be an extension, so the dot is part of the name.
        assertThat(FileNames.stripExtension("Meeting 12.08.2026 minutes"))
            .isEqualTo("Meeting 12.08.2026 minutes")
    }
}

class FormattingTest {

    @Test
    fun `formats byte sizes at each scale`() {
        assertThat(Formatting.fileSize(0)).isEqualTo("0 B")
        assertThat(Formatting.fileSize(512)).isEqualTo("512 B")
        assertThat(Formatting.fileSize(2048)).isEqualTo("2 KB")
        assertThat(Formatting.fileSize(5L * 1024 * 1024)).isEqualTo("5.0 MB")
    }

    @Test
    fun `pluralises the page count`() {
        assertThat(Formatting.pageCount(1)).isEqualTo("1 page")
        assertThat(Formatting.pageCount(4)).isEqualTo("4 pages")
        assertThat(Formatting.pageCount(0)).isEqualTo("0 pages")
    }

    @Test
    fun `today and yesterday read as words`() {
        val now = System.currentTimeMillis()
        assertThat(Formatting.relativeDate(now, now)).startsWith("Today")

        val yesterday = now - 24L * 60 * 60 * 1000
        assertThat(Formatting.relativeDate(yesterday, now)).startsWith("Yesterday")
    }

    @Test
    fun `older dates read as a date`() {
        val now = System.currentTimeMillis()
        val lastMonth = now - 40L * 24 * 60 * 60 * 1000
        val text = Formatting.relativeDate(lastMonth, now)

        assertThat(text).doesNotContain("Today")
        assertThat(text).doesNotContain("Yesterday")
    }
}
