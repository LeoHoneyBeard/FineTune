package com.finetune.desktop.validation

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DatasetValidatorTest {
    @Test
    fun `valid dataset passes`() {
        val file = createDatasetFile(
            """
            {"messages":[{"role":"system","content":"rules"},{"role":"user","content":"hello"},{"role":"assistant","content":"hi"}]}
            {"messages":[{"role":"system","content":"rules"},{"role":"user","content":"question"},{"role":"assistant","content":"answer"}]}
            """.trimIndent()
        )

        val result = DatasetValidator.validate(file)

        assertTrue(result.isValid)
        assertEquals(2, result.totalLines)
        assertEquals(2, result.validLines)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `invalid json is reported`() {
        val file = createDatasetFile("""{"messages":[}""")

        val result = DatasetValidator.validate(file)

        assertFalse(result.isValid)
        assertEquals(1, result.totalLines)
        assertEquals(0, result.validLines)
        assertTrue(result.errors.single().contains("invalid JSON"))
    }

    @Test
    fun `missing roles and blank content are reported`() {
        val file = createDatasetFile(
            """
            {"messages":[{"role":"system","content":"rules"},{"role":"assistant","content":"reply"},{"role":"user","content":"   "}]}
            """.trimIndent()
        )

        val result = DatasetValidator.validate(file)

        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("must have role \"user\"") })
        assertTrue(result.errors.any { it.contains("must have role \"assistant\"") })
        assertTrue(result.errors.any { it.contains("non-empty \"content\"") })
    }

    private fun createDatasetFile(content: String): File {
        return kotlin.io.path.createTempFile(suffix = ".jsonl").toFile().apply {
            writeText(content)
            deleteOnExit()
        }
    }
}
