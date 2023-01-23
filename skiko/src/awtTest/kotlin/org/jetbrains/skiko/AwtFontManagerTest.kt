package org.jetbrains.skiko

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.jetbrains.skia.Data
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.Typeface
import org.jetbrains.skia.tests.makeFromResource
import org.junit.Test
import kotlin.io.path.createTempFile
import kotlin.io.path.writeBytes
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class AwtFontManagerTest {

    // Since Arial is not available on Linux, we need a substitute that
    // works at least on Ubuntu
    private val aFontName = when (hostOs) {
        OS.Linux -> "Liberation Sans"
        else -> "Arial"
    }

    private val systemFontProvider = FakeSystemFontProvider().apply {
        addEmptyFontFamily(aFontName).also {
            it.addTypeface(Typeface.makeFromName(aFontName, FontStyle.NORMAL))
            it.addTypeface(Typeface.makeFromName(aFontName, FontStyle.ITALIC))
            it.addTypeface(Typeface.makeFromName(aFontName, FontStyle.BOLD))
            it.addTypeface(Typeface.makeFromName(aFontName, FontStyle.BOLD_ITALIC))
        }
        addEmptyFontFamily("Potato Sans")
        addEmptyFontFamily("Walrus Display")
    }

    private val customTypefaceCache = TestTypefaceCache()

    private val fontManager = AwtFontManager(systemFontProvider, customTypefaceCache)

    @Test
    fun `should be able to find regular fonts`() = runTest {
        val typeface = fontManager.getTypefaceOrNull(aFontName, FontStyle.NORMAL)
        assertNotNull(typeface, "Font must exist")
        assertEquals(aFontName, typeface.familyName, "Font family name must match")
        assertFalse(typeface.isBold, "Font must not be bold")
        assertFalse(typeface.isItalic, "Font must not be italic")
    }

    @Test
    fun `should be able to find bold fonts`() = runTest {
        val typeface = fontManager.getTypefaceOrNull(aFontName, FontStyle.BOLD)
        assertNotNull(typeface, "Font must exist")
        assertEquals(aFontName, typeface.familyName, "Font family name must match")
        assertTrue(typeface.isBold, "Font must be bold")
        assertFalse(typeface.isItalic, "Font must not be italic")
    }

    @Test
    fun `should be able to find italic fonts`() = runTest {
        val typeface = fontManager.getTypefaceOrNull(aFontName, FontStyle.ITALIC)
        assertNotNull(typeface, "Font must exist")
        assertEquals(aFontName, typeface.familyName, "Font family name must match")
        assertFalse(typeface.isBold, "Font must not be bold")
        assertTrue(typeface.isItalic, "Font must be italic")
    }

    @Test
    fun `should be able to find bold italic fonts`() = runTest {
        val typeface = fontManager.getTypefaceOrNull(aFontName, FontStyle.BOLD_ITALIC)
        assertNotNull(typeface, "Font must exist")
        assertEquals(aFontName, typeface.familyName, "Font family name must match")
        assertTrue(typeface.isBold, "Font must be bold")
        assertTrue(typeface.isItalic, "Font must be italic")
    }

    @Test
    fun `should not be able to find non-existent fonts`() = runTest {
        val typeface = fontManager.getTypefaceOrNull("XXXYYY745", FontStyle.NORMAL)
        assertNull(typeface, "Font must not match (doesn't exist!)")
    }

    @Test
    fun `should not find any custom fonts on a new instance`() {
        assertTrue(fontManager.customFamilyNames().isEmpty())
    }

    @Test
    fun `should be able to register, and then find custom fonts from classpath resources`() = runTest {
        assertNull(
            fontManager.getTypefaceOrNull("Libre Barcode 39", FontStyle.NORMAL),
            "The font we're trying to add must not already be loaded"
        )

        fontManager.addCustomFontResource("LibreBarcode39-Regular.ttf")

        val typeface = fontManager.getTypefaceOrNull("Libre Barcode 39", FontStyle.NORMAL)!!
        assertContains(fontManager.familyNames(), typeface.familyName, "Custom font not listed in all fonts")
        assertContains(
            fontManager.customFamilyNames(),
            typeface.familyName,
            "Custom font not listed in custom fonts"
        )

        assertNotNull(typeface, "Font must have been loaded from resources")
        assertEquals("Libre Barcode 39", typeface.familyName, "Font family name must match")
        assertFalse(typeface.isBold, "Font must not be bold")
        assertFalse(typeface.isItalic, "Font must not be italic")
    }

    @Test
    fun `should be able to register, and then find custom fonts from a file`() = runTest {
        assertNull(
            fontManager.getTypefaceOrNull("Libre Barcode 39", FontStyle.NORMAL),
            "The font we're trying to add must not already be loaded"
        )

        val fontFile = createTempFile("awtfontmanagertest", "testfont")
        withContext(Dispatchers.IO) {
            val fontBytes = Thread.currentThread()
                .contextClassLoader
                .getResourceAsStream("LibreBarcode39-Regular.ttf")!!
                .readAllBytes()

            fontFile.writeBytes(fontBytes)
        }

        fontManager.addCustomFontFile(fontFile.toFile())

        assertTrue(fontManager.customFamilyNames().contains("Libre Barcode 39"), "Font must be registered")

        val typeface = fontManager.getTypefaceOrNull("Libre Barcode 39", FontStyle.NORMAL)
        assertNotNull(typeface, "Font must have been loaded from disk")
        assertEquals("Libre Barcode 39", typeface.familyName, "Font family name must match")
        assertFalse(typeface.isBold, "Font must not be bold")
        assertFalse(typeface.isItalic, "Font must not be italic")

        assertContains(fontManager.familyNames(), typeface.familyName, "Custom font not listed in all fonts")
        assertContains(
            fontManager.customFamilyNames(),
            typeface.familyName,
            "Custom font not listed in custom fonts"
        )
    }

    @Test
    fun `should be able to register, and then find custom fonts from a typeface`() = runTest {
        assertNull(
            fontManager.getTypefaceOrNull("Libre Barcode 39", FontStyle.NORMAL),
            "The font we're trying to add must not already be loaded"
        )

        val loadedTypeface = withContext(Dispatchers.IO) {
            val fontBytes = Thread.currentThread()
                .contextClassLoader
                .getResourceAsStream("LibreBarcode39-Regular.ttf")!!
                .readAllBytes()

            Typeface.makeFromData(Data.makeFromBytes(fontBytes))
        }

        fontManager.addCustomFontTypeface(loadedTypeface)

        val typeface = fontManager.getTypefaceOrNull("Libre Barcode 39", FontStyle.NORMAL)!!
        assertContains(fontManager.familyNames(), typeface.familyName, "Custom font not listed in all fonts")
        assertContains(
            fontManager.customFamilyNames(),
            typeface.familyName,
            "Custom font not listed in custom fonts"
        )

        assertNotNull(typeface, "Font must have been loaded from disk")
        assertEquals("Libre Barcode 39", typeface.familyName, "Font family name must match")
        assertFalse(typeface.isBold, "Font must not be bold")
        assertFalse(typeface.isItalic, "Font must not be italic")
    }

    @Test
    fun `should remove all custom fonts when clearing custom fonts`() = runTest {
        fontManager.addCustomFontResource("./fonts/Inter-V.ttf")

        fontManager.addCustomFontTypeface(Typeface.makeFromResource("./fonts/JetBrainsMono-Regular.ttf"))
        fontManager.addCustomFontResource("./fonts/JetBrainsMono-Italic.ttf")

        val fontFile = createTempFile("awtfontmanagertest", "testfont")
        withContext(Dispatchers.IO) {
            val fontBytes = Thread.currentThread()
                .contextClassLoader
                .getResourceAsStream("./fonts/JetBrainsMono-Bold.ttf")!!
                .readAllBytes()

            fontFile.writeBytes(fontBytes)
        }

        fontManager.addCustomFontFile(fontFile.toFile())

        assertEquals(2, fontManager.customFamilyNames().size)

        fontManager.clearCustomFonts()

        assertTrue(fontManager.customFamilyNames().isEmpty())
    }

    @Test
    fun `should do nothing when trying to remove a non-existent custom font`() {
        fontManager.removeCustomFontFamily("Bananananananananana*&^%$")
    }

    @Test
    fun `should contain at least the system font families`() = runTest {
        val families = fontManager.familyNames()
        assertEquals(families.size, 3, "Missing some system families")
    }

    @Test
    fun `should prefer custom to system fonts when existing`() = runTest {
        fontManager.addCustomFontTypeface(Typeface.makeFromName(aFontName, FontStyle.NORMAL))

        val typeface = fontManager.getTypefaceOrNull(aFontName, FontStyle.NORMAL)
        assertNotNull(typeface, "Should find typeface")
        assertEquals(aFontName, customTypefaceCache.lastGetName)
        assertNull(systemFontProvider.lastContainsName, "Should not get system fonts")
        assertNull(systemFontProvider.lastGetName, "Should not get system fonts")
    }

    @Test
    fun `should fall back to system fonts when existing custom fonts are missing the requested style`() = runTest {
        systemFontProvider.addEmptyFontFamily("JetBrains Mono")
        fontManager.addCustomFontResource("./fonts/JetBrainsMono-Regular.ttf")

        val typeface = fontManager.getTypefaceOrNull("JetBrains Mono", FontStyle.BOLD)
        assertEquals("JetBrains Mono", customTypefaceCache.lastGetName)

        assertNull(typeface, "System typeface should be missing in this test")
        assertEquals("JetBrains Mono", systemFontProvider.lastContainsName)
        assertEquals("JetBrains Mono", systemFontProvider.lastGetName)
    }
}
