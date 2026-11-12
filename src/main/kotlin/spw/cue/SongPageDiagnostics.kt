package spw.cue

internal class SongPageDiagnostics {
    @Volatile var registration = "未启动 · 歌曲页接入默认关闭"
    @Volatile var playback = "尚未点播分轨"
    @Volatile var selfCheck = "尚未运行 · 点击「开始歌曲页诊断」"
    private var filterEntries = 0L
    private var filterReturns = 0L
    private var listEntries = 0L
    private var filter = "等待歌曲页原生筛选"
    private var scan = "等待原生曲库输入"
    private var preview = "尚未计算"
    private var publish = "尚未挂载"
    private var list = "等待歌曲页列表回调"
    private var render = "尚未测得分轨行尺寸"
    private var error: String? = null
    @Volatile var canMount = false
        private set

    @Synchronized
    fun filterEntered() {
        filterEntries++
    }

    @Synchronized
    fun filterReturned() {
        filterReturns++
    }

    @Synchronized
    fun listEntered() {
        listEntries++
    }

    @Synchronized
    fun filterIgnored(reason: String) {
        filter = "已收到回调 · $reason"
    }

    @Synchronized
    fun pendingCachedInput(input: Int) {
        filter = "已取得最近一次宿主输入 $input 首"
        scan = "等待后台分析"
        preview = "尚未计算"
        canMount = false
    }

    @Synchronized
    fun begin(input: Int, count: Int, hide: Boolean, query: Boolean, cached: Boolean, mounting: Boolean) {
        val source = when {
            mounting -> "歌曲页当前状态"
            cached -> "最近一次回调"
            else -> "本次回调"
        }
        filter = "$source · 输入 $input 首 · 原生返回 $count 首 · ${if (query) "正在搜索" else "无搜索条件"} · ${if (hide) "隐藏整轨" else "保留整轨"}"
        scan = "正在查找外置 CUE"
        canMount = false
        preview = "尚未计算"
        publish = "尚未挂载"
        error = null
    }

    @Synchronized
    fun scanned(paths: Int, sources: Int, tracks: Int, usable: Int) {
        scan = "本地路径 $paths 个 · 匹配整轨 $sources 首 · 解析分轨 $tracks 条 · 可点播 $usable 条"
    }

    @Synchronized
    fun preview(count: Int, cues: Int) {
        preview = "$count 首，其中分轨 $cues 条"
        canMount = cues > 0
    }

    @Synchronized
    fun observedNativeList() {
        list = "已收到原生列表回调 · 当前仅观察"
    }

    @Synchronized
    fun awaitingMount() {
        publish = "等待歌曲页状态订阅"
        list = "等待歌曲页列表回调"
        render = "尚未测得分轨行尺寸"
        error = null
    }

    @Synchronized
    fun automaticStopped() {
        registration = "歌曲页分轨自动显示已关闭"
        publish = "已恢复原生歌曲页状态"
        list = "等待原生列表绘制"
        render = "自动显示已关闭"
        canMount = false
    }

    @Synchronized
    fun stateUnavailable() {
        publish = "宿主状态流不兼容"
    }

    @Synchronized
    fun published(count: Int) {
        publish = "已提交歌曲页计数 $count 首"
    }

    @Synchronized
    fun submitted(rows: Int) {
        list = "已提交 $rows 行（不含分组标题）"
    }

    @Synchronized
    fun laidOutCue(number: Int, title: String, height: Int) {
        render = "已布局分轨 $number · $title · 高度 $height px"
    }

    @Synchronized
    fun failed(stage: String, failure: Throwable) {
        error = "$stage · ${failure.cause?.message ?: failure.message ?: failure.javaClass.simpleName}"
    }

    @Synchronized
    fun report(): String = buildString {
        appendLine("歌曲页")
        appendLine("注册  $registration")
        appendLine("空列表自检  $selfCheck")
        appendLine("宿主回调  筛选进入 $filterEntries 次 · 返回 $filterReturns 次 · 共用列表入口 $listEntries 次")
        appendLine("原生筛选  $filter")
        appendLine("CUE 匹配  $scan")
        appendLine("分轨预览  $preview")
        appendLine("计数挂载  $publish")
        appendLine("列表行  $list")
        appendLine("界面绘制  $render")
        appendLine("分轨点播  $playback")
        error?.let { appendLine("最近错误  $it") }
    }.trimEnd()
}
