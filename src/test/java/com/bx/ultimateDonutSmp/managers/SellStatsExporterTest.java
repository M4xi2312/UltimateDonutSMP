package com.bx.ultimateDonutSmp.managers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SellStatsExporterTest {

    @Test
    void escapesMarkupInNames() {
        assertEquals("&lt;img src=x onerror=&quot;alert(1)&quot;&gt;",
                SellStatsExporter.escapeHtml("<img src=x onerror=\"alert(1)\">"));
        assertEquals("Tom &amp; Jerry&#39;s", SellStatsExporter.escapeHtml("Tom & Jerry's"));
    }

    @Test
    void leavesOrdinaryNamesAlone() {
        assertEquals("BeestoXd_01", SellStatsExporter.escapeHtml("BeestoXd_01"));
        assertEquals("", SellStatsExporter.escapeHtml(null));
    }

    @Test
    void urlSegmentCannotBreakOutOfAQuotedHandler() {
        // the fallback head URL sits inside onerror="this.src='...'", where HTML escaping alone
        // is undone before the script runs
        String segment = SellStatsExporter.urlSegment("a'; alert(1); '\" b");

        assertFalse(segment.contains("'"));
        assertFalse(segment.contains("\""));
        assertFalse(segment.contains(" "));
        assertEquals("BeestoXd_01", SellStatsExporter.urlSegment("BeestoXd_01"));
        assertEquals("", SellStatsExporter.urlSegment(null));
    }
}
