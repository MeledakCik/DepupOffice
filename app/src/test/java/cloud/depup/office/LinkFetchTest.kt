package cloud.depup.office

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkFetchTest {
    @Test
    fun extractsUrlAndTitleFromShareText() {
        val t = "DDL_DML.pdf https://share.google/3lwcufoNQdQ4Jvv01"
        val u = LinkFetch.extractUrl(t)
        assertEquals("https://share.google/3lwcufoNQdQ4Jvv01", u)
        assertEquals("DDL_DML.pdf", LinkFetch.titleOf(t, u!!))
        assertEquals("https://x.id/a.pdf", LinkFetch.extractUrl("Lihat (https://x.id/a.pdf)."))
        assertNull(LinkFetch.extractUrl("http://tidak-aman.id/a.pdf"))
        assertNull(LinkFetch.extractUrl("tanpa link"))
    }

    @Test
    fun rewritesGoogleDriveAndDocs() {
        assertEquals("https://drive.usercontent.google.com/download?id=1AbCdEfGhIjK&export=download&confirm=t",
            LinkFetch.directUrl("https://drive.google.com/file/d/1AbCdEfGhIjK/view?usp=sharing"))
        assertEquals("https://drive.usercontent.google.com/download?id=1AbCdEfGhIjK&export=download&confirm=t",
            LinkFetch.directUrl("https://drive.google.com/open?id=1AbCdEfGhIjK"))
        assertEquals("https://docs.google.com/document/d/1AbCdEfGhIjK/export?format=pdf",
            LinkFetch.directUrl("https://docs.google.com/document/d/1AbCdEfGhIjK/edit"))
        assertEquals("https://docs.google.com/presentation/d/1AbCdEfGhIjK/export/pdf",
            LinkFetch.directUrl("https://docs.google.com/presentation/d/1AbCdEfGhIjK/edit#slide=1"))
        assertEquals("https://lms.unikom.ac.id/a.pdf", LinkFetch.directUrl("https://lms.unikom.ac.id/a.pdf"))
    }

    @Test
    fun sniffsByContentNotHeader() {
        assertEquals(LinkFetch.Kind.PDF, LinkFetch.sniff("%PDF-1.7\n...".toByteArray(), "x"))
        assertEquals(LinkFetch.Kind.PDF, LinkFetch.sniff("﻿\n%PDF-1.4".toByteArray(), "x"))
        assertNull(LinkFetch.sniff("<!DOCTYPE html><html>login</html>".toByteArray(), "a.pdf"))
        val zip = byteArrayOf(0x50, 0x4B, 3, 4) + "....word/document.xml".toByteArray()
        assertEquals(LinkFetch.Kind.DOCX, LinkFetch.sniff(zip, "x"))
    }

    @Test
    fun safeFileNames() {
        assertEquals("Materi SQL.pdf", LinkFetch.fileName("attachment; filename=\"Materi SQL.pdf\"", "https://a.id/download", ""))
        assertEquals("Bab 1.pdf", LinkFetch.fileName("attachment; filename*=UTF-8''Bab%201.pdf", "https://a.id/x", ""))
        assertEquals("DDL_DML.pdf", LinkFetch.fileName(null, "https://drive.usercontent.google.com/download", "DDL_DML.pdf"))
        assertEquals("_etc_passwd", LinkFetch.fileName("attachment; filename=\"../etc/passwd\"", "https://a.id/x", "").replace("..", ""))
    }
}
