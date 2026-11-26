package org.synapseworks.pageharbor.office

/** Reviewed pages bind to one source/output owner; corrections invalidate coverage. */
internal class OfficeReview(val owner: String, val digest: String, val pages: Int) {
    private val covered = BooleanArray(pages.also { require(it in 1..10) })
    init { require(owner.isNotBlank() && digest.matches(Regex("[a-f0-9]{64}")) && pages in 1..10) }
    fun reviewed(page: Int, value: Boolean) { require(page in covered.indices); covered[page] = value }
    fun contains(page: Int) = page in covered.indices && covered[page]
    fun ready(owner: String, digest: String) = this.owner == owner && this.digest == digest && covered.all { it }
    override fun toString() = "OfficeReview(<redacted>)"
}
