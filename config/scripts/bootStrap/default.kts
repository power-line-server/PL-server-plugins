package bootStrap

suspend fun boot() = ScriptManager.transactionV2 {
    enable("kcp/")
    execute().printResult()

    //add 添加需要加载的脚本(前缀判断) exclude 排除脚本(可以作为依赖被加载)
    enable(ScriptRegistry.allScripts())
    exclude("@")
    exclude("bootStrap/")
    exclude("coreLibrary/extApi/")//lazy load
    exclude("scratch")
    exclude("mirai")//Deprecated

    load("mapScript/")
}.printResult()


/**
 * 编译缓存的环境指纹失效。
 *
 * SA 的缓存键只含「脚本自身源码」的哈希(Config.cacheFileV3(id, hash)), **不含依赖**, 因此:
 *  - 修改被广泛引用的 .kt 库文件(menu.lib.kt / variables.kts / *.api.kt 等)后, 依赖方仍会复用旧 .ktc
 *  - 更换 server.jar 或加载器 jar 后, 所有脚本的旧缓存都不再兼容
 * 这两种情况原先只能靠人工删 cache/compiled 解决(否则报 NoSuchMethodError/LinkageError/ConditionFail)。
 * 这里在**加载其它脚本之前**比对环境指纹: 变了就整体清理一次(仅此一次编译开销), 没变则零成本。
 *
 * 指纹 = (所有 .kt 模块库文件内容 + server.jar 与 mods 下 jar 的大小/时间 + 框架版本) 的 MD5。
 * 只处理"环境"变化; 单个脚本自身的源码变化由 SA 自己的缓存键负责, 不需要整体清理。
 */
private fun envFingerprint(): String {
    val md = java.security.MessageDigest.getInstance("MD5")
    fun feed(text: String) = md.update(text.toByteArray(Charsets.UTF_8))
    val scriptRoot = Config.rootDir
    runCatching {
        scriptRoot.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") && "cache" !in it.path }
            .sortedBy { it.path }
            .forEach { f ->
                feed(f.path.substringAfter(scriptRoot.path))
                feed(f.length().toString())
                md.update(f.readBytes())
            }
    }
    feed(System.getProperty("SA-Kotlin") ?: "")
    listOf(
        java.io.File(scriptRoot.parentFile?.parentFile, "server.jar"),
        java.io.File(scriptRoot, "mods")
    ).forEach { f ->
        if (f.isFile) {
            feed(f.name); feed(f.length().toString()); feed(f.lastModified().toString())
        } else if (f.isDirectory) {
            f.listFiles()?.sortedBy { it.name }?.forEach { jar ->
                feed(jar.name); feed(jar.length().toString()); feed(jar.lastModified().toString())
            }
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

/** 环境指纹变了才清理编译缓存; 首次运行(无指纹文件)只记录不清理, 避免升级后平白全量重编译一次 */
private fun checkCacheEnv() {
    runCatching {
        val cacheDir = Config.cacheDir
        val stamp = java.io.File(cacheDir, "env.fingerprint")
        val current = envFingerprint()
        val last = if (stamp.isFile) stamp.readText().trim() else null
        if (last == null) {
            stamp.parentFile?.mkdirs()
            stamp.writeText(current)
            return
        }
        if (last == current) return
        val compiled = java.io.File(cacheDir, "compiled")
        val count = compiled.listFiles()?.size ?: 0
        if (count > 0) {
            compiled.deleteRecursively()
            logger.info("[cache] 检测到库文件/服务端 jar 变化, 已清理 $count 个脚本的编译缓存, 本次启动将重新编译")
        }
        stamp.writeText(current)
    }.onFailure { logger.warning("[cache] 环境指纹检查失败(不影响启动): ${it.message}") }
}

onEnable {
    if (Config.mainScript != id)
        return@onEnable ScriptManager.disableScript(this, "仅可通过SAMain启用")
    checkCacheEnv()
    ScriptManager.afterTransaction {
        boot()
    }
}
