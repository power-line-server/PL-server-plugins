package wayzer.user

import cf.wayzer.scriptAgent.util.Services
import coreLib.extApi.KVStore
import coreLibrary.lib.get
import mindustry.gen.Player
import org.h2.mvstore.MVMap
import org.h2.mvstore.type.StringDataType
import wayzer.lib.PlayerData

// KVStore 支持的语言/时区设置存储
val settings: MVMap<String, String> by lazy {
    Services.get<KVStore>().get().open("langSettings", StringDataType.INSTANCE)
}

val tzSettings: MVMap<String, String> by lazy {
    Services.get<KVStore>().get().open("tzSettings", StringDataType.INSTANCE)
}

var PlayerData.lang: String
    get() = settings[id] ?: player?.locale ?: "zh_CN"
    set(v) {
        if (lang == v) return
        if (v == player?.locale) {
            settings.remove(id)
        } else {
            settings[id] = v
        }
    }

var PlayerData.timezone: String
    get() = tzSettings[id] ?: "+08:00"
    set(v) {
        tzSettings[id] = v
    }
