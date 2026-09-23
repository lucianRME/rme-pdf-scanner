package org.synapseworks.pageharbor.document.session

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentAcquisitionCoordinatorTest {
    @Test
    fun scanAndFutureSourcesEnterOneOrderedSessionWithStableIdentities() {
        val coordinator = DocumentAcquisitionCoordinator()
        val scanned = coordinator.acquire(
            mode = DocumentAcquisitionMode.REPLACE,
            currentSession = DocumentSession(),
            input = input(
                page("scan-1", DocumentSourceCategory.SCAN),
                page("scan-2", DocumentSourceCategory.SCAN),
            ),
        ).successSession()
        val firstIds = scanned.pages.map(DocumentPage::id)

        val combined = coordinator.acquire(
            mode = DocumentAcquisitionMode.APPEND,
            currentSession = scanned,
            input = input(
                page("selected", DocumentSourceCategory.SELECTED_IMAGE, "image/png"),
                page("shared", DocumentSourceCategory.INBOUND_SHARE, "image/webp"),
                page("rendered", DocumentSourceCategory.RENDERED_PDF_PAGE),
            ),
        ).successSession()

        assertEquals(firstIds, combined.pages.take(2).map(DocumentPage::id))
        assertEquals(5, combined.pages.map(DocumentPage::id).distinct().size)
        assertEquals(
            listOf(
                DocumentSourceCategory.SCAN,
                DocumentSourceCategory.SCAN,
                DocumentSourceCategory.SELECTED_IMAGE,
                DocumentSourceCategory.INBOUND_SHARE,
                DocumentSourceCategory.RENDERED_PDF_PAGE,
            ),
            combined.pages.map(DocumentPage::sourceCategory),
        )
        assertEquals(
            listOf("image/jpeg", "image/jpeg", "image/png", "image/webp", "image/jpeg"),
            combined.pages.map(DocumentPage::contentType),
        )
    }

    @Test
    fun appendingImportedPdfsRetainsEveryOriginalWhileInvalidatingDirectExport() {
        val cleaner = RecordingCleaner()
        val coordinator = DocumentAcquisitionCoordinator(resourceCleaner = cleaner)
        val firstPdf = owned("first-original-pdf")
        val secondPdf = owned("second-original-pdf")

        val first = coordinator.acquire(
            mode = DocumentAcquisitionMode.REPLACE,
            currentSession = DocumentSession(),
            input = DocumentAcquisitionInput(
                pages = listOf(
                    AcquiredDocumentPage(
                        owned("first-page"),
                        DocumentSourceCategory.RENDERED_PDF_PAGE,
                        "image/jpeg",
                    ),
                ),
                directPdfSource = firstPdf,
            ),
        ).successSession()
        assertTrue(first.canUseDirectPdf)

        val combined = coordinator.acquire(
            mode = DocumentAcquisitionMode.APPEND,
            currentSession = first,
            input = DocumentAcquisitionInput(
                pages = listOf(
                    AcquiredDocumentPage(
                        owned("second-page"),
                        DocumentSourceCategory.RENDERED_PDF_PAGE,
                        "image/jpeg",
                    ),
                ),
                directPdfSource = secondPdf,
            ),
        ).successSession()

        assertEquals(
            listOf("first-original-pdf", "second-original-pdf"),
            combined.originalPdfSources.map(DocumentResource::reference),
        )
        assertFalse(combined.canUseDirectPdf)
        assertTrue(cleaner.deletedReferences.isEmpty())

        coordinator.release(combined)
        assertTrue("first-original-pdf" in cleaner.deletedReferences)
        assertTrue("second-original-pdf" in cleaner.deletedReferences)
    }

    @Test
    fun invalidAndUnsupportedInputsReturnTypedErrors() {
        val coordinator = DocumentAcquisitionCoordinator()

        assertFailure(
            coordinator,
            input(page(reference = "", category = DocumentSourceCategory.SCAN)),
            DocumentAcquisitionError.INVALID_REFERENCE,
        )
        assertFailure(
            coordinator,
            input(page("text", DocumentSourceCategory.SELECTED_IMAGE, "text/plain")),
            DocumentAcquisitionError.UNSUPPORTED_CONTENT_TYPE,
        )
        assertFailure(
            coordinator,
            DocumentAcquisitionInput(emptyList()),
            DocumentAcquisitionError.EMPTY_INPUT,
        )
    }

    @Test
    fun configuredPageLimitRejectsTheWholeInputAndPreservesCurrentSession() {
        val coordinator = DocumentAcquisitionCoordinator(DocumentInputLimits(maxPages = 2))
        val current = coordinator.acquire(
            DocumentAcquisitionMode.REPLACE,
            DocumentSession(),
            input(page("one", DocumentSourceCategory.SCAN)),
        ).successSession()

        val result = coordinator.acquire(
            DocumentAcquisitionMode.APPEND,
            current,
            input(
                page("two", DocumentSourceCategory.SELECTED_IMAGE),
                page("three", DocumentSourceCategory.INBOUND_SHARE),
            ),
        )

        assertEquals(
            DocumentAcquisitionResult.Failure(DocumentAcquisitionError.PAGE_LIMIT_EXCEEDED),
            result,
        )
        assertEquals(listOf("one"), current.pages.map { page -> page.source.reference })
    }

    @Test
    fun failedAppendDoesNotDeleteAnOwnedFileRetainedByTheCurrentSession() {
        val cleaner = RecordingCleaner()
        val coordinator = DocumentAcquisitionCoordinator(
            limits = DocumentInputLimits(maxPages = 1),
            resourceCleaner = cleaner,
        )
        val current = coordinator.acquire(
            DocumentAcquisitionMode.REPLACE,
            DocumentSession(),
            DocumentAcquisitionInput(
                pages = listOf(
                    AcquiredDocumentPage(
                        owned("active"),
                        DocumentSourceCategory.RENDERED_PDF_PAGE,
                        "image/jpeg",
                    ),
                ),
            ),
        ).successSession()

        val result = coordinator.acquire(
            DocumentAcquisitionMode.APPEND,
            current,
            DocumentAcquisitionInput(
                pages = listOf(
                    AcquiredDocumentPage(
                        owned("active"),
                        DocumentSourceCategory.RENDERED_PDF_PAGE,
                        "image/jpeg",
                    ),
                    AcquiredDocumentPage(
                        owned("new"),
                        DocumentSourceCategory.RENDERED_PDF_PAGE,
                        "image/jpeg",
                    ),
                ),
            ),
        )

        assertEquals(
            DocumentAcquisitionResult.Failure(DocumentAcquisitionError.PAGE_LIMIT_EXCEEDED),
            result,
        )
        assertEquals(listOf("new"), cleaner.deletedReferences)

        coordinator.release(current)

        assertEquals(listOf("new", "active"), cleaner.deletedReferences)
    }

    @Test
    fun successDeletesStagingButTransfersOwnedPagesUntilSessionRelease() {
        val cleaner = RecordingCleaner()
        val coordinator = DocumentAcquisitionCoordinator(resourceCleaner = cleaner)
        val activePage = owned("active")
        val staging = owned("staging")

        val session = coordinator.acquire(
            DocumentAcquisitionMode.REPLACE,
            DocumentSession(),
            DocumentAcquisitionInput(
                pages = listOf(
                    AcquiredDocumentPage(
                        activePage,
                        DocumentSourceCategory.RENDERED_PDF_PAGE,
                        "image/jpeg",
                    ),
                ),
                transientResources = listOf(staging),
            ),
        ).successSession()

        assertEquals(listOf("staging"), cleaner.deletedReferences)

        coordinator.release(session)

        assertEquals(listOf("staging", "active"), cleaner.deletedReferences)
    }

    @Test
    fun replacementDeletesOldOwnedResourcesAndPreservesExternalInputs() {
        val cleaner = RecordingCleaner()
        val coordinator = DocumentAcquisitionCoordinator(resourceCleaner = cleaner)
        val oldSession = coordinator.acquire(
            DocumentAcquisitionMode.REPLACE,
            DocumentSession(),
            DocumentAcquisitionInput(
                pages = listOf(
                    AcquiredDocumentPage(
                        owned("old-owned"),
                        DocumentSourceCategory.RENDERED_PDF_PAGE,
                        "image/jpeg",
                    ),
                    page("external-content-uri", DocumentSourceCategory.INBOUND_SHARE),
                ),
            ),
        ).successSession()

        coordinator.acquire(
            DocumentAcquisitionMode.REPLACE,
            oldSession,
            input(page("replacement", DocumentSourceCategory.SCAN)),
        ).successSession()

        assertEquals(listOf("old-owned"), cleaner.deletedReferences)
        assertFalse(cleaner.deletedReferences.contains("external-content-uri"))
    }

    @Test
    fun cancellationFailureAndInterruptionCleanOnlyIncomingOwnedResources() {
        val cleaner = RecordingCleaner()
        val coordinator = DocumentAcquisitionCoordinator(resourceCleaner = cleaner)

        val cancelToken = coordinator.begin(DocumentAcquisitionMode.REPLACE)!!
        assertEquals(
            DocumentAcquisitionResult.Cancelled,
            coordinator.cancel(
                cancelToken,
                DocumentSession(),
                inputWithOwnedAndExternal("cancelled-owned"),
            ),
        )

        val failureToken = coordinator.begin(DocumentAcquisitionMode.REPLACE)!!
        assertEquals(
            DocumentAcquisitionResult.Failure(DocumentAcquisitionError.SOURCE_UNAVAILABLE),
            coordinator.fail(
                failureToken,
                DocumentSession(),
                inputWithOwnedAndExternal("failed-owned"),
            ),
        )

        val interruptedToken = coordinator.begin(DocumentAcquisitionMode.REPLACE)!!
        assertEquals(
            DocumentAcquisitionResult.Failure(DocumentAcquisitionError.INTERRUPTED),
            coordinator.interrupt(
                interruptedToken,
                DocumentSession(),
                inputWithOwnedAndExternal("interrupted-owned"),
            ),
        )

        assertEquals(
            listOf("cancelled-owned", "failed-owned", "interrupted-owned"),
            cleaner.deletedReferences,
        )
        assertTrue(cleaner.deletedReferences.none { reference -> reference.endsWith("-external") })
    }

    @Test
    fun invalidOwnedInputIsCleanedOnFailureWithoutTouchingExternalResources() {
        val cleaner = RecordingCleaner()
        val coordinator = DocumentAcquisitionCoordinator(resourceCleaner = cleaner)
        val result = coordinator.acquire(
            DocumentAcquisitionMode.REPLACE,
            DocumentSession(),
            DocumentAcquisitionInput(
                pages = listOf(
                    AcquiredDocumentPage(
                        owned("unsupported-owned"),
                        DocumentSourceCategory.SELECTED_IMAGE,
                        "application/octet-stream",
                    ),
                    page("preserved-external", DocumentSourceCategory.INBOUND_SHARE),
                ),
            ),
        )

        assertEquals(
            DocumentAcquisitionResult.Failure(DocumentAcquisitionError.UNSUPPORTED_CONTENT_TYPE),
            result,
        )
        assertEquals(listOf("unsupported-owned"), cleaner.deletedReferences)
    }

    @Test
    fun invalidOwnedReferencesAreStillCleanupEligible() {
        val cleaner = RecordingCleaner()
        val coordinator = DocumentAcquisitionCoordinator(resourceCleaner = cleaner)
        val blank = owned(reference = "", pathName = "blank-reference")
        val missing = owned(reference = null, pathName = "missing-reference")

        assertFailure(
            coordinator,
            DocumentAcquisitionInput(
                pages = listOf(
                    AcquiredDocumentPage(blank, DocumentSourceCategory.SELECTED_IMAGE, "image/jpeg"),
                ),
                transientResources = listOf(missing),
            ),
            DocumentAcquisitionError.INVALID_REFERENCE,
        )

        assertEquals(
            listOf("/private/rme/blank-reference", "/private/rme/missing-reference"),
            cleaner.deletedPaths,
        )
    }

    @Test
    fun pendingResourcesAreCleanedWithoutResupplyingInputOnEveryTerminalPath() {
        val cleaner = RecordingCleaner()
        val coordinator = DocumentAcquisitionCoordinator(resourceCleaner = cleaner)

        val cancelled = coordinator.begin(DocumentAcquisitionMode.REPLACE)!!
        assertEquals(
            PendingResourceRegistrationResult.Registered,
            coordinator.registerPendingResources(cancelled, DocumentSession(), listOf(owned("cancelled"))),
        )
        assertEquals(
            DocumentAcquisitionResult.Cancelled,
            coordinator.cancel(cancelled, DocumentSession()),
        )

        val failed = coordinator.begin(DocumentAcquisitionMode.REPLACE)!!
        assertEquals(
            PendingResourceRegistrationResult.Registered,
            coordinator.registerPendingResources(failed, DocumentSession(), listOf(owned("failed"))),
        )
        assertEquals(
            DocumentAcquisitionResult.Failure(DocumentAcquisitionError.SOURCE_UNAVAILABLE),
            coordinator.fail(failed, DocumentSession()),
        )

        val interrupted = coordinator.begin(DocumentAcquisitionMode.REPLACE)!!
        assertEquals(
            PendingResourceRegistrationResult.Registered,
            coordinator.registerPendingResources(
                interrupted,
                DocumentSession(),
                listOf(owned("interrupted")),
            ),
        )
        assertEquals(
            DocumentAcquisitionResult.Failure(DocumentAcquisitionError.INTERRUPTED),
            coordinator.interrupt(interrupted, DocumentSession()),
        )

        val released = coordinator.begin(DocumentAcquisitionMode.REPLACE)!!
        assertEquals(
            PendingResourceRegistrationResult.Registered,
            coordinator.registerPendingResources(released, DocumentSession(), listOf(owned("released"))),
        )
        coordinator.release(DocumentSession())

        assertEquals(
            listOf("cancelled", "failed", "interrupted", "released"),
            cleaner.deletedReferences,
        )
    }

    @Test
    fun cancellationFailureAndInterruptionPreserveCanonicalAliasesOfTheActiveSession() {
        val cleaner = RecordingCleaner()
        val coordinator = DocumentAcquisitionCoordinator(resourceCleaner = cleaner)
        val current = coordinator.acquire(
            DocumentAcquisitionMode.REPLACE,
            DocumentSession(),
            DocumentAcquisitionInput(
                pages = listOf(
                    AcquiredDocumentPage(
                        owned("active"),
                        DocumentSourceCategory.RENDERED_PDF_PAGE,
                        "image/jpeg",
                    ),
                ),
            ),
        ).successSession()

        val cancelToken = coordinator.begin(DocumentAcquisitionMode.APPEND)!!
        assertEquals(
            PendingResourceRegistrationResult.Registered,
            coordinator.registerPendingResources(
                cancelToken,
                current,
                listOf(owned("cancel-alias", "./active"), owned("cancel-new")),
            ),
        )
        coordinator.cancel(cancelToken, current)

        val failureToken = coordinator.begin(DocumentAcquisitionMode.APPEND)!!
        assertEquals(
            PendingResourceRegistrationResult.Registered,
            coordinator.registerPendingResources(
                failureToken,
                current,
                listOf(owned("fail-alias", "./active"), owned("fail-new")),
            ),
        )
        coordinator.fail(failureToken, current)

        val interruptionToken = coordinator.begin(DocumentAcquisitionMode.APPEND)!!
        assertEquals(
            PendingResourceRegistrationResult.Registered,
            coordinator.registerPendingResources(
                interruptionToken,
                current,
                listOf(owned("interrupt-alias", "./active"), owned("interrupt-new")),
            ),
        )
        coordinator.interrupt(interruptionToken, current)

        assertEquals(listOf("cancel-new", "fail-new", "interrupt-new"), cleaner.deletedReferences)
        coordinator.release(current)
        assertEquals(
            listOf("cancel-new", "fail-new", "interrupt-new", "active"),
            cleaner.deletedReferences,
        )
    }

    @Test
    fun staleRegistrationPreservesActiveSessionAliasesAndCleansOnlyNewOwnedFiles() {
        val cleaner = RecordingCleaner()
        val coordinator = DocumentAcquisitionCoordinator(resourceCleaner = cleaner)
        val current = coordinator.acquire(
            DocumentAcquisitionMode.REPLACE,
            DocumentSession(),
            DocumentAcquisitionInput(
                pages = listOf(
                    AcquiredDocumentPage(
                        owned("active"),
                        DocumentSourceCategory.RENDERED_PDF_PAGE,
                        "image/jpeg",
                    ),
                ),
            ),
        ).successSession()
        val stale = coordinator.begin(DocumentAcquisitionMode.APPEND)!!
        coordinator.cancel(stale, current)
        coordinator.begin(DocumentAcquisitionMode.APPEND)!!

        assertEquals(
            PendingResourceRegistrationResult.StaleToken,
            coordinator.registerPendingResources(
                stale,
                current,
                listOf(owned("active-alias", "./active"), owned("stale-new")),
            ),
        )
        assertEquals(listOf("stale-new"), cleaner.deletedReferences)

        coordinator.release(current)
        assertEquals(listOf("stale-new", "active"), cleaner.deletedReferences)
    }

    @Test
    fun pendingRegistrationRejectsUnsafeOwnershipAtomically() {
        val cleaner = RecordingCleaner()
        val coordinator = DocumentAcquisitionCoordinator(resourceCleaner = cleaner)
        val token = coordinator.begin(DocumentAcquisitionMode.REPLACE)!!
        val invalidResources = listOf(
            AcquiredResource(
                reference = "missing-owned-file",
                ownership = DocumentResourceOwnership.RME_OWNED_TEMPORARY,
            ),
            ownedWithPaths("blank-path", path = "", rootPath = "/private/rme"),
            ownedWithPaths("root", path = "/private/rme", rootPath = "/private/rme"),
            ownedWithPaths(
                "traversal",
                path = "/private/rme/../user/file.jpg",
                rootPath = "/private/rme",
            ),
            ownedWithPaths("canonicalization", path = "\u0000", rootPath = "/private/rme"),
        )

        invalidResources.forEach { invalid ->
            assertEquals(
                PendingResourceRegistrationResult.InvalidResource(
                    DocumentAcquisitionError.INVALID_OWNERSHIP,
                ),
                coordinator.registerPendingResources(token, DocumentSession(), listOf(invalid)),
            )
        }
        assertEquals(
            PendingResourceRegistrationResult.InvalidResource(
                DocumentAcquisitionError.INVALID_OWNERSHIP,
            ),
            coordinator.registerPendingResources(
                token,
                DocumentSession(),
                listOf(
                    owned("valid-but-atomic"),
                    AcquiredResource(
                        reference = "external-with-owned-metadata",
                        ownedTemporaryFile = OwnedTemporaryFile(
                            path = "/private/rme/external",
                            rootPath = "/private/rme",
                        ),
                    ),
                ),
            ),
        )
        assertEquals(
            PendingResourceRegistrationResult.Registered,
            coordinator.registerPendingResources(
                token,
                DocumentSession(),
                listOf(owned("valid"), AcquiredResource("external")),
            ),
        )

        coordinator.cancel(token, DocumentSession())

        assertEquals(listOf("valid"), cleaner.deletedReferences)
    }

    @Test
    fun successfulReplacementTransfersRegisteredPageAndCleansOnlyUntransferredResources() {
        val cleaner = RecordingCleaner()
        val coordinator = DocumentAcquisitionCoordinator(resourceCleaner = cleaner)
        val current = coordinator.acquire(
            DocumentAcquisitionMode.REPLACE,
            DocumentSession(),
            DocumentAcquisitionInput(
                pages = listOf(
                    AcquiredDocumentPage(
                        owned("old"),
                        DocumentSourceCategory.RENDERED_PDF_PAGE,
                        "image/jpeg",
                    ),
                ),
            ),
        ).successSession()
        val token = coordinator.begin(DocumentAcquisitionMode.REPLACE)!!
        val transferred = owned("transferred")
        val staging = owned("staging")
        assertEquals(
            PendingResourceRegistrationResult.Registered,
            coordinator.registerPendingResources(token, current, listOf(transferred, staging)),
        )

        val replacement = coordinator.complete(
            token,
            current,
            DocumentAcquisitionInput(
                pages = listOf(
                    AcquiredDocumentPage(
                        transferred,
                        DocumentSourceCategory.RENDERED_PDF_PAGE,
                        "image/jpeg",
                    ),
                ),
            ),
        ).successSession()

        assertEquals(listOf("old", "staging"), cleaner.deletedReferences)
        coordinator.release(replacement)
        assertEquals(listOf("old", "staging", "transferred"), cleaner.deletedReferences)
    }

    @Test
    fun staleTokenCannotDeleteAnEquivalentResourceRegisteredByTheActiveOperation() {
        val cleaner = RecordingCleaner()
        val coordinator = DocumentAcquisitionCoordinator(resourceCleaner = cleaner)
        val stale = coordinator.begin(DocumentAcquisitionMode.REPLACE)!!
        coordinator.cancel(stale, DocumentSession())
        val active = coordinator.begin(DocumentAcquisitionMode.REPLACE)!!
        assertEquals(
            PendingResourceRegistrationResult.Registered,
            coordinator.registerPendingResources(active, DocumentSession(), listOf(owned("active"))),
        )

        assertEquals(
            PendingResourceRegistrationResult.StaleToken,
            coordinator.registerPendingResources(
                stale,
                DocumentSession(),
                listOf(owned("stale-alias", pathName = "./active")),
            ),
        )
        assertEquals(emptyList<String>(), cleaner.deletedReferences)

        coordinator.cancel(active, DocumentSession())
        assertEquals(listOf("active"), cleaner.deletedReferences)
    }

    @Test
    fun releaseDeduplicatesPendingAndSessionAliases() {
        val cleaner = RecordingCleaner()
        val coordinator = DocumentAcquisitionCoordinator(resourceCleaner = cleaner)
        val session = coordinator.acquire(
            DocumentAcquisitionMode.REPLACE,
            DocumentSession(),
            DocumentAcquisitionInput(
                pages = listOf(
                    AcquiredDocumentPage(
                        owned("active"),
                        DocumentSourceCategory.RENDERED_PDF_PAGE,
                        "image/jpeg",
                    ),
                ),
            ),
        ).successSession()
        val token = coordinator.begin(DocumentAcquisitionMode.APPEND)!!
        coordinator.registerPendingResources(
            token,
            session,
            listOf(owned("active-alias", pathName = "./active")),
        )

        coordinator.release(session)

        assertEquals(listOf("active"), cleaner.deletedReferences)
    }

    @Test
    fun canonicalAliasesArePreservedWhenTheyReferToTheActiveSessionFile() {
        val cleaner = RecordingCleaner()
        val coordinator = DocumentAcquisitionCoordinator(
            limits = DocumentInputLimits(maxPages = 1),
            resourceCleaner = cleaner,
        )
        val current = coordinator.acquire(
            DocumentAcquisitionMode.REPLACE,
            DocumentSession(),
            DocumentAcquisitionInput(
                pages = listOf(
                    AcquiredDocumentPage(
                        owned("active"),
                        DocumentSourceCategory.RENDERED_PDF_PAGE,
                        "image/jpeg",
                    ),
                ),
            ),
        ).successSession()

        val result = coordinator.acquire(
            DocumentAcquisitionMode.APPEND,
            current,
            DocumentAcquisitionInput(
                pages = listOf(
                    AcquiredDocumentPage(
                        owned("alias", pathName = "./active"),
                        DocumentSourceCategory.RENDERED_PDF_PAGE,
                        "image/jpeg",
                    ),
                ),
            ),
        )

        assertEquals(
            DocumentAcquisitionResult.Failure(DocumentAcquisitionError.PAGE_LIMIT_EXCEEDED),
            result,
        )
        assertEquals(emptyList<String>(), cleaner.deletedReferences)
    }

    @Test
    fun byteAndImageBoundsAreRejectedAtomicallyWithTypedErrors() {
        val limits = DocumentInputLimits(
            maxSourceBytes = 100,
            maxDecodedPixelCount = 2_000,
            maxImageDimension = 100,
        )
        val coordinator = DocumentAcquisitionCoordinator(limits = limits)

        assertFailure(
            coordinator,
            input(page("invalid-size", metadata = DocumentImageMetadata(sourceByteCount = 0))),
            DocumentAcquisitionError.INVALID_IMAGE_METADATA,
        )
        assertFailure(
            coordinator,
            input(page("large-file", metadata = DocumentImageMetadata(sourceByteCount = 101))),
            DocumentAcquisitionError.SOURCE_TOO_LARGE,
        )
        assertFailure(
            coordinator,
            input(page("wide", metadata = DocumentImageMetadata(width = 101, height = 1))),
            DocumentAcquisitionError.IMAGE_DIMENSIONS_EXCEEDED,
        )
        assertFailure(
            coordinator,
            input(page("pixels", metadata = DocumentImageMetadata(width = 50, height = 41))),
            DocumentAcquisitionError.IMAGE_DIMENSIONS_EXCEEDED,
        )
        assertFailure(
            coordinator,
            input(page("partial", metadata = DocumentImageMetadata(width = 50))),
            DocumentAcquisitionError.INVALID_IMAGE_METADATA,
        )

        val boundaryMetadata = DocumentImageMetadata(
            sourceByteCount = 100,
            width = 40,
            height = 50,
        )
        val accepted = coordinator.acquire(
            DocumentAcquisitionMode.REPLACE,
            DocumentSession(),
            input(
                page(
                    "boundary",
                    metadata = boundaryMetadata,
                ),
            ),
        ).successSession()
        assertEquals(1, accepted.pages.size)
        assertEquals(boundaryMetadata, accepted.pages.single().imageMetadata)
    }

    @Test
    fun knownSourceByteMetadataIsRetainedForJpegPngAndWebp() {
        val session = DocumentAcquisitionCoordinator().acquire(
            DocumentAcquisitionMode.REPLACE,
            DocumentSession(),
            input(
                page(
                    reference = "jpeg",
                    contentType = "image/jpeg",
                    metadata = DocumentImageMetadata(sourceByteCount = 11L),
                ),
                page(
                    reference = "png",
                    contentType = "image/png",
                    metadata = DocumentImageMetadata(sourceByteCount = 22L),
                ),
                page(
                    reference = "webp",
                    contentType = "image/webp",
                    metadata = DocumentImageMetadata(sourceByteCount = 33L),
                ),
            ),
        ).successSession()

        assertEquals(listOf("image/jpeg", "image/png", "image/webp"), session.pages.map { it.contentType })
        assertEquals(listOf(11L, 22L, 33L), session.pages.map { it.imageMetadata.sourceByteCount })
    }

    @Test
    fun localCleanerDeletesOnlyRegularFilesInsideTheirDeclaredOwnedRoot() {
        val root = Files.createTempDirectory("rme-owned-root-").toFile()
        val outsideRoot = Files.createTempDirectory("rme-user-root-").toFile()
        try {
            val ownedFile = File(root, "owned.jpg").apply { writeText("owned") }
            val userFile = File(root, "user.jpg").apply { writeText("user") }
            val outsideFile = File(outsideRoot, "outside.jpg").apply { writeText("outside") }
            val cleaner = LocalDocumentResourceCleaner()

            cleaner.delete(resourceForFile(ownedFile, root, DocumentResourceOwnership.RME_OWNED_TEMPORARY))
            cleaner.delete(resourceForFile(userFile, root, DocumentResourceOwnership.USER_OR_EXTERNAL))
            cleaner.delete(resourceForFile(outsideFile, root, DocumentResourceOwnership.RME_OWNED_TEMPORARY))

            assertFalse(ownedFile.exists())
            assertTrue(userFile.exists())
            assertTrue(outsideFile.exists())
        } finally {
            root.deleteRecursively()
            outsideRoot.deleteRecursively()
        }
    }

    @Test
    fun localCleanerNormalizesAliasesAndRejectsRootTraversalAndSymlinkEscape() {
        val root = Files.createTempDirectory("rme-owned-root-").toFile()
        val outsideRoot = Files.createTempDirectory("rme-user-root-").toFile()
        try {
            val aliasedFile = File(root, "aliased.jpg").apply { writeText("owned") }
            val outsideFile = File(outsideRoot, "outside.jpg").apply { writeText("outside") }
            val cleaner = LocalDocumentResourceCleaner()

            cleaner.delete(
                resourceForPaths(
                    path = File(root, "./aliased.jpg").path,
                    rootPath = root.path,
                ),
            )
            cleaner.delete(resourceForPaths(path = root.path, rootPath = root.path))
            cleaner.delete(
                resourceForPaths(
                    path = File(root, "../${outsideRoot.name}/outside.jpg").path,
                    rootPath = root.path,
                ),
            )

            assertFalse(aliasedFile.exists())
            assertTrue(root.exists())
            assertTrue(outsideFile.exists())

            val link = File(root, "outside-link")
            val linkCreated = runCatching {
                Files.createSymbolicLink(link.toPath(), outsideRoot.toPath())
            }.isSuccess
            if (linkCreated) {
                cleaner.delete(
                    resourceForPaths(
                        path = File(link, "outside.jpg").path,
                        rootPath = root.path,
                    ),
                )
                assertTrue(outsideFile.exists())
            }
        } finally {
            root.deleteRecursively()
            outsideRoot.deleteRecursively()
        }
    }

    private fun DocumentAcquisitionCoordinator.acquire(
        mode: DocumentAcquisitionMode,
        currentSession: DocumentSession,
        input: DocumentAcquisitionInput,
    ): DocumentAcquisitionResult {
        val token = begin(mode)!!
        return complete(token, currentSession, input)
    }

    private fun DocumentAcquisitionResult.successSession(): DocumentSession =
        (this as DocumentAcquisitionResult.Success).session

    private fun assertFailure(
        coordinator: DocumentAcquisitionCoordinator,
        input: DocumentAcquisitionInput,
        expected: DocumentAcquisitionError,
    ) {
        assertEquals(
            DocumentAcquisitionResult.Failure(expected),
            coordinator.acquire(DocumentAcquisitionMode.REPLACE, DocumentSession(), input),
        )
    }

    private fun input(vararg pages: AcquiredDocumentPage) = DocumentAcquisitionInput(pages.toList())

    private fun page(
        reference: String,
        category: DocumentSourceCategory = DocumentSourceCategory.SELECTED_IMAGE,
        contentType: String = "image/jpeg",
        metadata: DocumentImageMetadata = DocumentImageMetadata(),
    ) = AcquiredDocumentPage(AcquiredResource(reference), category, contentType, metadata)

    private fun owned(reference: String?, pathName: String = requireNotNull(reference)) = AcquiredResource(
        reference = reference,
        ownership = DocumentResourceOwnership.RME_OWNED_TEMPORARY,
        ownedTemporaryFile = OwnedTemporaryFile(
            path = "/private/rme/$pathName",
            rootPath = "/private/rme",
        ),
    )

    private fun ownedWithPaths(
        reference: String,
        path: String,
        rootPath: String,
    ) = AcquiredResource(
        reference = reference,
        ownership = DocumentResourceOwnership.RME_OWNED_TEMPORARY,
        ownedTemporaryFile = OwnedTemporaryFile(path = path, rootPath = rootPath),
    )

    private fun inputWithOwnedAndExternal(reference: String) = DocumentAcquisitionInput(
        pages = listOf(
            AcquiredDocumentPage(
                owned(reference),
                DocumentSourceCategory.RENDERED_PDF_PAGE,
                "image/jpeg",
            ),
            page("$reference-external", DocumentSourceCategory.INBOUND_SHARE),
        ),
    )

    private fun resourceForFile(
        file: File,
        root: File,
        ownership: DocumentResourceOwnership,
    ) = DocumentResource(
        reference = file.toURI().toString(),
        ownership = ownership,
        ownedTemporaryFile = if (ownership == DocumentResourceOwnership.RME_OWNED_TEMPORARY) {
            OwnedTemporaryFile(file.absolutePath, root.absolutePath)
        } else {
            null
        },
    )

    private fun resourceForPaths(path: String, rootPath: String) = DocumentResource(
        reference = "owned-test-resource",
        ownership = DocumentResourceOwnership.RME_OWNED_TEMPORARY,
        ownedTemporaryFile = OwnedTemporaryFile(path = path, rootPath = rootPath),
    )

    private class RecordingCleaner : DocumentResourceCleaner {
        val deletedReferences = mutableListOf<String>()
        val deletedPaths = mutableListOf<String>()

        override fun delete(resource: DocumentResource) {
            deletedReferences += resource.reference
            deletedPaths += requireNotNull(resource.ownedTemporaryFile).path
        }
    }
}
