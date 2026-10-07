package eu.kanade.tachiyomi.ui.reader.setting

/**
 * 双页跨页时的左右顺序。按漫画保存在 `Manga.viewerFlags` 的独立位段里
 * （与阅读模式 0x07、屏幕方向 0x38 互不干扰），因此只对这本漫画生效，
 * 下次再读同一本漫画会自动沿用。
 *
 * [flagValue] 直接写进位段，故只能是 0 / 位段的唯一 1 bit。
 */
enum class DoublePageOrder(
    val flagValue: Int,
) {
    /** 默认顺序：跨页里页码小的在左（R2L 阅读方向下由 adapter 统一反序处理）。 */
    NORMAL(0x00000000),

    /** 交换顺序：跨页里页码小的在右。 */
    SWAPPED(0x00000040),
    ;

    fun flipped(): DoublePageOrder = if (this == NORMAL) SWAPPED else NORMAL

    companion object {
        /** 位段掩码：只占第 6 位（0x40），避开 ReadingMode(0x07) 与 ReaderOrientation(0x38)。 */
        const val MASK = 0x00000040

        fun fromFlagValue(value: Int): DoublePageOrder =
            if (value and MASK != 0) SWAPPED else NORMAL

        fun fromMangaViewerFlags(viewerFlags: Long): DoublePageOrder =
            fromFlagValue((viewerFlags and MASK.toLong()).toInt())
    }
}

/** 把 [order] 写回 `viewerFlags`，保留其余位段。 */
fun Long.withDoublePageOrder(order: DoublePageOrder): Long =
    (this and DoublePageOrder.MASK.toLong().inv()) or
        (order.flagValue.toLong() and DoublePageOrder.MASK.toLong())
