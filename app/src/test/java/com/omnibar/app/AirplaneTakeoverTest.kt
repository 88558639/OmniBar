package com.omnibar.app

import android.content.Context
import android.view.View
import com.omnibar.app.hook.ColorOsAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Stands in for the framework icon views.
 *
 * The real ones implement `StatusIconDisplayable`, whose `getSlot()` returns the slot
 * name from the framework strings. The classes below are top level on purpose: a nested
 * class would keep `getSlot()` non-public, which is not what the framework does.
 */
open class SlotViewStub(context: Context, private val slot: String) : View(context) {
    fun getSlot(): String = slot
}

class AirplaneViewStub(context: Context) : SlotViewStub(context, "airplane")
class AlarmViewStub(context: Context) : SlotViewStub(context, "alarm_clock")
class BluetoothViewStub(context: Context) : SlotViewStub(context, "bluetooth")
class PlainViewStub(context: Context) : View(context)

/**
 * The native airplane icon has to be taken over, otherwise the status bar shows the
 * framework plane **and** the drawn one side by side.
 *
 * On ColorOS 17 the airplane icon is a `StatIconView` that reports the slot name
 * "airplane" (the framework string `status_bar_airplane_slot`). It sits outside the
 * `StatusIconContainer` the ignored-slot list applies to, so it is matched by that slot
 * name and hidden directly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AirplaneTakeoverTest {

    private fun context(): Context = RuntimeEnvironment.getApplication()

    @Test fun theAirplaneSlotIsTakenOver() {
        assertTrue(
            "The airplane slot must be taken over, otherwise two planes are drawn",
            "airplane" in ColorOsAdapter.TAKEOVER_SLOTS
        )
    }

    @Test fun theAirplaneSlotIsAlsoIgnoredByTheIconContainer() {
        assertTrue(
            "The airplane slot must be ignored by the StatusIconContainer as well",
            "airplane" in ColorOsAdapter.NETWORK_SLOTS
        )
    }

    @Test fun theOtherSystemIconsAreLeftAlone() {
        // The alarm clock and the bluetooth icon are native icons the user keeps —
        // only the network icons and the airplane are replaced by the indicator.
        assertFalse("alarm_clock" in ColorOsAdapter.TAKEOVER_SLOTS)
        assertFalse("bluetooth" in ColorOsAdapter.TAKEOVER_SLOTS)
        assertFalse("alarm_clock" in ColorOsAdapter.NETWORK_SLOTS)
        assertFalse("bluetooth" in ColorOsAdapter.NETWORK_SLOTS)
    }

    @Test fun theSlotNameIsReadFromTheIconView() {
        assertEquals("airplane", ColorOsAdapter.slotNameOf(AirplaneViewStub(context())))
        assertEquals("alarm_clock", ColorOsAdapter.slotNameOf(AlarmViewStub(context())))
        assertEquals("bluetooth", ColorOsAdapter.slotNameOf(BluetoothViewStub(context())))
    }

    @Test fun aViewWithoutASlotReportsAnEmptyName() {
        assertEquals("", ColorOsAdapter.slotNameOf(PlainViewStub(context())))
        assertFalse("" in ColorOsAdapter.TAKEOVER_SLOTS)
    }
}
