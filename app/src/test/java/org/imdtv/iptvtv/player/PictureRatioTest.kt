package org.imdtv.iptvtv.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PictureRatioTest {
    private val wide = 16f / 9f

    @Test fun unmarkedStandardDefinitionIsShownWidescreen() {
        assertEquals(wide, pictureRatio(704, 576, 1f), 0.001f)
        assertEquals(wide, pictureRatio(720, 576, 1f), 0.001f)
        assertEquals(wide, pictureRatio(720, 480, 1f), 0.001f)
        assertEquals(wide, pictureRatio(544, 576, 1f), 0.001f)
    }

    @Test fun highDefinitionIsLeftAlone() {
        assertEquals(wide, pictureRatio(1920, 1080, 1f), 0.001f)
        assertEquals(1280f / 720f, pictureRatio(1280, 720, 1f), 0.001f)
        assertEquals(3840f / 2160f, pictureRatio(3840, 2160, 1f), 0.001f)
    }

    @Test fun trulySquarePixelFourByThreeIsLeftAlone() = assertEquals(4f / 3f, pictureRatio(640, 480, 1f), 0.001f)

    @Test fun aShapeTheStreamStatesIsKept() {
        // 4:3 stored as 720x576 with its pixel shape given: 720 * 1.0667 / 576
        assertEquals(4f / 3f, pictureRatio(720, 576, 16f / 15f), 0.01f)
        assertEquals(wide, pictureRatio(720, 576, 64f / 45f), 0.01f)
    }

    @Test fun unknownSizeGivesNoShape() = assertEquals(0f, pictureRatio(0, 0, 1f), 0f)
}
