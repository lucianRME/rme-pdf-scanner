package org.synapseworks.pageharbor.migration

/** Preserve the existing bulk-preview admission guard: migration materializes a planning record
 * for every PDF page. Ordinary active-document scanning/import no longer shares this fixed cap.
 * Raising this separate preview guard requires a bounded/lazy migration planner first.
 */
internal const val MAX_MIGRATION_PLANNED_PDF_PAGES = 10_000
