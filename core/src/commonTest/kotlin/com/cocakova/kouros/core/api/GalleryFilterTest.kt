package com.cocakova.kouros.core.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GalleryFilterTest {
    private val pictures = listOf(MediaKind.IMAGE, MediaKind.IMAGE, MediaKind.VIDEO)
    private val musicRun = listOf(MediaKind.AUDIO, MediaKind.TEXT, MediaKind.TEXT)

    @Test fun oneKindNeedsNoChips() {
        assertEquals(emptyList(), GalleryFilter.counts(pictures))
        assertEquals(GalleryFilter.ALL, GalleryFilter.initial(pictures))
        assertEquals(emptyList(), GalleryFilter.counts(emptyList()))
    }

    @Test fun theGroupsThatAreThereAreOffered() {
        assertEquals(
            listOf(GalleryFilter.PICTURES to 3, GalleryFilter.AUDIO to 1, GalleryFilter.NOTES to 2, GalleryFilter.ALL to 6),
            GalleryFilter.counts(pictures + musicRun),
        )
    }

    @Test fun itOpensOnThePicturesWhenThereAreAny() {
        assertEquals(GalleryFilter.PICTURES, GalleryFilter.initial(pictures + musicRun))
        assertEquals(GalleryFilter.PICTURES, GalleryFilter.initial(listOf(MediaKind.IMAGE) + musicRun))
    }

    @Test fun withNoPicturesItOpensOnWhateverThereIsMostOf() {
        assertEquals(GalleryFilter.NOTES, GalleryFilter.initial(musicRun))
        assertEquals(GalleryFilter.AUDIO, GalleryFilter.initial(listOf(MediaKind.AUDIO, MediaKind.AUDIO, MediaKind.TEXT)))
    }

    @Test fun everyKindLandsInExactlyOneGroup() {
        for (k in MediaKind.entries) {
            val groups = listOf(GalleryFilter.PICTURES, GalleryFilter.AUDIO, GalleryFilter.NOTES).filter { it.accepts(k) }
            assertEquals(1, groups.size, "$k belongs to $groups")
            assertTrue(GalleryFilter.ALL.accepts(k))
        }
    }
}
