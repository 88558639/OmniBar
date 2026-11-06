package com.omnibar.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.omnibar.app.config.IndicatorConfig
import com.omnibar.app.model.IndicatorState
import com.omnibar.app.model.WifiPhase
import com.omnibar.app.render.IndicatorRenderer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BadgeCompareSheetTest {
    @Test fun sheet() {
        val cases = listOf(
            "5G 文字" to IndicatorState.demo.copy(wifi = WifiPhase.OFF),
            "飞行模式" to IndicatorState.demo.copy(wifi = WifiPhase.OFF, airplane = true),
            "VPN 盾牌" to IndicatorState.demo.copy(vpn = true)
        )
        val cell = 340
        val sheet = Bitmap.createBitmap(cell * cases.size, cell + 46, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(Color.WHITE)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 26f; color = Color.BLACK; textAlign = Paint.Align.CENTER
        }
        cases.forEachIndexed { index, (label, state) ->
            val left = index * cell
            val icon = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
            IndicatorRenderer().draw(Canvas(icon), 300f, 300f, state, IndicatorConfig(), Color.BLACK)
            canvas.drawBitmap(icon, left + 20f, 10f, null)
            canvas.drawText(label, left + cell / 2f, cell + 20f, text)
        }
        val out = File("../DevDoc/qa/badges-compare.png")
        out.parentFile!!.mkdirs()
        out.outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
