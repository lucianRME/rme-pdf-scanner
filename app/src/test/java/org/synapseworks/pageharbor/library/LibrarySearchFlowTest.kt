package org.synapseworks.pageharbor.library

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySearchFlowTest {
    @Test
    fun latestQueryCancelsOlderWorkAndCannotPublishStaleResults() = runBlocking {
        val query = MutableStateFlow("")
        val limit = MutableStateFlow(30)
        val completedQueries = mutableListOf<String>()
        val states = mutableListOf<LibrarySearchState>()
        val collection = launch {
            librarySearchStateFlow(query, limit, debounceMillis = 10L) { value, _ ->
                flow {
                    if (value == "invoice") delay(150L)
                    completedQueries += value
                    emit(listOf(hit(value)))
                }
            }.collect(states::add)
        }

        query.value = "invoice"
        delay(40L)
        query.value = "contract"
        delay(80L)
        collection.cancelAndJoin()

        assertEquals(listOf("contract"), completedQueries)
        assertEquals(
            "contract",
            states.filterIsInstance<LibrarySearchState.Results>().single().query,
        )
    }

    @Test
    fun rapidTypingRunsOnlyTheLatestUsefulQuery() = runBlocking {
        val query = MutableStateFlow("")
        val limit = MutableStateFlow(LIBRARY_SEARCH_INITIAL_LIMIT)
        val completedQueries = mutableListOf<String>()
        val collection = launch {
            librarySearchStateFlow(query, limit, debounceMillis = 20L) { value, _ ->
                flow {
                    completedQueries += value
                    emit(listOf(hit(value)))
                }
            }.collect()
        }

        listOf("i", "in", "inv", "invo", "invoice").forEach { value ->
            query.value = value
            delay(5L)
        }
        delay(50L)
        collection.cancelAndJoin()

        assertEquals(listOf("invoice"), completedQueries)
    }

    @Test
    fun shortQueriesDoNotReachTheRepository() = runBlocking {
        val query = MutableStateFlow("i")
        val limit = MutableStateFlow(30)
        var searchCount = 0
        val states = mutableListOf<LibrarySearchState>()
        val collection = launch {
            librarySearchStateFlow(query, limit, debounceMillis = 1L) { value, _ ->
                flow {
                    searchCount += 1
                    emit(listOf(hit(value)))
                }
            }.collect(states::add)
        }

        delay(30L)
        collection.cancelAndJoin()

        assertEquals(0, searchCount)
        assertTrue(states.single() is LibrarySearchState.TooShort)
    }

    @Test
    fun activeQueryPublishesEffectiveOcrIndexChangesWithoutRetyping() = runBlocking {
        val query = MutableStateFlow("invoice")
        val limit = MutableStateFlow(30)
        val indexedHits = MutableStateFlow(listOf(hit("raw")))
        val states = mutableListOf<LibrarySearchState>()
        val collection = launch {
            librarySearchStateFlow(query, limit, debounceMillis = 1L) { _, _ -> indexedHits }
                .collect(states::add)
        }

        withTimeout(1_000L) {
            while (states.none { it is LibrarySearchState.Results }) delay(1L)
        }
        indexedHits.value = listOf(hit("corrected"))
        withTimeout(1_000L) {
            while (states.filterIsInstance<LibrarySearchState.Results>().size < 2) delay(1L)
        }
        collection.cancelAndJoin()

        assertEquals(
            listOf("raw", "corrected"),
            states.filterIsInstance<LibrarySearchState.Results>()
                .map { it.hits.single().documentId },
        )
    }

    @Test
    fun resultsExpandByThirtyAndStopAtTheTwoHundredItemHardBound() = runBlocking {
        val query = MutableStateFlow("common")
        val limit = MutableStateFlow(LIBRARY_SEARCH_INITIAL_LIMIT)
        val states = mutableListOf<LibrarySearchState.Results>()
        val collection = launch {
            librarySearchStateFlow(query, limit, debounceMillis = 1L) { _, requestedLimit ->
                flow { emit(List(requestedLimit) { index -> hit("result-$index") }) }
            }.collect { state ->
                if (state is LibrarySearchState.Results) states += state
            }
        }

        delay(20L)
        limit.value = 60
        delay(20L)
        limit.value = LIBRARY_SEARCH_MAX_LIMIT
        delay(20L)
        collection.cancelAndJoin()

        assertEquals(listOf(30, 60, 200), states.map { it.hits.size })
        assertEquals(listOf(true, true, false), states.map { it.canLoadMore })
        assertTrue(states.all(LibrarySearchState.Results::hasMoreResults))
    }

    private fun hit(query: String) = LibrarySearchHit(
        documentId = query,
        documentTitle = query,
        pageId = null,
        currentPagePosition = null,
        matchType = LibrarySearchMatch.TITLE,
        snippet = null,
    )
}
