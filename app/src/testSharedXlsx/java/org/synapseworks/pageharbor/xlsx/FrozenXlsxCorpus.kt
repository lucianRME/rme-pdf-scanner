package org.synapseworks.pageharbor.xlsx

import org.synapseworks.pageharbor.table.*
import org.synapseworks.pageharbor.receipt.*

/** Independent fixed data from m33/tables.json and receipts.json; never derived from output. */
internal object FrozenXlsxCorpus {
    fun table():XlsxTable {
        val grid=TableGrid.from(listOf(listOf("Reviewed table","","",""),listOf("ID","Amount","Date","Notes"),listOf("00123","12.50","2026-05-04","First line\nSecond line"),listOf("=1+1","-2.50","","@data")))
        val refs=grid.rows.flatMapIndexed{r,row->row.mapIndexed{c,v->v.id to "S1 P1 R${r+1} C${c+1}"}}.toMap()
        val source=XlsxSource(1,"Frozen supplied M22-style grid","independent-1","Unchanged independent table source",original=grid.rows.flatten().associate{it.id to it.value},references=refs)
        val money=XlsxTyping(XlsxType.MONEY,ReceiptDecimal.DOT,currency="EUR")
        return XlsxTable("Table 1",source,grid,mapOf(grid.rows[2][1].id to money,grid.rows[3][1].id to money,grid.rows[2][2].id to XlsxTyping(XlsxType.DATE)),listOf(XlsxMerge(0,0,0,3)),true)
    }
    fun second():XlsxTable {
        val grid=TableGrid.from(listOf(listOf("Code","Value"),listOf("00007","0.000001"),listOf("μ µ","+SUM(A1:A2)")))
        return XlsxTable("Second table",XlsxSource(2,"Manual entry","independent-2","",original=grid.rows.flatten().associate{it.id to it.value},references=grid.rows.flatMapIndexed{r,row->row.mapIndexed{c,v->v.id to "S2 P1 R${r+1} C${c+1}"}}.toMap()),grid,mapOf(grid.rows[1][1].id to XlsxTyping(XlsxType.NUMBER,ReceiptDecimal.DOT)),confirmed=true)
    }
    val rawReceipts=listOf("Merchant: North Shop\nDate: 2026-05-04\nCurrency: EUR\nTotal: 12.50\nTax: 2.50","Merchant: Return Desk\nCurrency: EUR\nTotal: -2.50","Merchant: West Store\nDate: 2026-05-05\nUSD\nTotal: 5.25","Merchant: Sparse Store\nEUR","Merchant: Review Store\nDate: 04/05/2026\nTotal: 1,234\n$")
    fun expenses():List<XlsxExpense> = rawReceipts.mapIndexed{i,text->
        val extraction=requireNotNull(ReceiptParser.parse(text));val draft=extraction.draft();val values=requireNotNull(draft.normalized(ReceiptRules()))
        val state=ReceiptState("frozen-source-${i+3}",1,ReceiptPhase.REVIEW,extraction,draft,reviewed=values)
        XlsxCapture.receipt(state,i+3,"Uncorrected M21 parser result; confirmed separately").copy(confirmed=true)
    }
    fun types():XlsxTable {
        val rows=listOf(
            Triple("identifier","00123",XlsxTyping()),Triple("formula","=1+1",XlsxTyping()),Triple("at","@SUM(A1:A2)",XlsxTyping()),
            Triple("url"," \t=HYPERLINK(\"https://example.invalid\",\"x\")",XlsxTyping()),Triple("multiline","First line\nSecond line",XlsxTyping()),
            Triple("unicode","Café Ελληνικά μ µ 中文",XlsxTyping()),Triple("negative","-12.3456",XlsxTyping(XlsxType.NUMBER,ReceiptDecimal.DOT)),
            Triple("localized","1.234,56",XlsxTyping(XlsxType.MONEY,ReceiptDecimal.COMMA,currency="EUR")),Triple("refund","(10.00)",XlsxTyping(XlsxType.MONEY,ReceiptDecimal.DOT,currency="USD")),
            Triple("zero","0",XlsxTyping(XlsxType.MONEY,ReceiptDecimal.DOT,currency="EUR")),Triple("date","04/05/2026",XlsxTyping(XlsxType.DATE,dates=ReceiptDateOrder.DMY)),
            Triple("missing","",XlsxTyping(XlsxType.BLANK)),Triple("number-max","999999999999",XlsxTyping(XlsxType.NUMBER,ReceiptDecimal.DOT)),Triple("date-min","1900-03-01",XlsxTyping(XlsxType.DATE)))
        val grid=TableGrid.from(listOf(listOf("Case","Value"))+rows.map{listOf(it.first,it.second)})
        return XlsxTable("Types",XlsxSource(1,"Frozen typed controls","types-1","",original=grid.rows.flatten().associate{it.id to it.value},references=grid.rows.flatMapIndexed{r,row->row.mapIndexed{c,v->v.id to "S1 P1 R${r+1} C${c+1}"}}.toMap()),grid,rows.mapIndexed{i,r->grid.rows[i+1][1].id to r.third}.toMap(),confirmed=true)
    }
    fun literals():XlsxTable {
        val values=listOf("=SUM(1,2)","+12","-2+3","@SUM(A1:A2)"," \t=HYPERLINK(\"https://example.invalid\",\"x\")","<f>SUM(A1)</f>","literal_x0041_","literal_x005F_","a\r\nb","0000123")
        val grid=TableGrid.from(listOf(listOf("Case","Literal text"))+values.mapIndexed{i,v->listOf((i+1).toString(),v)})
        return XlsxTable("Literals",XlsxSource(1,"Independent literal controls","literal-1","",original=grid.rows.flatten().associate{it.id to it.value},references=grid.rows.flatMapIndexed{r,row->row.mapIndexed{c,v->v.id to "S1 P1 R${r+1} C${c+1}"}}.toMap()),grid,confirmed=true)
    }
    fun books():Map<String,XlsxBook> {
        val e=expenses()
        return linkedMapOf("literals" to XlsxBook(listOf(literals())),"types" to XlsxBook(listOf(types())),"tables" to XlsxBook(listOf(table(),second())),"complete" to XlsxBook(expenses=e.take(3)),"missing" to XlsxBook(expenses=e.take(4)),"uncertain" to XlsxBook(expenses=e),"reimbursement" to XlsxBook(listOf(table(),second()),e.take(3),XlsxTemplate.REIMBURSEMENT,"Example claimant","Independent synthetic report"))
    }
}
