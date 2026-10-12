package fr.eva.cloudgallery.data.search

import fr.eva.cloudgallery.data.db.dao.FTS_DATES_SQL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Expression FTS sûre, libellés de date de l'index, correspondance des noms, historique. */
class SearchLogicTest {

    @Test
    fun matchExpression_prefixesEachWord_withImplicitAnd() {
        assertEquals("août* 2026*", ftsMatchExpression("Août 2026"))
        assertEquals("vacances*", ftsMatchExpression("  vacances  "))
        // Nom de fichier : découpé comme par le tokeniseur (« IMG_2026.jpg » -> img, 2026, jpg).
        assertEquals("img* 2026* jpg*", ftsMatchExpression("IMG_2026.jpg"))
    }

    @Test
    fun matchExpression_withoutWords_isNull() {
        assertNull(ftsMatchExpression(""))
        assertNull(ftsMatchExpression("   "))
        assertNull(ftsMatchExpression("\"*-:()^"))
    }

    @Test
    fun matchExpression_neverContainsFtsSyntax() {
        val hostile = listOf(
            "a\" OR \"b", "x* -y", "name:foo", "(a OR b)", "NEAR(a b)", "a AND b", "NOT a", "^start", "a NEAR/2 b", "'; DROP TABLE media; --",
        )
        for (input in hostile) {
            val expression = ftsMatchExpression(input) ?: continue
            // Seulement des mots en minuscules suivis de « * », séparés par des espaces.
            assertTrue(input, expression.split(' ').all { Regex("[\\p{L}\\p{N}]+\\*").matches(it) })
            assertFalse(input, Regex("\\b(AND|OR|NOT|NEAR)\\b").containsMatchIn(expression))
        }
        assertEquals("a* or* b*", ftsMatchExpression("a\" OR \"b"))
    }

    @Test
    fun matchExpression_limitsWordsAndLength() {
        val many = (1..20).joinToString(" ") { "m$it" }
        assertEquals(MAX_SEARCH_TOKENS, ftsMatchExpression(many)!!.split(' ').size)
        val long = "a".repeat(500)
        assertEquals(MAX_SEARCH_TOKEN_LENGTH + 1, ftsMatchExpression(long)!!.length)
    }

    @Test
    fun indexDateLabels_matchTheSqlOfTheIndex() {
        assertEquals(12, MONTH_LABELS.size)
        MONTH_LABELS.forEachIndexed { i, label ->
            assertTrue(label, FTS_DATES_SQL.contains("WHEN '%02d' THEN '%s'".format(i + 1, label)))
        }
        assertEquals(7, WEEKDAY_LABELS.size)
        WEEKDAY_LABELS.forEachIndexed { i, label ->
            assertTrue(label, FTS_DATES_SQL.contains("WHEN '$i' THEN '$label'"))
        }
        assertTrue(FTS_DATES_SQL.contains("'$VIDEO_LABEL'"))
        assertTrue(FTS_DATES_SQL.contains("'$PHOTO_LABEL'"))
    }

    @Test
    fun nameMatches_whenEveryWordStartsAWord_ignoringAccentsAndCase() {
        assertTrue(nameMatchesSearch("Vacances été 2025", "vac"))
        assertTrue(nameMatchesSearch("Vacances été 2025", "ETE vac"))
        assertTrue(nameMatchesSearch("Caméra", "camera"))
        assertFalse(nameMatchesSearch("Vacances", "cances"))
        assertFalse(nameMatchesSearch("Vacances été", "vac hiver"))
        assertFalse(nameMatchesSearch("Vacances", "  "))
    }

    @Test
    fun recentSearch_isMovedFirst_withoutDuplicates_andBounded() {
        assertEquals(listOf("plage", "août"), withRecentSearch(listOf("août"), "  plage "))
        // Même recherche (casse, accents, espaces) : remontée, pas dupliquée.
        assertEquals(listOf("Aout  2026".replace("  ", " "), "plage"), withRecentSearch(listOf("plage", "août 2026"), "Aout  2026"))
        val full = (1..MAX_RECENT_SEARCHES).map { "q$it" }
        val next = withRecentSearch(full, "new")
        assertEquals(MAX_RECENT_SEARCHES, next.size)
        assertEquals("new", next.first())
        assertFalse(next.contains("q$MAX_RECENT_SEARCHES"))
        // Saisie sans mot : historique inchangé.
        assertEquals(listOf("plage"), withRecentSearch(listOf("plage"), " -* "))
    }
}
