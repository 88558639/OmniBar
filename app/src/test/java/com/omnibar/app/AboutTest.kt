package com.omnibar.app

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.omnibar.app.ui.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AboutTest {
    @Test fun footerIsChangelogAndHasNoAuthorLink() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            fun descendants(view: View): List<View> = listOf(view) +
                if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
                else emptyList()
            val buttons = descendants(activity.findViewById(android.R.id.content)).filterIsInstance<Button>()
            val footer = buttons.last()
            assertEquals("更新日志", footer.text.toString())
            footer.performClick()

            val dialog = ShadowDialog.getLatestDialog()
            assertNotNull("The changelog sheet did not open", dialog)
            val texts = descendants(dialog.findViewById(android.R.id.content))
                .filterIsInstance<TextView>().map { it.text.toString() }
            // The changelog carries the recent versions.
            assertTrue("The changelog text is missing", texts.any { it.contains("2026") })
            // No author link remains.
            assertFalse("The author link is still present", texts.any { it.contains("作者主页") })
            assertFalse("The coolapk link is still present", texts.any { it.contains("coolapk") })
            // No close button — the sheet dismisses on outside tap.
            assertTrue("A close button is still present",
                descendants(dialog.findViewById(android.R.id.content)).filterIsInstance<Button>().none {
                    it.text.toString() == "关闭"
                })
        } finally { controller.pause().stop().destroy() }
    }
}
