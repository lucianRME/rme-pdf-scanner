package org.synapseworks.pageharbor.ocr

/** An advisory for photographs with mostly dim content in the central page area. */
internal fun looksDimForOcr(width: Int, height: Int, argb: IntArray): Boolean {
    if (width < 16 || height < 16 || width.toLong() * height != argb.size.toLong()) return false

    var sampled = 0
    var visible = 0
    var dim = 0
    for (y in height / 8 until height - height / 8) {
        for (x in width / 8 until width - width / 8) {
            val pixel = argb[y * width + x]
            sampled++
            if (pixel ushr 24 < 230) continue
            visible++
            val red = (pixel ushr 16) and 255
            val green = (pixel ushr 8) and 255
            val blue = pixel and 255
            val luminance = (54 * red + 183 * green + 19 * blue) / 256
            if (luminance < 150) dim++
        }
    }

    // A bright page with dark type, a dark border or transparent regions should not warn.
    return visible * 10 >= sampled * 9 && dim * 5 >= visible * 4
}
