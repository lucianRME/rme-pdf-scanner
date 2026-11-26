package org.synapseworks.pageharbor.qr

import java.nio.ByteBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex

internal interface QrFrame : AutoCloseable {
    val buffer: ByteBuffer
    val width: Int; val height: Int
    val rowStride: Int; val pixelStride: Int
    val left: Int; val top: Int; val cropWidth: Int; val cropHeight: Int
    val rotation: Int
}

/** Shared work mutex also protects image import. No queued camera work or retained frame. */
internal class QrFrameProcessor(private val work: Mutex, private val controller: QrController,
    private val completed: (QrTicket,QrDecode)->Unit, private val decoder: QrDecoder = QrDecoder()) {
    fun analyze(frame: QrFrame, owner: String, generation: Long) {
        try {
            if(!work.tryLock())return
            try {
                val ticket=controller.frame(owner,generation) ?: return
                var outcome: QrDecode=QrDecode.NoCode
                try {
                    val check={if(!controller.currentFrame(ticket))throw CancellationException("QR scan ended")}
                    check()
                    val plane=QrLuma.plane(frame.buffer,frame.width,frame.height,frame.rowStride,frame.pixelStride,
                        frame.left,frame.top,frame.cropWidth,frame.cropHeight,frame.rotation,check)
                    outcome=if(plane==null)QrDecode.Invalid else decoder.decode(plane.first,plane.second,plane.third,check)
                } catch (_: CancellationException) { /* A stale frame cannot publish. */ }
                catch (_: Exception) { outcome=QrDecode.Invalid }
                finally { completed(ticket,outcome) }
            } finally { work.unlock() }
        } finally { frame.close() }
    }
}
