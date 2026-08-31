package dev.octoshrimpy.quik.util

import org.junit.Assert.assertEquals
import org.junit.Test

class UtilsTest {
    @Test
    fun sourceClassNameUsesKotlinPackageInsteadOfApplicationId() {
        assertEquals(
            "dev.octoshrimpy.quik.feature.widget.WidgetProvider",
            sourceClassName(".feature.widget.WidgetProvider")
        )
    }
}
