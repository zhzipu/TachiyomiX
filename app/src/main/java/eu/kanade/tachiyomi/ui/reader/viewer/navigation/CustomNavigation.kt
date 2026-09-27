package eu.kanade.tachiyomi.ui.reader.viewer.navigation

import android.graphics.RectF
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation

/**
 * The action that can be assigned to a custom tap zone.
 */
enum class CustomTapAction(val region: ViewerNavigation.NavigationRegion) {
    MENU(ViewerNavigation.NavigationRegion.MENU),
    PREV(ViewerNavigation.NavigationRegion.PREV),
    NEXT(ViewerNavigation.NavigationRegion.NEXT),
    NONE(ViewerNavigation.NavigationRegion.NONE),
    ;

    companion object {
        private val BY_ORDINAL = entries.associateBy { it.ordinal }

        fun fromOrdinal(ordinal: Int): CustomTapAction = BY_ORDINAL[ordinal] ?: MENU
    }
}

/**
 * Helpers to parse/serialize the 9-zone custom layout, which is stored
 * as a comma-separated list of 9 [CustomTapAction] ordinals.
 */
object CustomTapZones {
    const val COUNT = 9

    /** Default layout that mimics the "Right and Left" navigation. */
    val pagerDefault: List<CustomTapAction> = listOf(
        CustomTapAction.NEXT, CustomTapAction.MENU, CustomTapAction.PREV,
        CustomTapAction.NEXT, CustomTapAction.MENU, CustomTapAction.PREV,
        CustomTapAction.NEXT, CustomTapAction.MENU, CustomTapAction.PREV,
    )

    /** Default layout that mimics the "L shaped" navigation. */
    val webtoonDefault: List<CustomTapAction> = listOf(
        CustomTapAction.PREV, CustomTapAction.PREV, CustomTapAction.PREV,
        CustomTapAction.PREV, CustomTapAction.MENU, CustomTapAction.NEXT,
        CustomTapAction.NEXT, CustomTapAction.NEXT, CustomTapAction.NEXT,
    )

    fun parse(value: String): List<CustomTapAction> {
        val zones = value.split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .map { CustomTapAction.fromOrdinal(it) }
        return (zones + List(COUNT) { CustomTapAction.MENU }).take(COUNT)
    }

    fun serialize(zones: List<CustomTapAction>): String =
        (zones + List(COUNT) { CustomTapAction.MENU })
            .take(COUNT)
            .joinToString(",") { it.ordinal.toString() }
}

/**
 * Visualization of a custom tap zone layout.
 * The screen is divided into a 3x3 grid, each zone can be independently
 * assigned an action (menu, previous, next, left, right).
 * +-----+-----+-----+
 * |  0  |  1  |  2  |
 * +-----+-----+-----+
 * |  3  |  4  |  5  |
 * +-----+-----+-----+
 * |  6  |  7  |  8  |
 * +-----+-----+-----+
 */
class CustomNavigation(zones: List<CustomTapAction>) : ViewerNavigation() {

    override var regionList: List<Region> = zones.mapIndexedNotNull { index, action ->
        val row = index / 3
        val col = index % 3
        Region(
            rectF = RectF(col / 3f, row / 3f, (col + 1) / 3f, (row + 1) / 3f),
            type = action.region,
        )
    }

    // Inversion does not apply to user-defined custom zones.
    override fun getRegions(): List<Region> = regionList
}
