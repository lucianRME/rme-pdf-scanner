package org.synapseworks.pageharbor.ui.flow

import java.io.Serializable

/** The user-visible place that supplied the document IDs for a tool invocation. */
enum class ToolInvocationSource : Serializable {
    DOCUMENT_DETAIL,
    DOCUMENT_SELECTION,
    TOOLS_SCREEN,
    SCAN_ENTRY,
    IMPORT_ENTRY,
}

enum class ToolInputCardinality {
    EXACTLY_ONE,
    AT_LEAST_TWO,
}

/** Operations that consume saved RME documents rather than acquiring a new source. */
enum class DocumentToolOperation(
    val inputCardinality: ToolInputCardinality,
) : Serializable {
    EXTRACT_TEXT(ToolInputCardinality.EXACTLY_ONE),
    SPLIT_EXTRACT(ToolInputCardinality.EXACTLY_ONE),
    MERGE(ToolInputCardinality.AT_LEAST_TWO),
    FIT_UPLOAD_LIMIT(ToolInputCardinality.EXACTLY_ONE),
    SHARE_SAFE_COPY(ToolInputCardinality.EXACTLY_ONE),
}

/**
 * Explicit, operation-scoped document ownership. It is intentionally not a global "current
 * document": callers create it for one action and discard it when that action starts or is
 * cancelled.
 */
data class ToolInvocationContext(
    val source: ToolInvocationSource,
    val operation: DocumentToolOperation,
    val documentIds: List<String>,
    val pageIds: List<String> = emptyList(),
) : Serializable {
    init {
        require(documentIds.all(String::isNotBlank))
        require(documentIds.distinct().size == documentIds.size)
        require(pageIds.all(String::isNotBlank))
        require(pageIds.distinct().size == pageIds.size)
    }

    val hasValidCardinality: Boolean
        get() = when (operation.inputCardinality) {
            ToolInputCardinality.EXACTLY_ONE -> documentIds.size == 1
            ToolInputCardinality.AT_LEAST_TWO -> documentIds.size >= 2
        }

    fun requireReady(): ToolInvocationContext = apply {
        require(hasValidCardinality) {
            "${operation.name} received ${documentIds.size} document(s)"
        }
    }
}
