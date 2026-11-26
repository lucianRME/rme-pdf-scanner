package org.synapseworks.pageharbor.xlsx

import org.synapseworks.pageharbor.ui.theme.RmeActionRow
import org.synapseworks.pageharbor.ui.theme.RmePrimaryButton
import org.synapseworks.pageharbor.ui.theme.RmeSecondaryButton
import org.synapseworks.pageharbor.ui.theme.RmeTextButton
import org.synapseworks.pageharbor.ui.theme.RmeTextField

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.synapseworks.pageharbor.receipt.*

@Composable internal fun XlsxScreen(vm:XlsxViewModel,save:()->Unit,share:()->Unit,addTable:()->Unit,addReceipt:()->Unit,queue:(()->Unit)?=null) {
    BackHandler{vm.close()}
    val book=vm.book;val enabled=!vm.busy&&!vm.picker
    var selected by remember{mutableIntStateOf(0)}
    var expanded by remember{mutableStateOf<String?>(null)}
    var merge by remember{mutableStateOf("1,1,1,2")}
    LazyColumn(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(12.dp).testTag("xlsx-screen"),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        item{Text("Reviewed workbook",style=MaterialTheme.typography.headlineSmall)}
        item{Text("Editable cells from your reviewed receipts and tables. No original formulas, charts or business rules are recovered. Raw readings and corrections travel in Sources. This is not secure removal; use Share-Safe before extraction when needed.")}
        item{Text("TEXT is the default. Numbers need explicit types and separators. Identifiers and leading zeros stay text. Missing amounts stay blank. Dates use 1900-03-01 through 9999-12-31. Supported currencies: EUR/USD/GBP/CAD/AUD/RON/CHF.")}
        item{RmeActionRow {RmeSecondaryButton(onClick=addTable,enabled=enabled&&book.tables.size<4,modifier=Modifier.weight(1f).testTag("xlsx-add-table")){Text("Add reviewed table")};RmeSecondaryButton(onClick=addReceipt,enabled=enabled&&book.expenses.size<12,modifier=Modifier.weight(1f).testTag("xlsx-add-receipt")){Text("Add reviewed receipt")}}}
        item{RmeTextField(vm.name,vm::rename,label={Text("Separate XLSX name")},enabled=enabled,modifier=Modifier.fillMaxWidth().testTag("xlsx-name"))}
        for((i,table)in book.tables.withIndex()) {
            item(key="table-$i") {
                Text("Worksheet ${i+1}: ${table.name}",style=MaterialTheme.typography.titleMedium)
                RmeTextField(table.name,{vm.table(i,table.copy(name=it.take(31)))},label={Text("Worksheet name")},enabled=enabled,modifier=Modifier.fillMaxWidth().testTag("xlsx-sheet-$i"))
                Text("${table.grid.rows.size} rows × ${table.grid.columns} cells. Source S${table.source.number} page 1 · ${table.source.kind}")
                RmeTextButton(onClick={selected=(selected+1)%table.grid.rows.flatten().size},enabled=enabled,modifier=Modifier.testTag("xlsx-next-cell-$i")){Text("Select next cell")}
                val cells=table.grid.rows.flatten();val cell=cells[selected.coerceIn(cells.indices)];val typing=table.types[cell.id]?:XlsxTyping()
                val row=selected.coerceIn(cells.indices)/table.grid.columns;val col=selected.coerceIn(cells.indices)%table.grid.columns
                Text("Selected R${row+1} C${col+1} · ${table.source.references[cell.id]?:"manual addition"}")
                RmeTextField(cell.value,{vm.table(i,table.copy(grid=table.grid.edit(cell.id,it)))},label={Text("Cell value")},enabled=enabled,modifier=Modifier.fillMaxWidth().testTag("xlsx-cell-$i"))
                RmeTextButton(onClick={vm.table(i,table.copy(types=table.types+(cell.id to typing.copy(type=XlsxType.entries[(typing.type.ordinal+1)%XlsxType.entries.size]))))},enabled=enabled,modifier=Modifier.testTag("xlsx-type-$i")){Text("Cell type: ${typing.type} (change)")}
                if(typing.type in setOf(XlsxType.NUMBER,XlsxType.MONEY))RmeTextButton(onClick={vm.table(i,table.copy(types=table.types+(cell.id to typing.copy(decimals=ReceiptDecimal.entries[(typing.decimals.ordinal+1)%3]))))},enabled=enabled,modifier=Modifier.testTag("xlsx-decimal-$i")){Text("Decimal rule: ${typing.decimals}")}
                if(typing.type==XlsxType.DATE)RmeTextButton(onClick={vm.table(i,table.copy(types=table.types+(cell.id to typing.copy(dates=ReceiptDateOrder.entries[(typing.dates.ordinal+1)%3]))))},enabled=enabled){Text("Date order: ${typing.dates}")}
                if(typing.type==XlsxType.MONEY)RmeTextButton(onClick={val codes=ReceiptValues.currencies.toList();vm.table(i,table.copy(types=table.types+(cell.id to typing.copy(currency=codes[(codes.indexOf(typing.currency)+1)%codes.size]))))},enabled=enabled){Text("Currency: ${typing.currency}")}
                Text("Interpreted: ${XlsxValues.value(cell.value,typing)?:"Unsupported or ambiguous: correct value/type/rule"}")
                RmeActionRow {RmeTextButton(onClick={vm.table(i,table.copy(grid=table.grid.insertRow(row+1),merges=emptyList()))},enabled=enabled&&table.grid.rows.size<40){Text("Insert row")};RmeTextButton(onClick={vm.table(i,table.copy(grid=table.grid.deleteRow(row),merges=emptyList()))},enabled=enabled&&table.grid.rows.size>1){Text("Delete row")}}
                RmeActionRow {RmeTextButton(onClick={vm.table(i,table.copy(grid=table.grid.insertColumn(col+1),merges=emptyList()))},enabled=enabled&&table.grid.columns<12){Text("Insert column")};RmeTextButton(onClick={vm.table(i,table.copy(grid=table.grid.deleteColumn(col),merges=emptyList()))},enabled=enabled&&table.grid.columns>1){Text("Delete column")}}
                Text("Structure changes clear reviewed merges; confirm their positions again.")
                Text("${if(cell.corrected)"User corrected"else if(cell.uncertain)"Uncertain OCR proposal"else"Reviewed input"}. Original: ${table.source.original[cell.id].orEmpty()}")
                RmeTextButton(onClick={expanded=if(expanded=="table-$i")null else "table-$i"},modifier=Modifier.testTag("xlsx-grid-$i")){Text("Show all cells and source evidence")}
                if(expanded=="table-$i") {
                    table.grid.rows.forEachIndexed{r,values->values.forEachIndexed{c,v->RmeTextButton(onClick={selected=r*table.grid.columns+c},enabled=enabled){Text("R${r+1} C${c+1} · ${(table.types[v.id]?:XlsxTyping()).type}: ${v.value.ifEmpty{"(missing)"}}")}}}
                    table.source.concerns.forEach{Text(it)}
                    Text(table.source.raw)
                }
                RmeTextField(merge,{merge=it.take(30)},label={Text("Merge: top,left,bottom,right (1-based)")},enabled=enabled,modifier=Modifier.fillMaxWidth().testTag("xlsx-merge-range-$i"))
                RmeTextButton(onClick={val v=merge.split(',').mapNotNull{it.trim().toIntOrNull()};if(v.size==4&&v.all{it in 1..40})vm.table(i,table.copy(merges=table.merges+XlsxMerge(v[0]-1,v[1]-1,v[2]-1,v[3]-1)))},enabled=enabled,modifier=Modifier.testTag("xlsx-merge-$i")){Text("Add reviewed merge (covered cells must be blank)")}
                table.merges.forEachIndexed{n,m->RmeTextButton(onClick={vm.table(i,table.copy(merges=table.merges.filterIndexed{j,_->j!=n}))},enabled=enabled){Text("Remove merge R${m.top+1}C${m.left+1}:R${m.bottom+1}C${m.right+1}")}}
                Row{Checkbox(table.confirmed,{vm.confirmTable(i,it)},enabled=enabled,modifier=Modifier.testTag("xlsx-confirm-table-$i"));Text("I reviewed every value, type, blank, source and merge.")}
                RmeTextButton(onClick={vm.removeTable(i)},enabled=enabled){Text("Remove worksheet")}
            }
        }
        if(book.expenses.isNotEmpty()) {
            item{RmeTextButton(onClick={vm.update(book.copy(template=XlsxTemplate.entries[(book.template.ordinal+1)%2]))},enabled=enabled,modifier=Modifier.testTag("xlsx-template")){Text("Template: ${book.template.label}")}}
            if(book.template==XlsxTemplate.REIMBURSEMENT)item {
                RmeTextField(book.claimant,{vm.update(book.copy(claimant=it.take(128)))},label={Text("Claimant (optional)")},enabled=enabled,modifier=Modifier.fillMaxWidth())
                RmeTextField(book.context,{vm.update(book.copy(context=it.take(128)))},label={Text("Report context (optional)")},enabled=enabled,modifier=Modifier.fillMaxWidth())
            }
        }
        for((i,e)in book.expenses.withIndex())item(key="expense-$i") {
            Text("Receipt ${i+1} · S${e.source.number} page 1 · ${e.source.kind}",style=MaterialTheme.typography.titleMedium)
            RmeTextField(e.merchant,{vm.expense(i,e.copy(merchant=it.take(256)))},label={Text("Merchant")},enabled=enabled,modifier=Modifier.fillMaxWidth().testTag("xlsx-merchant-$i"))
            RmeTextField(e.date,{vm.expense(i,e.copy(date=it.take(32)))},label={Text("Date (ISO YYYY-MM-DD or blank)")},enabled=enabled,modifier=Modifier.fillMaxWidth().testTag("xlsx-date-$i"))
            RmeTextField(e.currency,{vm.expense(i,e.copy(currency=it.take(3)))},label={Text("Confirmed currency code or blank")},enabled=enabled,modifier=Modifier.fillMaxWidth().testTag("xlsx-currency-$i"))
            RmeTextField(e.amount,{vm.expense(i,e.copy(amount=it.take(32)))},label={Text("Signed expense amount (dot decimal or missing)")},enabled=enabled,modifier=Modifier.fillMaxWidth().testTag("xlsx-amount-$i"))
            RmeTextField(e.tax,{vm.expense(i,e.copy(tax=it.take(32)))},label={Text("Printed tax (separate; not added to total)")},enabled=enabled,modifier=Modifier.fillMaxWidth())
            RmeTextField(e.category,{vm.expense(i,e.copy(category=it.take(256)))},label={Text("Category (optional)")},enabled=enabled,modifier=Modifier.fillMaxWidth())
            RmeTextField(e.purpose,{vm.expense(i,e.copy(purpose=it.take(256)))},label={Text("Purpose (optional)")},enabled=enabled,modifier=Modifier.fillMaxWidth())
            RmeTextButton(onClick={expanded=if(expanded=="receipt-$i")null else "receipt-$i"},modifier=Modifier.testTag("xlsx-source-$i")){Text("Show receipt readings and corrections")}
            if(expanded=="receipt-$i"){Text(e.source.raw);ReceiptField.entries.forEach{f->Text("${f.label}: ${e.values[f]?:"Missing"} · ${if(f in e.corrected)"User corrected"else e.source.suggestions[f.name].orEmpty()}");e.source.evidence[f.name].orEmpty().forEach{Text(it)}}}
            Text("Refunds keep their printed sign. Confirm the signed expense amount; missing fields are not zero.")
            Row{Checkbox(e.confirmed,{vm.confirmExpense(i,it)},enabled=enabled,modifier=Modifier.testTag("xlsx-confirm-expense-$i"));Text("I confirmed the signed amount, currency, corrections and missing fields.")}
            RmeTextButton(onClick={vm.removeExpense(i)},enabled=enabled){Text("Remove receipt")}
        }
        if(book.expenses.isNotEmpty())item {
            Text("Exact reviewed totals",style=MaterialTheme.typography.titleMedium)
            runCatching{XlsxValues.totals(book)}.getOrNull()?.forEach{(c,v)->Text("${c.ifEmpty{"Unassigned currency"}}: ${v?.toPlainString()?:"Unavailable: amount/currency missing or unconfirmed"}")}
            Text("Currencies are separate. Formulas cover fixed reviewed rows; changing currency or adding rows after export needs review/re-export. No exchange rates or tax rules are inferred.")
        }
        book.problem()?.let{p->item{Text(p,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("xlsx-problem"))}}
        item{Row{Checkbox(vm.reviewed,vm::review,enabled=enabled,modifier=Modifier.testTag("xlsx-review"));Text("I reviewed worksheets, types, source evidence, missing values and totals.")}}
        item{RmePrimaryButton(onClick=vm::generate,enabled=enabled&&vm.reviewed&&book.problem()==null&&vm.nameValid(),modifier=Modifier.fillMaxWidth().testTag("xlsx-generate")){Text("Generate reviewed XLSX")}}
        if(vm.busy)item{Text("Working locally · ${vm.progress} worksheets",Modifier.testTag("xlsx-working"));RmeSecondaryButton(onClick=vm::cancelWork,modifier=Modifier.testTag("xlsx-cancel-work")){Text("Cancel")}}
        vm.prepared?.let{p->item{Text("Frozen reviewed workbook · ${p.digest.second} bytes. Original source documents unchanged.",Modifier.testTag("xlsx-prepared"));RmeActionRow {RmePrimaryButton(onClick=save,enabled=enabled,modifier=Modifier.weight(1f).testTag("xlsx-save")){Text("Save separate XLSX")};RmeSecondaryButton(onClick=share,enabled=enabled,modifier=Modifier.weight(1f).testTag("xlsx-share")){Text("Share XLSX")}}}}
        if(vm.prepared!=null&&queue!=null)item{RmeSecondaryButton(onClick=queue,enabled=enabled,modifier=Modifier.fillMaxWidth().testTag("xlsx-queue")){Text("Review frozen recipe job")}}
        vm.message?.let{item{Text(it,Modifier.testTag("xlsx-message"))}}
        item{RmeTextButton(onClick=vm::close,modifier=Modifier.fillMaxWidth().testTag("xlsx-close")){Text("Cancel workbook and discard draft")}}
    }
}
