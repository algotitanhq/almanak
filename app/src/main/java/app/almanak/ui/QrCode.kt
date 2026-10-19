package app.almanak.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/** A QR code drawn as squares: dark modules in the theme's ink on a light plate, for scanner contrast. */
@Composable
internal fun QrCode(text: String, size: androidx.compose.ui.unit.Dp) {
    val matrix = remember(text) {
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 1))
    }
    Canvas(Modifier.size(size).background(Color(0xFFF2EDE4), RoundedCornerShape(6.dp))) {
        val cell = this.size.minDimension / matrix.width
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (matrix[x, y]) drawRect(Color(0xFF0E0D0B), topLeft = androidx.compose.ui.geometry.Offset(x * cell, y * cell), size = androidx.compose.ui.geometry.Size(cell + 0.5f, cell + 0.5f))
        }
    }
}
