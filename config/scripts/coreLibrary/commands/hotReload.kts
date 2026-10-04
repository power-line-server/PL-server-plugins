package coreLibrary.commands

import cf.wayzer.placehold.PlaceHoldApi.with
import cf.wayzer.scriptAgent.registry.DirScriptRegistry
import java.nio.file.*

var watcher: WatchService? = null

fun enableWatch() {
    if (watcher != null) return//Enabled
    watcher = FileSystems.getDefault().newWatchService()
    Config.rootDir.walkTopDown().onEnter { it.name != "cache" && it.name != "lib" && it.name != "res" }
        .filter { it.isDirectory }.forEach {
            it.toPath().register(watcher!!, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY)
        }
    launch(Dispatchers.IO) {
        while (true) {
            val key = try {
                watcher?.take() ?: return@launch
            } catch (_: ClosedWatchServiceException) {
                return@launch
            }
            key.pollEvents().forEach { event ->
                if (event.count() != 1) return@forEach
                val file = (key.watchable() as Path).resolve(event.context() as? Path ?: return@forEach)
                when {
                    file.toString().endsWith(Config.scriptSuffix) -> { //处理子脚本
                        val id = DirScriptRegistry.getIdByFile(file.toFile(), Config.rootDir)
                        val script = ScriptRegistry.getScriptInfo(id) ?: return@forEach
                        logger.info("脚本文件更新: ${event.kind().name()} ${script.id}")
                        // 真防抖: 等文件稳定(连续两次 mtime+size 不变)再编译。
                        // 原来固定 delay(1000) 会在"多步编辑/连续保存"的中间状态编译,
                        // 症状是报同一文件内符号的 Unresolved reference
                        var lastStamp = -1L
                        var stableTimes = 0
                        var waitedMs = 0
                        while (stableTimes < 2 && waitedMs < 5000) {
                            delay(250)
                            waitedMs += 250
                            val f = file.toFile()
                            val stamp = f.lastModified() * 31 + f.length()
                            if (stamp == lastStamp) stableTimes++ else {
                                stableTimes = 0
                                lastStamp = stamp
                            }
                        }
                        ScriptManager.transactionV2 {
                            reload(script)
                        }.printResult()
                    }

                    file.toFile().isDirectory -> {//添加子目录到Watch
                        file.register(
                            watcher!!,
                            StandardWatchEventKinds.ENTRY_CREATE,
                            StandardWatchEventKinds.ENTRY_MODIFY
                        )
                    }
                }
            }
            if (!key.reset()) return@launch
        }
    }
}

command("hotReload", "{tr command.hotReload.desc}".with(), commands = Commands.controlCommand) {
    requirePermission("scriptAgent.control.hotReload")
    body {
        if (watcher == null) {
            enableWatch()
            reply("{tr hotReload.reply.start}".with())
        } else {
            watcher?.close()
            watcher = null
            reply("{tr hotReload.reply.stop}".with())
        }
    }
}

onDisable {
    withContext(Dispatchers.IO) {
        watcher?.close()
    }
}
