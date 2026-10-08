package org.synapseworks.pageharbor.document.targetsize

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal sealed interface TargetSizePdfInspectionResult {
    data class Success(val facts: TargetSizePdfSourceFacts) : TargetSizePdfInspectionResult
    data object Encrypted : TargetSizePdfInspectionResult
    data object Malformed : TargetSizePdfInspectionResult
    data object Unreadable : TargetSizePdfInspectionResult
}

/** Conservative local-only inspection. Inputs that cannot be proven reconstructable are rejected. */
internal class TargetSizePdfInputInspector(
    context: Context,
    private val openInputStream: (Uri) -> InputStream? = context.contentResolver::openInputStream,
) {
    init {
        PDFBoxResourceLoader.init(context.applicationContext ?: context)
    }

    suspend fun inspect(uri: Uri): TargetSizePdfInspectionResult {
        currentCoroutineContext().ensureActive()
        val input = try {
            openInputStream(uri) ?: return TargetSizePdfInspectionResult.Unreadable
        } catch (_: IOException) {
            return TargetSizePdfInspectionResult.Unreadable
        } catch (_: SecurityException) {
            return TargetSizePdfInspectionResult.Unreadable
        } catch (_: IllegalArgumentException) {
            return TargetSizePdfInspectionResult.Unreadable
        }
        val document = try {
            input.use(PDDocument::load)
        } catch (_: InvalidPasswordException) {
            return TargetSizePdfInspectionResult.Encrypted
        } catch (error: CancellationException) {
            throw error
        } catch (_: IOException) {
            return TargetSizePdfInspectionResult.Malformed
        } catch (_: RuntimeException) {
            return TargetSizePdfInspectionResult.Malformed
        }
        return document.use { pdf ->
            currentCoroutineContext().ensureActive()
            if (pdf.isEncrypted) return@use TargetSizePdfInspectionResult.Encrypted
            if (pdf.numberOfPages <= 0) return@use TargetSizePdfInspectionResult.Malformed
            try {
                var allPagesContainRasterImage = true
                var hasTextOrVectorContent = false
                var hasAnnotation = false
                for (pageIndex in 0 until pdf.numberOfPages) {
                    currentCoroutineContext().ensureActive()
                    val page = pdf.getPage(pageIndex)
                    val xObjectNames = page.resources?.xObjectNames.orEmpty()
                    val xObjects = xObjectNames.mapNotNull { name ->
                        runCatching { page.resources.getXObject(name) }.getOrNull()
                    }
                    if (xObjects.none { it is PDImageXObject }) {
                        allPagesContainRasterImage = false
                    }
                    if (xObjects.any { it !is PDImageXObject }) {
                        hasTextOrVectorContent = true
                    }
                    val parser = PDFStreamParser(page)
                    parser.parse()
                    if (parser.tokens.filterIsInstance<Operator>().any { operator ->
                            operator.name !in IMAGE_PAGE_OPERATORS
                        }
                    ) {
                        hasTextOrVectorContent = true
                    }
                    if (page.annotations.isNotEmpty()) hasAnnotation = true
                }
                val catalog = pdf.documentCatalog
                val form = catalog.acroForm
                val hasInteractiveForm = form != null && form.fields.isNotEmpty()
                val hasSignature = pdf.signatureDictionaries.isNotEmpty()
                val names = catalog.cosObject.getDictionaryObject(COSName.NAMES) as? COSDictionary
                val hasEmbeddedFile = names?.getDictionaryObject(COSName.EMBEDDED_FILES) != null
                TargetSizePdfInspectionResult.Success(
                    TargetSizePdfSourceFacts(
                        pageCount = pdf.numberOfPages,
                        allPagesContainRasterImage = allPagesContainRasterImage,
                        hasTextOrVectorContent = hasTextOrVectorContent,
                        hasInteractiveForm = hasInteractiveForm,
                        hasSignature = hasSignature,
                        hasAnnotation = hasAnnotation,
                        hasEmbeddedFile = hasEmbeddedFile,
                    ),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: IOException) {
                TargetSizePdfInspectionResult.Malformed
            } catch (_: RuntimeException) {
                TargetSizePdfInspectionResult.Malformed
            }
        }
    }

    private companion object {
        val IMAGE_PAGE_OPERATORS = setOf(
            "q",
            "Q",
            "cm",
            "Do",
            "gs",
            "BMC",
            "BDC",
            "EMC",
            "MP",
            "DP",
        )
    }
}

private fun Iterable<COSName>?.orEmpty(): Iterable<COSName> = this ?: emptyList()
