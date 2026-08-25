package coreLibrary

import arc.files.Fi

/**
 * 多语言服务接口，供其他脚本通过 Services.get<LangService>().get() 获取
 * 由 coreLibrary/lang.kts 实现
 */
interface LangService {
    /** 所有已加载的语言代码列表（langCode -> language.name 显示名） */
    val supportedLangs: MutableMap<String, String>
    /** 控制台语言 */
    var console: String
    /** Mindustry 源码目录路径（指向包含 core/ 的顶层目录）。留空则回退到 Vars.tree */
    val mindustrySourceDir: String
    /** 重新加载 bundle.properties */
    fun loadBundles()
    /** 获取 bundleData（key -> langCode -> translation），供 WebUI 等外部模块批量读取 */
    fun getBundleMap(): MutableMap<String, MutableMap<String, String>>
    /** 按 lang 查 bundle 翻译，含 fallback：玩家语言 -> zh_CN -> null */
    fun translateBundle(key: String, lang: String): String?
    /** 获取游戏 bundle 的基础 Fi（不含 locale 后缀），供 I18NBundle.createBundle() 使用。优先从 mindustrySourceDir/core/assets/bundles/bundle 加载，回退到 Vars.tree.get("bundles/bundle") */
    fun getGameBundleBase(): Fi
}
