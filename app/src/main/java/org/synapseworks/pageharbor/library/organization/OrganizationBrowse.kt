package org.synapseworks.pageharbor.library.organization

import androidx.sqlite.db.SimpleSQLiteQuery
import org.synapseworks.pageharbor.library.*

/** One count anchor plus at most30 documents, including a count row for an empty page. */
data class OrganizationBrowseRow(val total:Long,val documentId:String?,val title:String?,val createdAt:Long?,val modifiedAt:Long?,
    val documentDate:Long?,val dateProvenance:String?,val importedAt:Long?,val pageCount:Int?,val folderId:String?,val folderName:String?,
    val thumbnail:String?,val ocrStatus:String?,val favorite:Boolean?,val matchType:String?,val pageId:String?,val pagePosition:Int?,val snippet:String?,val tagMatched:Boolean?=null) {
    fun summary(query:String=""):LibraryDocumentSummary? {
        val id=documentId?:return null
        return LibraryDocumentSummary(id,requireNotNull(title),requireNotNull(createdAt),requireNotNull(modifiedAt),documentDate,
            dateProvenance?.let{runCatching{LibraryDocumentDateProvenance.valueOf(it)}.getOrNull()},importedAt,requireNotNull(pageCount),folderId,folderName,thumbnail,
            runCatching{LibraryOcrStatus.valueOf(requireNotNull(ocrStatus))}.getOrDefault(LibraryOcrStatus.NOT_INDEXED),
            searchMatch=matchType?.let{
                when {
                    matchesLibrarySearchText(requireNotNull(title),query)->LibrarySearchMatch.TITLE
                    tagMatched==true->LibrarySearchMatch.TAG
                    it=="FOLDER"->LibrarySearchMatch.FOLDER
                    else->LibrarySearchMatch.OCR
                }
            },searchSnippet=snippet,matchingPageId=pageId,matchingPagePosition=pagePosition,isFavorite=favorite==true)
    }
}
data class OrganizationPage(val items:List<LibraryDocumentSummary> = emptyList(),val total:Long=0,val offset:Int=0,val loading:Boolean=false,val failed:Boolean=false) {
    val hasNext get()=offset.toLong()+items.size<total
}
internal object OrganizationBrowse {
    fun query(folderId:String?,text:String,sort:LibrarySortOrder,filter:LibraryOrganizationFilter,offset:Int):SimpleSQLiteQuery {
        require(offset>=0&&offset<=Int.MAX_VALUE-LibraryTagRules.PAGE_SIZE)
        val args=mutableListOf<Any?>();val raw=text.take(160).trim();val searching=raw.isNotEmpty()
        val fts=raw.toFtsPrefixQuery()
        val prefix=if(searching){
            val docs=if(fts!=null){args+=fts;"doc_hits AS (SELECT d.document_id FROM library_document_search_v3 s JOIN library_document_search_content_v3 c ON c.rowid=s.rowid JOIN library_documents d ON d.row_id=c.rowid WHERE library_document_search_v3 MATCH ?),"}
                else "doc_hits AS (SELECT document_id FROM library_documents WHERE 0),"
            args+=LibraryTagRules.like(raw)
            val tags="tag_hits AS (SELECT DISTINCT a.document_id FROM library_document_tags a JOIN library_tags t ON t.tag_id=a.tag_id WHERE t.normalized_name LIKE ? ESCAPE '\\'),"
            val pages=if(fts!=null){args+=fts;"page_hits AS (SELECT p.document_id,p.page_id,p.page_position,substr(snippet(library_page_search_v3,'','',' … ',1,12),1,240) AS excerpt FROM library_page_search_v3 s JOIN library_page_search_content_v3 c ON c.rowid=s.rowid JOIN library_pages p ON p.page_id=c.page_id WHERE library_page_search_v3 MATCH ?),"}
                else "page_hits AS (SELECT document_id,page_id,page_position,NULL AS excerpt FROM library_pages WHERE 0),"
            docs+tags+pages
        }else ""
        // Classify Unicode/prefix title matches with the existing Kotlin search normalization.
        // SQLite lower()/LIKE is not a Unicode or multi-token FTS title classifier.
        val type=if(searching)"CASE WHEN d.document_id IN(SELECT document_id FROM doc_hits) THEN 'FOLDER' ELSE 'OCR' END"else "NULL"
        val tagMatched=if(searching)"p.document_id IN(SELECT document_id FROM tag_hits)"else "0"
        val conditions=mutableListOf("d.library_state='ACTIVE'")
        if(searching)conditions+="(d.document_id IN(SELECT document_id FROM doc_hits) OR d.document_id IN(SELECT document_id FROM tag_hits) OR d.document_id IN(SELECT document_id FROM page_hits))"
        if(folderId!=null){conditions+="d.folder_id=?";args+=folderId}
        if(filter.favoritesOnly)conditions+="d.is_favorite=1"
        if(filter.tagIds.isNotEmpty()){
            val selected=filter.tagIds.sorted();val marks=selected.joinToString(","){"?"}
            conditions+=if(filter.match==TagMatchMode.ANY)"EXISTS(SELECT 1 FROM library_document_tags a WHERE a.document_id=d.document_id AND a.tag_id IN($marks))"
                else "(SELECT COUNT(*) FROM library_document_tags a WHERE a.document_id=d.document_id AND a.tag_id IN($marks))=${selected.size}"
            args.addAll(selected)
        }
        val order=when(sort){
            LibrarySortOrder.MODIFIED_DESC->"modified_at DESC,title COLLATE NOCASE,document_id"
            LibrarySortOrder.CREATED_DESC->"created_at DESC,title COLLATE NOCASE,document_id"
            LibrarySortOrder.TITLE_ASC->"title COLLATE NOCASE,modified_at DESC,document_id"
            LibrarySortOrder.DOCUMENT_DATE_DESC->"document_date_epoch_day IS NULL,document_date_epoch_day DESC,imported_at IS NULL,imported_at DESC,document_id"
        }
        args+=LibraryTagRules.PAGE_SIZE;args+=offset
        val page=if(searching)"(SELECT h.page_id FROM page_hits h WHERE h.document_id=p.document_id ORDER BY h.page_position,h.page_id LIMIT 1)"else"NULL"
        val position=if(searching)"(SELECT h.page_position FROM page_hits h WHERE h.document_id=p.document_id ORDER BY h.page_position,h.page_id LIMIT 1)"else"NULL"
        val snippet=if(searching)"(SELECT h.excerpt FROM page_hits h WHERE h.document_id=p.document_id ORDER BY h.page_position,h.page_id LIMIT 1)"else"NULL"
        return SimpleSQLiteQuery("""WITH $prefix filtered AS (SELECT d.*,$type AS match_type FROM library_documents d WHERE ${conditions.joinToString(" AND ")}),
            page AS (SELECT * FROM filtered ORDER BY $order LIMIT ? OFFSET ?)
            SELECT total.n AS total,p.document_id AS documentId,p.title,p.created_at AS createdAt,p.modified_at AS modifiedAt,
            p.document_date_epoch_day AS documentDate,p.document_date_provenance AS dateProvenance,p.imported_at AS importedAt,p.page_count AS pageCount,
            p.folder_id AS folderId,f.name AS folderName,p.thumbnail_path AS thumbnail,p.ocr_status AS ocrStatus,p.is_favorite AS favorite,p.match_type AS matchType,
            $page AS pageId,$position AS pagePosition,$snippet AS snippet,$tagMatched AS tagMatched
            FROM (SELECT COUNT(*) AS n FROM filtered) total LEFT JOIN page p ON 1 LEFT JOIN library_folders f ON f.folder_id=p.folder_id
            ORDER BY ${order.split(',').joinToString(","){"p."+it.trim()}}""".trimIndent(),args.toTypedArray())
    }
}
