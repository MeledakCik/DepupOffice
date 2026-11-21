package cloud.depup.office

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Catatan (bagian murni): format simpan JSON, ubah teks ↔ daftar centang, ringkasan untuk widget. */
class NotesTest {
    private fun sample() = Note(
        42L, "Belanja", "", mutableListOf(NoteItem("Susu", true), NoteItem("Roti"), NoteItem("  ")),
        checklist = true, color = 2, pinned = true, created = 1000L, updated = 2000L
    )

    @Test
    fun codecRoundTrip() {
        val back = NoteCodec.decode(NoteCodec.encode(listOf(sample(), Note(7L, body = "Halo\n\"kutip\" & ✓ emoji 😀"))))
        assertEquals(2, back.size)
        val a = back[0]
        assertEquals(42L, a.id); assertEquals("Belanja", a.title); assertTrue(a.checklist)
        assertEquals(listOf(NoteItem("Susu", true), NoteItem("Roti"), NoteItem("  ")), a.items)
        assertEquals(2, a.color); assertTrue(a.pinned); assertEquals(1000L, a.created); assertEquals(2000L, a.updated)
        assertEquals("Halo\n\"kutip\" & ✓ emoji 😀", back[1].body)
        assertFalse(back[1].checklist)
    }

    @Test
    fun decodeSkipsBrokenEntriesAndClampsColor() {
        val json = """{"v":1,"notes":[{"t":"tanpa id"},{"id":5,"t":"ok","k":99,"i":[{"x":"a","d":true},"rusak"]},7]}"""
        val l = NoteCodec.decode(json)
        assertEquals(1, l.size)
        assertEquals(5L, l[0].id)
        assertEquals(NoteColors.ALL.size - 1, l[0].color)
        assertEquals(listOf(NoteItem("a", true)), l[0].items)
        assertTrue(NoteCodec.decode("""{"v":1}""").isEmpty())
    }

    @Test
    fun parseLineRecognisesBoxesAndBullets() {
        assertEquals(NoteItem("Susu", true), Note.parseLine("- [x] Susu"))
        assertEquals(NoteItem("Roti", true), Note.parseLine("[X] Roti"))
        assertEquals(NoteItem("Telur", false), Note.parseLine("* [ ] Telur"))
        assertEquals(NoteItem("Kopi", false), Note.parseLine("• Kopi"))
        assertEquals(NoteItem("Teh manis", false), Note.parseLine("  Teh manis  "))
        assertEquals(NoteItem("-5 derajat", false), Note.parseLine("-5 derajat"))   // minus angka bukan poin
    }

    @Test
    fun textChecklistConversion() {
        val n = Note(1L, body = "- [x] Susu\n\n• Roti\nTelur")
        n.toChecklist()
        assertTrue(n.checklist); assertEquals("", n.body)
        assertEquals(listOf(NoteItem("Susu", true), NoteItem("Roti"), NoteItem("Telur")), n.items)
        n.toText()
        assertFalse(n.checklist); assertTrue(n.items.isEmpty())
        assertEquals("Susu\nRoti\nTelur", n.body)
        // teks kosong → daftar dengan satu item kosong (siap diketik)
        val e = Note(2L).apply { toChecklist() }
        assertEquals(listOf(NoteItem("")), e.items)
    }

    @Test
    fun blankProgressAndPlainText() {
        val n = sample()
        assertEquals(1 to 2, n.progress())   // item kosong tidak dihitung
        assertFalse(n.blank)
        assertTrue(Note(3L, checklist = true, items = mutableListOf(NoteItem(" "))).blank)
        assertTrue(Note(4L, body = " \n ").blank)
        assertEquals("Belanja\n\n- [x] Susu\n- [ ] Roti", n.plainText())
        assertEquals("Isi saja", Note(5L, body = "  Isi saja \n").plainText())
    }

    @Test
    fun copyIsDeep() {
        val a = sample()
        val b = a.copy()
        b.items[0].done = false; b.items.add(NoteItem("baru")); b.title = "lain"
        assertTrue(a.items[0].done); assertEquals(3, a.items.size); assertEquals("Belanja", a.title)
    }

    @Test
    fun headlineAndSummaryForWidgets() {
        val n = sample()
        assertEquals("Belanja", NoteViews.headline(n))
        assertEquals("1/2 selesai · Roti", NoteViews.summary(n))
        val untitled = Note(9L, checklist = true, items = mutableListOf(NoteItem("Susu"), NoteItem("Roti"), NoteItem("Teh", true)))
        assertEquals("Susu", NoteViews.headline(untitled))
        assertEquals("1/3 selesai · Roti", NoteViews.summary(untitled))
        val done = Note(10L, "Selesai", checklist = true, items = mutableListOf(NoteItem("a", true)))
        assertEquals("Semua selesai (1)", NoteViews.summary(done))
        val text = Note(11L, body = "Baris pertama\n\n  baris   kedua\nketiga")
        assertEquals("Baris pertama", NoteViews.headline(text))
        assertEquals("baris kedua ketiga", NoteViews.summary(text))
        assertEquals("Catatan kosong", NoteViews.headline(Note(12L)))
        assertTrue(NoteViews.matches(text, "KEDUA"))
        assertFalse(NoteViews.matches(text, "keempat"))
    }
}
