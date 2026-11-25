package com.notokens.monolog.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.WindowManager
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.Size
import com.journeyapps.barcodescanner.ViewfinderView
import com.notokens.monolog.R
import kotlin.math.roundToInt

class PairingScannerActivity : CaptureActivity() {
    override fun initializeContent(): DecoratedBarcodeView {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.pairing_scanner)
        val scanner = findViewById<DecoratedBarcodeView>(R.id.pairing_scanner)
        val configuration = resources.configuration
        val sideDp = minOf(320f, configuration.screenWidthDp * 0.76f, configuration.screenHeightDp * 0.58f)
        val side = (sideDp * resources.displayMetrics.density).roundToInt()
        scanner.barcodeView.framingRectSize = Size(side, side)
        scanner.viewFinder.setLaserVisibility(false)
        return scanner
    }
}

class PairingScanGuide(context: Context, attrs: AttributeSet) : ViewfinderView(context, attrs) {
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2 * resources.displayMetrics.density
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val frame = framingRect ?: return
        val radius = 12 * resources.displayMetrics.density
        canvas.drawRoundRect(frame.left.toFloat(), frame.top.toFloat(), frame.right.toFloat(),
            frame.bottom.toFloat(), radius, radius, outline)
    }
}
