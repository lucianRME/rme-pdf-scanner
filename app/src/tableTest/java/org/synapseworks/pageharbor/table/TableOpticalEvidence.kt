package org.synapseworks.pageharbor.table

/** Test-only accuracy evidence. The frozen expected grid never follows OCR output. */
internal object TableOpticalEvidence {
    fun record(context:android.content.Context,label:String,grid:TableGrid,expected:List<List<String>>) {
        val actual=grid.values();val positions=org.json.JSONArray()
        val shape=actual.size==expected.size&&actual.all{it.size==expected.first().size}
        if(shape)expected.indices.forEach{r->expected[r].indices.forEach{c->if(actual[r][c]!=expected[r][c])positions.put(org.json.JSONArray(listOf(r+1,c+1)))}}
        val file=java.io.File(context.cacheDir,"m22-optical-evidence.json")
        val rows=if(file.exists())org.json.JSONArray(file.readText())else org.json.JSONArray()
        rows.put(org.json.JSONObject().put("case",label).put("shapeMatches",shape).put("expectedCells",expected.sumOf{it.size}).put("mismatchPositions",positions).put("exactTextMatch",shape&&positions.length()==0))
        file.writeText(rows.toString())
    }
    /** Known model-quality limitation at the item text R2C2; every other frozen cell stays exact. */
    fun allOtherCellsExact(grid:TableGrid,expected:List<List<String>>):Boolean {
        val actual=grid.values()
        return actual.size==expected.size&&actual.all{it.size==expected.first().size}&&expected.indices.all{r->expected[r].indices.all{c->(r==1&&c==1)||actual[r][c]==expected[r][c]}}
    }
}
