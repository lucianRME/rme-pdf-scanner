package org.synapseworks.pageharbor.library.recycle

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import org.synapseworks.pageharbor.document.session.*

class LibrarySourceReadersTest {
    @Test fun currentHistoryAndCapturedOwnersProtectLibraryPathsEvenWithoutDocumentReference() {
        val root=Files.createTempDirectory("synthetic-reader").toFile();val id="synthetic-id"
        val r=createLibraryDocumentResource("content://synthetic",root.resolve("$id/revision/page.png").path,root.path)!!
        val s=DocumentSession(listOf(DocumentPage(DocumentPageId(1),r,DocumentSourceCategory.LIBRARY)),libraryDocument=LibraryDocumentReference(id,"Synthetic"))
        val current=Any();val capture=Any()
        try {
            LibrarySourceReaders.retain(current,listOf(s.copy(libraryDocument=null)))
            LibrarySourceReaders.retain(capture,listOf(s))
            assertTrue(LibrarySourceReaders.inUse(id,root.resolve(id).path))
            LibrarySourceReaders.block(id,root.resolve(id).path)
            assertFalse(LibrarySourceReaders.canCapture(s));assertFalse(LibrarySourceReaders.canCapture(s.copy(libraryDocument=null)))
            LibrarySourceReaders.retain(current,emptyList());assertTrue(LibrarySourceReaders.inUse(id,root.resolve(id).path))
            LibrarySourceReaders.retain(capture,emptyList());assertFalse(LibrarySourceReaders.inUse(id,root.resolve(id).path))
            LibrarySourceReaders.unblock(id);assertTrue(LibrarySourceReaders.canCapture(s))
            val reservation=LibrarySourceReaders.reserve(s)!!
            LibrarySourceReaders.block(id,root.resolve(id).path)
            assertTrue(LibrarySourceReaders.inUse(id,root.resolve(id).path))
            assertNull(LibrarySourceReaders.reserve(s))
            LibrarySourceReaders.release(reservation)
            assertFalse(LibrarySourceReaders.inUse(id,root.resolve(id).path))
        } finally {LibrarySourceReaders.retain(current,emptyList());LibrarySourceReaders.retain(capture,emptyList());LibrarySourceReaders.unblock(id);root.deleteRecursively()}
    }
}
