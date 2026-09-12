package com.jerrey.monoicon.ui

import androidx.compose.runtime.compositionLocalOf

/**
 * UI language (Phase 13).
 *
 * The selected value is persisted by [com.jerrey.monoicon.config.ConfigManager]
 * and applied to the Activity's configuration in `MainActivity.attachBaseContext`
 * (see [com.jerrey.monoicon.ui.wrapLocale]).
 */
enum class AppLanguage(val id: String) {
    SYSTEM("system"),
    CHINESE("zh"),
    ENGLISH("en");

    companion object {
        fun fromId(id: String?): AppLanguage =
            entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}

/**
 * All user-visible strings of the settings UI, in one place so the language
 * switch can swap them without touching the screens.
 */
data class AppStrings(
    val overview: String,
    val moduleSettings: String,
    val iconStyle: String,
    val lsposedStatus: String,
    val active: String,
    val inactive: String,
    val framework: String,
    val apiVersion: String,
    val scope: String,
    val scopeGranted: String,
    val scopeMissing: String,
    val requestScope: String,
    val scopeRequested: String,
    val logs: String,
    val about: String,
    val masterSwitch: String,
    val masterSwitchSummary: String,
    val language: String,
    val languageSummary: String,
    val languageSystem: String,
    val languageChinese: String,
    val languageEnglish: String,
    val lawnicons: String,
    val lawniconsSummary: String,
    val circleIcons: String,
    val circleIconsSummary: String,
    val notificationIcons: String,
    val notificationIconsSummary: String,
    val colorVariant: String,
    val colorVariantSummary: String,
    val variantDefaultHint: String,
    val launcherRestart: String,
    val launcherRestartSummary: String,
    val restart: String,
    val restarting: String,
    val restartDone: String,
    val restartFailed: String,
    val restartHint: String,
    val logsTitle: String,
    val logsRefresh: String,
    val logsEmpty: String,
    val logsRootHint: String,
    val logsInAppOnly: String,
    val aboutBody: String,
    val aboutScope: String,
    val aboutVersion: String,
    val aboutRepo: String,
    val back: String,
)

private val ZH = AppStrings(
    overview = "概览",
    moduleSettings = "模块设置",
    iconStyle = "图标样式",
    lsposedStatus = "LSPosed 状态",
    active = "已激活",
    inactive = "未激活",
    framework = "框架",
    apiVersion = "API 版本",
    scope = "作用域",
    scopeGranted = "已授权",
    scopeMissing = "未授权",
    requestScope = "请求作用域",
    scopeRequested = "已发送作用域请求，请在 LSPosed 中确认",
    logs = "日志",
    about = "关于",
    masterSwitch = "启用模块",
    masterSwitchSummary = "总开关：关闭后不再处理任何图标",
    language = "语言",
    languageSummary = "设置页面的显示语言",
    languageSystem = "跟随系统",
    languageChinese = "简体中文",
    languageEnglish = "English",
    lawnicons = "Lawnicons 图标优先",
    lawniconsSummary = "无 monochrome 层的应用使用内置 Lawnicons 字形",
    circleIcons = "圆形图标（无需主题）",
    circleIconsSummary = "MIUI 图标与 MonoIcon 图标统一裁成圆形；修改后需重启桌面",
    notificationIcons = "通知中心图标",
    notificationIconsSummary = "通知栏程序图标使用 MonoIcon 图标；修改后需重启系统界面",
    colorVariant = "取色方案",
    colorVariantSummary = "图标配色使用的 Material Dynamic Color 2025 变体",
    variantDefaultHint = "默认 Material You 风格",
    launcherRestart = "重启桌面",
    launcherRestartSummary = "重装模块或切换主题/形状后，重启桌面才能完全生效（首次需要授予 root）",
    restart = "重启",
    restarting = "重启中…",
    restartDone = "已重启桌面",
    restartFailed = "重启失败：请授予 MonoIcon root 权限",
    restartHint = "配置已修改，请重启桌面 / 系统界面以完全生效。",
    logsTitle = "运行日志",
    logsRefresh = "刷新",
    logsEmpty = "暂无日志",
    logsRootHint = "读取系统日志需要 root（su），当前未获取到权限",
    logsInAppOnly = "以下仅为设置页自身的事件记录",
    aboutBody = "MonoIcon 把 HyperOS 应用图标转换为单色 Material You 主题图标，并复用 AOSP 的遮罩与配色管线。",
    aboutScope = "作用域",
    aboutVersion = "版本",
    aboutRepo = "项目主页",
    back = "返回",
)

private val EN = AppStrings(
    overview = "Overview",
    moduleSettings = "Module",
    iconStyle = "Icon style",
    lsposedStatus = "LSPosed status",
    active = "Active",
    inactive = "Inactive",
    framework = "Framework",
    apiVersion = "API version",
    scope = "Scope",
    scopeGranted = "granted",
    scopeMissing = "missing",
    requestScope = "Request scope",
    scopeRequested = "Scope request sent — confirm it in LSPosed",
    logs = "Logs",
    about = "About",
    masterSwitch = "Enable module",
    masterSwitchSummary = "Master switch: when off no icon is processed",
    language = "Language",
    languageSummary = "Language used by the settings screen",
    languageSystem = "System default",
    languageChinese = "简体中文",
    languageEnglish = "English",
    lawnicons = "Prefer Lawnicons",
    lawniconsSummary = "Use the bundled Lawnicons glyphs for apps without a monochrome layer",
    circleIcons = "Circular icons (no theme)",
    circleIconsSummary = "Shape every icon as a circle without an MTZ theme; restart the launcher to apply",
    notificationIcons = "Notification icons",
    notificationIconsSummary = "Theme notification app icons in SystemUI; restart SystemUI to apply",
    colorVariant = "Colour variant",
    colorVariantSummary = "Material Dynamic Color 2025 variant used for icon colours",
    variantDefaultHint = "Default Material You style",
    launcherRestart = "Launcher restart",
    launcherRestartSummary = "Restart the launcher after reinstalling or switching theme/shape (root required once)",
    restart = "Restart",
    restarting = "Restarting…",
    restartDone = "Launcher restarted",
    restartFailed = "Restart failed — grant MonoIcon root access",
    restartHint = "Configuration changed — restart the launcher / SystemUI to apply.",
    logsTitle = "Runtime logs",
    logsRefresh = "Refresh",
    logsEmpty = "No logs yet",
    logsRootHint = "Reading system logs needs root (su); permission not granted",
    logsInAppOnly = "Showing settings-process events only",
    aboutBody = "MonoIcon turns HyperOS app icons into monochrome Material You themed icons, reusing the AOSP mask and colour pipeline.",
    aboutScope = "Scope",
    aboutVersion = "Version",
    aboutRepo = "Project page",
    back = "Back",
)

/** Strings for [language], resolving [AppLanguage.SYSTEM] to the given system locale. */
fun stringsFor(language: AppLanguage, systemIsChinese: Boolean): AppStrings = when (language) {
    AppLanguage.CHINESE -> ZH
    AppLanguage.ENGLISH -> EN
    AppLanguage.SYSTEM -> if (systemIsChinese) ZH else EN
}

/** Current UI strings; provided by the app root and read by every screen. */
val LocalStrings = compositionLocalOf { ZH }
