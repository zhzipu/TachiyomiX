package eu.kanade.tachiyomi.data.upload

import android.content.Context
import android.graphics.BitmapFactory
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.NotificationHandler
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.lang.chop
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.sy.SYMR

/**
 * 上传任务的通知。
 *
 * 进度条本身显示在书架的「下载」分类里（见 `LibraryMangaProgress`），
 * 这里只负责在通知栏留个进度 + 结果，跟 `DownloadNotifier` 的分工一致。
 */
internal class UploadNotifier(private val context: Context) {

    private val progressNotificationBuilder by lazy {
        context.notificationBuilder(Notifications.CHANNEL_UPLOADER_PROGRESS) {
            setLargeIcon(BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher))
            setAutoCancel(false)
            setOnlyAlertOnce(true)
        }
    }

    private val resultNotificationBuilder by lazy {
        context.notificationBuilder(Notifications.CHANNEL_UPLOADER_ERROR) {
            setLargeIcon(BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher))
            setAutoCancel(true)
        }
    }

    fun onProgress(mangaTitle: String, progress: Int, uploaded: Int, total: Int) {
        with(progressNotificationBuilder) {
            setSmallIcon(android.R.drawable.stat_sys_upload)
            setContentTitle(context.stringResource(SYMR.strings.upload_notifier_title, mangaTitle.chop(40)))
            setContentText(context.stringResource(SYMR.strings.upload_notifier_progress, uploaded, total))
            setProgress(100, progress, false)
            setContentIntent(NotificationHandler.openDownloadManagerPendingActivity(context))
            context.notify(Notifications.ID_UPLOAD_PROGRESS, build())
        }
    }

    fun onComplete(mangaTitle: String, uploaded: Int, folderPath: String) {
        with(resultNotificationBuilder) {
            setSmallIcon(android.R.drawable.stat_sys_upload_done)
            setContentTitle(mangaTitle.chop(40))
            setContentText(
                context.stringResource(SYMR.strings.upload_notifier_completed, uploaded, folderPath),
            )
            setProgress(0, 0, false)
            setContentIntent(NotificationHandler.openDownloadManagerPendingActivity(context))
            context.notify(Notifications.ID_UPLOAD_ERROR, build())
        }
    }

    fun onError(mangaTitle: String, message: String?) {
        with(resultNotificationBuilder) {
            setSmallIcon(android.R.drawable.stat_notify_error)
            setContentTitle(context.stringResource(SYMR.strings.upload_notifier_failed, mangaTitle.chop(40)))
            setContentText(message.orEmpty())
            setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(message.orEmpty()))
            setContentIntent(NotificationHandler.openDownloadManagerPendingActivity(context))
            context.notify(Notifications.ID_UPLOAD_ERROR, build())
        }
    }

    /** 上传队列清空（无论成功失败），把进度通知收掉。 */
    fun dismissProgress() {
        context.cancelNotification(Notifications.ID_UPLOAD_PROGRESS)
    }
}
