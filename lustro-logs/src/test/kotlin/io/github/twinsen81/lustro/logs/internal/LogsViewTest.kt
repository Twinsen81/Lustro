package io.github.twinsen81.lustro.logs.internal

import org.junit.Assert.assertTrue
import org.junit.Test

class LogsViewTest {
    @Test
    fun `the Fatal chip says that it shows level F, and that a crash and Log_wtf are at level E`() {
        val chip = Regex("""<button[^>]*id="logs-fatal"[^>]*>""").find(LogsView.HTML)!!.value
        val title = Regex("""title="([^"]*)"""").find(chip)!!.groupValues[1]

        assertTrue(title, title.contains("level F"))
        assertTrue(title, title.contains("Log.wtf() are at level E"))
        assertTrue(title, title.contains("can't show a crash that ends its own process"))
    }
}

