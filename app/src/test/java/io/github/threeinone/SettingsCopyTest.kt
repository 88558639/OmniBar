package io.github.threeinone

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.github.threeinone.ui.MainActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The explanatory notes on the settings page.
 *
 * They are read on a phone, so every item gets its own line and its own number:
 * a colon or a semicolon in the text used to run several sentences together.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsCopyTest {

    private fun descendants(v: View): List<View> = listOf(v) +
        if (v is ViewGroup) (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) }
        else emptyList()

    @Test fun everyExplanationIsANumberedList() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val texts = descendants(controller.get().findViewById(android.R.id.content))
                .filterIsInstance<TextView>()
                .map { it.text.toString() }
            val explanations = texts.filter { it.contains("\n") }
            assertTrue("No explanation found", explanations.size >= 2)
            for (explanation in explanations) {
                val lines = explanation.split("\n").filter { it.isNotBlank() }
                // A line that ends with a colon is the heading of the list.
                var expected = 1
                for (line in lines) {
                    if (line.endsWith("：")) continue
                    assertTrue("Unnumbered line: \"$line\"", line.startsWith("$expected."))
                    expected++
                }
                assertTrue("The list is empty: \"$explanation\"", expected > 1)
            }
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun theFollowSystemColourSentenceIsGone() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val texts = descendants(controller.get().findViewById(android.R.id.content))
                .filterIsInstance<TextView>()
                .map { it.text.toString() }
            assertFalse(texts.any { it.contains("其余跟随系统颜色") })
            // The whole row opens the colour picker — the swatch on the left and the
            // hex value on the right alike — so the note must not point at one side.
            assertFalse(texts.any { it.contains("左侧色块上点击") })
            assertTrue(texts.any { it.contains("点击整行") })
        } finally { controller.pause().stop().destroy() }
    }
}
