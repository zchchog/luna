package tv.luna.player.data.source

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoHeightTest {
    private fun h(s: String) = videoHeight(Json.parseToJsonElement(s))

    @Test fun numberOrString() { assertEquals(1080, h("""{"width":1920,"height":1080}""")); assertEquals(2160, h("""{"height":"2160"}""")) }

    @Test fun missingOrOddShapes() { assertNull(h("""[]""")); assertNull(h("""{"height":0}""")); assertNull(h("""{}""")); assertNull(videoHeight(null)) }
}
