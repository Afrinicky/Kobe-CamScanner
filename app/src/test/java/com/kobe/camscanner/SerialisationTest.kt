package com.kobe.camscanner

import com.google.common.truth.Truth.assertThat
import com.kobe.camscanner.data.local.Serialisation
import com.kobe.camscanner.data.repository.FtsQuery
import com.kobe.camscanner.domain.model.Adjustments
import com.kobe.camscanner.domain.model.PdfCompression
import com.kobe.camscanner.domain.model.PdfOptions
import com.kobe.camscanner.domain.model.PdfQuality
import com.kobe.camscanner.domain.model.PointN
import com.kobe.camscanner.domain.model.Quad
import org.junit.Test

/**
 * The two hand-written encodings that the library rows depend on. A silent decode failure here
 * would lose a page's crop or its adjustments, so both directions are checked, including on the
 * malformed input a partially-written row could produce.
 */
class SerialisationTest {

    @Test
    fun `adjustments survive a round trip`() {
        val original = Adjustments(0.2f, -0.4f, 0.6f, 0f, 0.1f)
        val decoded = Serialisation.adjustmentsFrom(Serialisation.adjustmentsTo(original))

        assertThat(decoded).isEqualTo(original)
    }

    @Test
    fun `malformed adjustments decode to neutral rather than throwing`() {
        assertThat(Serialisation.adjustmentsFrom("1,2")).isEqualTo(Adjustments.NEUTRAL)
        assertThat(Serialisation.adjustmentsFrom("nonsense")).isEqualTo(Adjustments.NEUTRAL)
        assertThat(Serialisation.adjustmentsFrom(null)).isEqualTo(Adjustments.NEUTRAL)
    }

    @Test
    fun `a quad survives a round trip`() {
        val original = Quad(
            PointN(0.05f, 0.06f),
            PointN(0.94f, 0.08f),
            PointN(0.92f, 0.95f),
            PointN(0.07f, 0.93f),
        )
        val decoded = Serialisation.quadFrom(Serialisation.quadTo(original))

        assertThat(decoded).isEqualTo(original)
    }

    @Test
    fun `a null quad encodes and decodes as no crop`() {
        assertThat(Serialisation.quadTo(null)).isNull()
        assertThat(Serialisation.quadFrom(null)).isNull()
        assertThat(Serialisation.quadFrom("0.1,0.2,0.3")).isNull()
    }
}

/**
 * FTS4's grammar rejects raw user input — an apostrophe, a bare wildcard or a stray hyphen is a
 * syntax error, not a no-op — so every query is rebuilt from scratch.
 */
class FtsQueryTest {

    @Test
    fun `quotes every token and prefixes only the last`() {
        assertThat(FtsQuery.build("hospital report")).isEqualTo("\"hospital\" \"report\"*")
    }

    @Test
    fun `strips punctuation that FTS would treat as an operator`() {
        val query = FtsQuery.build("""invoice* -2026 "quoted" O'Brien""")

        assertThat(query).isNotNull()
        assertThat(query).doesNotContain("*\"")
        assertThat(query).doesNotContain("-")
        assertThat(query!!).contains("\"invoice\"")
        assertThat(query).contains("\"2026\"")
    }

    @Test
    fun `input with no usable tokens produces no query`() {
        assertThat(FtsQuery.build("***")).isNull()
        assertThat(FtsQuery.build("   ")).isNull()
    }

    @Test
    fun `keeps digits, which is how invoice numbers get found`() {
        assertThat(FtsQuery.build("00124")).isEqualTo("\"00124\"*")
    }
}

class PdfOptionsTest {

    @Test
    fun `compression chooses the quality when none is given`() {
        assertThat(PdfOptions(compression = PdfCompression.SMALL).effectiveQuality)
            .isEqualTo(PdfQuality.LOW)
        assertThat(PdfOptions(compression = PdfCompression.HIGH_QUALITY).effectiveQuality)
            .isEqualTo(PdfQuality.HIGH)
    }

    @Test
    fun `an explicit quality overrides the compression preset`() {
        val options = PdfOptions(
            compression = PdfCompression.SMALL,
            quality = PdfQuality.MAXIMUM,
        )
        assertThat(options.effectiveQuality).isEqualTo(PdfQuality.MAXIMUM)
    }

    @Test
    fun `higher quality never means fewer pixels or worse encoding`() {
        val ordered = listOf(
            PdfQuality.LOW,
            PdfQuality.STANDARD,
            PdfQuality.HIGH,
            PdfQuality.MAXIMUM,
        )
        ordered.zipWithNext { lower, higher ->
            assertThat(higher.jpegQuality).isGreaterThan(lower.jpegQuality)
            assertThat(higher.maxLongEdgePx).isGreaterThan(lower.maxLongEdgePx)
        }
    }
}
