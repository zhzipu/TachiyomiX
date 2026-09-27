package eu.kanade.tachiyomi.data.upload

/**
 * 上传任务的状态。
 *
 * 状态机：QUEUED →（WAITING_CONFIRM）→ UPLOADING → COMPLETED / ERROR / CANCELLED
 */
enum class UploadStatus {
    /** 已排队，还没开始。 */
    QUEUED,

    /** 服务器上已存在同名漫画，等用户决定「合并」还是「新建文件夹」（只有手动上传会走到）。 */
    WAITING_CONFIRM,

    /** 正在上传。 */
    UPLOADING,

    /** 全部完成。 */
    COMPLETED,

    /** 失败。 */
    ERROR,

    /** 用户取消了这次上传。 */
    CANCELLED,
}

/**
 * 一本漫画的上传状态与进度。
 *
 * 键是 manga id，由 `UploadManager.state` 持有供书架读取。
 *
 * **上传已改成章节级任务**（队列里一话一行），所以这里的
 * `uploaded` / `total` 数的是**这本漫画在当前这批任务里已处理 / 总共几话**，
 * `pagesUploaded` / `pagesTotal` 是页数。一本书的多话任务会把进度累加到同一个
 * [UploadState] 上（`UploadManager` 按 mangaId 聚合），书架那条进度条因此仍是
 * 「这一本传了多少页」。
 *
 * @property uploaded 本批次里已处理（上传完成，或服务器上已有而跳过）的章节数
 * @property total 本批次需要处理的章节总数
 * @property pagesUploaded 已上传页数（一页一个 PUT，传完一页 +1，是精确值）
 * @property pagesTotal 本次要上传的总页数
 * @property folderPath 上传到的服务器端文件夹（相对根路径）
 * @property chapters 本批次各话的状态，供上传队列页按「话」展示与单话取消
 */
data class UploadState(
    val mangaId: Long,
    val mangaTitle: String,
    val status: UploadStatus,
    val uploaded: Int = 0,
    val total: Int = 0,
    val pagesUploaded: Int = 0,
    val pagesTotal: Int = 0,
    val folderPath: String? = null,
    val message: String? = null,
    // SY -->
    /** 本批次每一话的状态，键是 `Chapter.id`。上传队列页据此画每一行。 */
    val chapters: Map<Long, UploadChapterState> = emptyMap(),
    // SY <--
) {
    /**
     * 整本漫画的上传进度 0..100。
     *
     * 优先按页数算；页数数不出来时（本地文件读不动等）退化成按章节数，
     * 免得进度条永远停在 0。
     */
    val progress: Int
        get() = when (status) {
            UploadStatus.COMPLETED -> 100
            UploadStatus.CANCELLED -> 0
            else -> when {
                pagesTotal > 0 -> (pagesUploaded * 100 / pagesTotal).coerceIn(0, 100)
                total > 0 -> (uploaded * 100 / total).coerceIn(0, 100)
                else -> 0
            }
        }
}

/**
 * 书架「下载」分类下某个项目当前的下载 / 上传活动情况。
 *
 * 下载侧只带**当前这一章的页数**：书架那条下载进度条就是「已下载页数 / 下载总页数」，
 * 不再单独显示章节数，所以这里也不去算章节总数（那要查库，而 `LibraryItem` 已经带了）。
 *
 * 有没有任务这件事**必须显式带出来** —— 页数是 0 也可能是「刚排队还没开始」，
 * 光看数字分不清「在下载」和「没在下载」，而界面要按后者决定隐藏进度条。
 */
data class LibraryMangaProgress(
    /** 这本漫画当前有下载任务（排队中或正在下载）。 */
    val downloading: Boolean = false,
    /** 当前正在下载那一章**已经下载好的页数**。 */
    val pagesDone: Int = 0,
    /** 当前正在下载那一章的**总页数**；页表还没取到时是 0。 */
    val pagesTotal: Int = 0,
    // SY -->
    /**
     * 当前**正在上传那一话**已上传的页数（上传页数条的分子）。
     *
     * 口径与下载条对齐：下载条看的是「正在下载那一章」，上传条就看「正在上传那一话」。
     * 没有正在传的那一话时（排队中 / 等这一话下载完）退化成「本批次里下一话还没传的那一话」。
     * **整批的页数合计不再由这条表达** —— 那是下面章节条的职责。
     */
    val uploadPagesDone: Int = 0,
    /** 当前正在上传那一话的总页数（上传页数条的分母）；页数还没数出来时为 0。 */
    val uploadPagesTotal: Int = 0,
    /**
     * 本批次已处理（上传完成，或服务器上已有而跳过）的**章节数**（上传章节条的分子）。
     * 来源是 `UploadState.uploaded`。
     */
    val uploadChaptersDone: Int = 0,
    /** 本批次需要上传的**章节总数**（上传章节条的分母）。来源是 `UploadState.total`。 */
    val uploadChaptersTotal: Int = 0,
    // SY <--
    val uploadStatus: UploadStatus? = null,
    // SY -->
    /**
     * 章节口径：「已下载章节数」。
     *
     * 不管队列里有什么，只要本地**下载完成**了就算一章（等价于
     * `DownloadCache.getDownloadCount`，目录落好才计数，下到一半的 `_tmp` 不算）。
     *
     * 注意它与 [chaptersPending] 的口径**不完全对称**：它是**本地累计**的已下载章数
     * （可能包含本次任务之外的章），而 [chaptersPending] 只看队列。
     * 所以分母 `chaptersDone + chaptersPending` 表示的是「这次任务涉及的章数」
     * —— 只下 1 章时会被 [showChapterBar] 挡掉，不画。
     */
    val chaptersDone: Int = 0,
    /**
     * 章节口径：「本次要下载的章节数」= **在下载队列里但还没下完**的章节数。
     *
     * 关键在**不在队列中的章节一律不计入**（用户明确要求）：
     * 没入队的章节既不算分子也不算分母，否则分母会被「还没打算下的章节」撑大。
     * 所以这条进度条的分子是 [chaptersDone]、分母是 `chaptersDone + chaptersPending`，
     * 含义是「本次下载任务里，已经下完的比例」。
     */
    val chaptersPending: Int = 0,
    // SY <--
) {
    /** 上传任务进行中（排队 / 等用户确认冲突 / 正在传）。 */
    val uploadActive: Boolean
        get() = uploadStatus == UploadStatus.QUEUED ||
            uploadStatus == UploadStatus.WAITING_CONFIRM ||
            uploadStatus == UploadStatus.UPLOADING

    /** 上传失败。这是唯一「任务已结束但仍需要用户看见」的状态。 */
    val uploadFailed: Boolean
        get() = uploadStatus == UploadStatus.ERROR

    // SY -->
    /** 章节口径那条进度条的总章节数（分子 + 分母）。 */
    val chapterTotal: Int
        get() = chaptersDone + chaptersPending

    /**
     * 这本漫画有章节口径的进度可画。
     *
     * **分母（本次任务总章数）小于 2 就不画**：只有 1 章时这条条只可能是「0/1」或「1/1」，
     * 也就是**只有空和满两个状态**，画出来没有信息量（用户明确要求不显示这种情况）。
     *
     * 分母 = [chaptersDone] + [chaptersPending]，也就是「本次下载任务涉及的章数」——
     * 不在队列里的章节不算（见 [chaptersPending] 的说明），所以它表示的是
     * **这次任务**的规模，不是「本地一共存了几章」。
     */
    val showChapterBar: Boolean
        get() = chapterTotal > 1

    /**
     * 上传章节口径那条进度条能不能画。
     *
     * 两条规则：
     * - **本批次只上传 1 话时不画**（与下载章节条同一规则，用户明确要求）：
     *   分母是 1 的话这条只在「空 / 满」两个状态之间跳，没有信息量。
     * - **必须上传还在进行（或失败）才画**：整组上传条是一起显隐的，上传跑完就都收起来 ——
     *   `UploadState` 完成后仍留在 `state` 里，光看分母会一直画一条 100% 的条。
     */
    val showUploadChapterBar: Boolean
        get() = (uploadActive || uploadFailed) && uploadChaptersTotal > 1
    // SY <--

    companion object {
        val EMPTY = LibraryMangaProgress()
    }
}

/**
 * 上传队列里**一话**的状态。
 *
 * 队列页每一行绑一条它，用户能按话取消 / 重排。
 *
 * @property chapterId `Chapter.id`
 * @property chapterName 章节名（界面显示）
 * @property chapterNumber 章节号（排序用）
 * @property status 这一话自己的状态
 * @property pagesUploaded 这一话已上传页数
 * @property pagesTotal 这一话总页数；还没数出来时是 0（界面画不确定进度条）
 * @property message 失败时的原因
 */
data class UploadChapterState(
    val chapterId: Long,
    val chapterName: String,
    val chapterNumber: Double,
    val status: UploadStatus,
    val pagesUploaded: Int = 0,
    val pagesTotal: Int = 0,
    val message: String? = null,
) {
    /** 这一话的进度 0..100。页数还没数出来时返回 0，调用方据此画不确定进度条。 */
    val progress: Int
        get() = when {
            status == UploadStatus.COMPLETED -> 100
            pagesTotal > 0 -> (pagesUploaded * 100 / pagesTotal).coerceIn(0, 100)
            else -> 0
        }
}

/**
 * 用户对「服务器上已存在同名漫画」的处理选择。
 */
enum class UploadChoice {
    /** 复用服务器上已有的文件夹，只补缺失的章节。 */
    MERGE,

    /** 分配一个新的（随机串命名的）漫画文件夹，另存一份。 */
    NEW_FOLDER,

    /** 放弃这次上传。 */
    CANCEL,
}

/**
 * 一个待用户拍板的冲突项。
 *
 * @property remoteFolderName 服务器上已有的文件夹名
 */
data class UploadPendingDecision(
    val mangaId: Long,
    val mangaTitle: String,
    val remoteFolderName: String,
)

/**
 * 一次上传任务的输入。
 *
 * **粒度是一话**（不是一本）。上传队列页里每一行就是一话，可以单独取消、单独重排 ——
 * 这是用户明确要求的。一本漫画上传时会被拆成若干这样的任务，它们共用同一个
 * [mangaId]（去重、进度聚合都按它走）。
 *
 * @property chapterId 这一话的 id（`Chapter.id`）。同一个 manga 下**唯一** ——
 *   队列去重、Holder 定位、取消单话都靠它。
 * @property chapterName 章节名，只用于界面显示与日志。
 * @property chapterNumber 章节号，用于「按章节号排序」。
 * @property askOnConflict 遇到「服务器上已有同名漫画」时是否要弹窗询问。
 *   手动上传要问；自动上传不能弹窗，直接合并（避免留下重复副本）。
 */
data class UploadTask(
    val mangaId: Long,
    val chapterId: Long,
    val chapterName: String,
    val chapterNumber: Double,
    val askOnConflict: Boolean,
) {
    /**
     * 整本上传的批次号：同一本漫画、同一个 [askOnConflict] 的一批话共享它。
     *
     * 冲突询问（合并 / 新建文件夹）是**按本**回答一次的，所以同一本的多个任务
     * 必须能认出「我们是一批的」——重新入队时仍然沿用同一个批次号，
     * 否则用户回答一次之后，后面几话还会各问一遍。
     */
    val batchKey: String
        get() = "$mangaId:$askOnConflict"
}
