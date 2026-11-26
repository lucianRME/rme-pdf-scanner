package org.synapseworks.pageharbor.businesscard

import android.content.ContentValues
import android.content.Intent
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.*

/** No provider writes, account selection, raw OCR, card photo, notes, URI or executable links. */
internal object ContactEditorIntent {
    fun create(reviewed: ContactDraft): Intent? {
        if(!reviewed.valid()) return null
        val draft = reviewed.cleaned()
        val rows = arrayListOf<ContentValues>()
        fun row(mime: String, vararg values: Pair<String, String>) {
            rows += ContentValues().apply {
                put(ContactsContract.Data.MIMETYPE, mime)
                for((key, value) in values) if(value.isNotEmpty()) put(key, value)
            }
        }
        if(draft.organization.isNotEmpty() || draft.title.isNotEmpty()) row(Organization.CONTENT_ITEM_TYPE,
            Organization.COMPANY to draft.organization, Organization.TITLE to draft.title)
        draft.phones.forEach { row(Phone.CONTENT_ITEM_TYPE, Phone.NUMBER to it) }
        draft.emails.forEach { row(Email.CONTENT_ITEM_TYPE, Email.ADDRESS to it) }
        draft.addresses.forEach { row(StructuredPostal.CONTENT_ITEM_TYPE, StructuredPostal.FORMATTED_ADDRESS to it) }
        return Intent(Intent.ACTION_INSERT).setType(ContactsContract.Contacts.CONTENT_TYPE)
            .putExtra(ContactsContract.Intents.Insert.FULL_MODE, true)
            .apply { if(draft.name.isNotEmpty()) putExtra(ContactsContract.Intents.Insert.NAME, draft.name) }
            .putParcelableArrayListExtra(ContactsContract.Intents.Insert.DATA, rows)
    }
}
