package com.binaire.iptv

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** Tests de la logique pure du protocole Stalker/MAG (sans réseau ni Android). */
class StalkerProtocolTest {

    // ----- MAC -----
    @Test fun macCleanAndValidate() {
        assertEquals("00:1A:79:AB:CD:EF", StalkerProtocol.cleanMac("001a79abcdef"))
        assertEquals("00:1A:79:AB:CD:EF", StalkerProtocol.cleanMac("00:1a:79:ab:cd:ef"))
        assertNull(StalkerProtocol.cleanMac("001a79abcd"))     // trop court
        assertNull(StalkerProtocol.cleanMac("zz:1a:79:ab:cd:ef")) // non hex
        assertTrue(StalkerProtocol.isValidMac("00 1A 79 AB CD EF"))
    }

    @Test fun randomMacFormat() {
        val m = StalkerProtocol.randomMac(Random(42))
        assertTrue(m.startsWith("00:1A:79:"))
        assertTrue(Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$").matches(m))
    }

    // ----- Adresses -----
    @Test fun normalizePortalAddsSchemeAndPrefix() {
        assertEquals("stalker:http://host:8080/c", StalkerProtocol.normalizePortal("host:8080/c/"))
        assertEquals("stalker:https://x.y/c", StalkerProtocol.normalizePortal("https://x.y/c"))
    }

    @Test fun originParsing() {
        assertEquals("http://host:8080", StalkerProtocol.origin("http://host:8080/c"))
        assertEquals("https://a.b", StalkerProtocol.origin("https://a.b/stalker_portal/"))
    }

    @Test fun endpointCandidatesOrderedAndDistinct() {
        val c = StalkerProtocol.endpointCandidates("http://host:8080/c")
        assertEquals("http://host:8080/portal.php", c[0])
        assertTrue(c.contains("http://host:8080/server/load.php"))
        assertEquals(c.size, c.distinct().size)
    }

    @Test fun buildUrlAppendsJsHttpRequest() {
        assertEquals("http://h/portal.php?type=stb&action=handshake&JsHttpRequest=1-xml",
            StalkerProtocol.buildUrl("http://h/portal.php", "type=stb&action=handshake"))
    }

    // ----- Enveloppes -----
    @Test fun parseOkObjectAndArray() {
        assertEquals(StalkerProtocol.ResultKind.OK, StalkerProtocol.parse("""{"js":{"data":[]}}""").kind)
        assertEquals(StalkerProtocol.ResultKind.OK, StalkerProtocol.parse("""{"js":[1,2]}""").kind)
    }

    @Test fun parseBlockedAndErrorAndExpired() {
        assertEquals(StalkerProtocol.ResultKind.BLOCKED, StalkerProtocol.parse("""{"js":{"block":1}}""").kind)
        assertEquals(StalkerProtocol.ResultKind.ERROR, StalkerProtocol.parse("""{"js":"error"}""").kind)
        assertEquals(StalkerProtocol.ResultKind.BLOCKED, StalkerProtocol.parse("""{"js":{"error":"account expired"}}""").kind)
        assertEquals(StalkerProtocol.ResultKind.TOKEN_EXPIRED,
            StalkerProtocol.parse("""{"js":{"error":"Token is not valid"}}""").kind)
        assertEquals(StalkerProtocol.ResultKind.ERROR, StalkerProtocol.parse("not json").kind)
    }

    @Test fun tokenAndDataExtraction() {
        val p = StalkerProtocol.parse("""{"js":{"token":"ABC123"}}""")
        assertEquals("ABC123", StalkerProtocol.token(p.js))
        assertEquals(2, StalkerProtocol.dataArray(JSONObject("""{"data":[1,2]}""")).length())
        assertEquals(3, StalkerProtocol.dataArray(JSONArray("[1,2,3]")).length())
    }

    // ----- Pagination -----
    @Test fun paginationStops() {
        assertTrue(StalkerProtocol.paginationDone(0, 14, 0, 100, 1))     // page vide
        assertTrue(StalkerProtocol.paginationDone(5, 14, 5, 999, 1))     // dernière page (< perPage)
        assertTrue(StalkerProtocol.paginationDone(14, 14, 28, 28, 2))    // total atteint
        assertTrue(StalkerProtocol.paginationDone(14, 14, 14, 0, StalkerProtocol.PAGE_MAX)) // plafond
        assertFalse(StalkerProtocol.paginationDone(14, 14, 14, 100, 1))  // il reste des pages
    }

    // ----- Lien de lecture (sécurité) -----
    @Test fun normalizePlayUrlAcceptsHttp() {
        assertEquals("http://s/live/1.ts", StalkerProtocol.normalizePlayUrl("ffmpeg http://s/live/1.ts"))
        assertEquals("https://s/x?a=1&b=2", StalkerProtocol.normalizePlayUrl("auto https://s/x?a=1&b=2"))
        assertEquals("http://s/v.mp4", StalkerProtocol.normalizePlayUrl("http://s/v.mp4"))
    }

    @Test fun normalizePlayUrlRejectsDangerous() {
        assertNull(StalkerProtocol.normalizePlayUrl("ffmpeg rtp://239.0.0.1:1234"))
        assertNull(StalkerProtocol.normalizePlayUrl("rm -rf /"))
        assertNull(StalkerProtocol.normalizePlayUrl("file:///etc/passwd"))
        assertNull(StalkerProtocol.normalizePlayUrl(""))
        assertNull(StalkerProtocol.normalizePlayUrl(null))
    }

    @Test fun absUrlResolves() {
        assertEquals("http://h:8080/img/a.png",
            StalkerProtocol.absUrl("http://h:8080/c", "img/a.png"))
        assertEquals("https://cdn/x.png",
            StalkerProtocol.absUrl("http://h:8080/c", "https://cdn/x.png"))
        assertNull(StalkerProtocol.absUrl("http://h/c", null))
    }
}
