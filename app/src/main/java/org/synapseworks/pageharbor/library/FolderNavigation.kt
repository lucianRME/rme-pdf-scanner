package org.synapseworks.pageharbor.library

/** A navigation-only search result; no library schema or saved metadata changes. */
data class FolderNavigationItem(val folder: LibraryFolder, val path: String)

fun folderNavigationItems(
    folders: List<LibraryFolder>,
    selectedFolderId: String?,
    query: String,
): List<FolderNavigationItem> {
    val term = query.trim().take(256)
    if (term.isEmpty()) return folders.filter { it.parentFolderId == selectedFolderId }
        .map { FolderNavigationItem(it, it.name) }
    val byId = folders.associateBy(LibraryFolder::id)
    return folders.mapNotNull { folder ->
        val names = ArrayDeque<String>()
        val visited = mutableSetOf<String>()
        var current: LibraryFolder? = folder
        while (current != null && visited.add(current.id)) {
            names.addFirst(current.name)
            current = current.parentFolderId?.let(byId::get)
        }
        val path = names.joinToString(" / ")
        FolderNavigationItem(folder, path).takeIf { path.contains(term, ignoreCase = true) }
    }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.path })
}
