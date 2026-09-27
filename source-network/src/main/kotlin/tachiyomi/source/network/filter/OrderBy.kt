package tachiyomi.source.network.filter

import android.content.Context
import eu.kanade.tachiyomi.source.model.Filter
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR

/**
 * 网络图源的排序项，与本地图源保持一致的「标题 / 日期」两列。
 *
 * 直接复用 `i18n` 里本地图源已有的那几条文案，不额外新增字符串。
 *
 * 默认项是 [DateDescending]（日期、降序），也就是「从新到旧」——
 * 本图源只保留「浏览」一个列表（见 `NetworkSource.supportsLatest`），
 * 列表的默认顺序就由这里决定。
 */
sealed class OrderBy(context: Context, selection: Selection) : Filter.Sort(
    context.stringResource(MR.strings.local_filter_order_by),
    arrayOf(context.stringResource(MR.strings.title), context.stringResource(MR.strings.date)),
    selection,
) {
    /** 按标题，默认升序。 */
    class Title(context: Context) : OrderBy(context, Selection(0, true))

    /** 按修改日期，默认降序（从新到旧）。 */
    class DateDescending(context: Context) : OrderBy(context, Selection(1, false))
}
