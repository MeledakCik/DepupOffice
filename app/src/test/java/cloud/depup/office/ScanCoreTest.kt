package cloud.depup.office

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanCoreTest {
    @Test
    fun fromPixelsKeepsOriginalPixelsAndCalculatesGrayscale() {
        val color = 0xFF336699.toInt()

        val small = ScanCore.fromPixels(intArrayOf(color), 1, 1)

        assertEquals(1, small.w)
        assertEquals(1, small.h)
        assertEquals(color, small.argb.single())
        assertEquals((0x33 * 299 + 0x66 * 587 + 0x99 * 114) / 1000, small.g.single())
    }

    @Test
    fun detectsLargeRectangularPaperWithinTheFrame() {
        val w = 120
        val h = 160
        val pixels = IntArray(w * h) { 0xFF202020.toInt() }
        for (y in 18..141) for (x in 15..104) pixels[y * w + x] = 0xFFFFFFFF.toInt()

        val crop = ScanCore.detectCrop(ScanCore.fromPixels(pixels, w, h))

        assertNotNull(crop)
        assertEquals(4, crop!!.size)
        assertTrue(crop[0] in 0.05f..0.25f)
        assertTrue(crop[1] in 0.03f..0.25f)
        assertTrue(crop[2] in 0.75f..0.95f)
        assertTrue(crop[3] in 0.75f..0.95f)
    }

    @Test
    fun ignoresTinyDarkObjectInsteadOfTreatingItAsAPage() {
        val w = 100
        val h = 100
        val pixels = IntArray(w * h) { 0xFFFFFFFF.toInt() }
        for (y in 45..54) for (x in 45..54) pixels[y * w + x] = 0xFF101010.toInt()

        assertNull(ScanCore.detectCrop(ScanCore.fromPixels(pixels, w, h)))
    }
}
