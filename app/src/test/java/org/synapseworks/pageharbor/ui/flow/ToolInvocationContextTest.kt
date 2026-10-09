package org.synapseworks.pageharbor.ui.flow

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolInvocationContextTest {
    @Test
    fun oneDocumentToolsRequireExactlyOneExplicitDocument() {
        DocumentToolOperation.entries
            .filter { it.inputCardinality == ToolInputCardinality.EXACTLY_ONE }
            .forEach { operation ->
                assertFalse(context(operation, emptyList()).hasValidCardinality)
                assertTrue(context(operation, listOf("document-a")).hasValidCardinality)
                assertFalse(
                    context(operation, listOf("document-a", "document-b")).hasValidCardinality,
                )
            }
    }

    @Test
    fun mergeRequiresAtLeastTwoExplicitDocuments() {
        assertFalse(context(DocumentToolOperation.MERGE, emptyList()).hasValidCardinality)
        assertFalse(context(DocumentToolOperation.MERGE, listOf("document-a")).hasValidCardinality)
        assertTrue(
            context(
                DocumentToolOperation.MERGE,
                listOf("document-a", "document-b"),
            ).hasValidCardinality,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateDocumentIdsAreRejected() {
        context(
            DocumentToolOperation.MERGE,
            listOf("document-a", "document-a"),
        )
    }

    @Test
    fun readyContextRetainsItsExplicitInvocationSource() {
        val context = ToolInvocationContext(
            source = ToolInvocationSource.DOCUMENT_SELECTION,
            operation = DocumentToolOperation.MERGE,
            documentIds = listOf("document-a", "document-b"),
        )

        assertSame(context, context.requireReady())
        assertTrue(context.source == ToolInvocationSource.DOCUMENT_SELECTION)
    }

    @Test
    fun operationSelectionIsIsolatedWhenASecondToolRouteIsConstructed() {
        val merge = context(DocumentToolOperation.MERGE, listOf("document-a", "document-b"))
        val extract = context(DocumentToolOperation.EXTRACT_TEXT, emptyList())

        assertEquals(listOf("document-a", "document-b"), merge.documentIds)
        assertTrue(extract.documentIds.isEmpty())
        assertFalse(extract.hasValidCardinality)
    }

    @Test
    fun explicitContextSurvivesSavedStateCompatibleSerialization() {
        val original = ToolInvocationContext(
            source = ToolInvocationSource.TOOLS_SCREEN,
            operation = DocumentToolOperation.SPLIT_EXTRACT,
            documentIds = listOf("document-a"),
            pageIds = listOf("page-1"),
        )
        val bytes = ByteArrayOutputStream().use { output ->
            ObjectOutputStream(output).use { it.writeObject(original) }
            output.toByteArray()
        }
        val restored = ObjectInputStream(ByteArrayInputStream(bytes)).use {
            it.readObject() as ToolInvocationContext
        }

        assertEquals(original, restored)
        assertTrue(restored.hasValidCardinality)
    }

    private fun context(
        operation: DocumentToolOperation,
        documentIds: List<String>,
    ) = ToolInvocationContext(
        source = ToolInvocationSource.TOOLS_SCREEN,
        operation = operation,
        documentIds = documentIds,
    )
}
