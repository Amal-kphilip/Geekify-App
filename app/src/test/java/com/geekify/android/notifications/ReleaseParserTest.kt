package com.geekify.android.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReleaseParserTest {
    @Test fun versionCodeMatchesReleaseWorkflowFormula() {
        assertEquals(10105, ReleaseParser.versionCodeFor("1.1.5"))
        assertEquals(10105, ReleaseParser.versionCodeFor("v1.1.5"))
        assertEquals(100, ReleaseParser.versionCodeFor("0.1.0"))
        assertNull(ReleaseParser.versionCodeFor("nightly"))
    }
    @Test fun parsesStableRelease() {
        val r = ReleaseParser.parse("""{"tag_name":"v1.1.5","html_url":"https://github.com/o/r/releases/tag/v1.1.5","draft":false,"prerelease":false}""")
        assertEquals(ReleaseInfo("1.1.5", 10105, "https://github.com/o/r/releases/tag/v1.1.5"), r)
    }
    @Test fun ignoresPrereleaseDraftAndGarbage() {
        assertNull(ReleaseParser.parse("""{"tag_name":"v2.0.0","html_url":"https://x","prerelease":true}"""))
        assertNull(ReleaseParser.parse("""{"tag_name":"v2.0.0","html_url":"https://x","draft":true}"""))
        assertNull(ReleaseParser.parse("not json"))
    }
}
