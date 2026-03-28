package com.limpe.tengerium.data.protocol

import com.limpe.tengerium.data.security.SecurePrefs

/**
 * Все константы MSNP11 и настройки клиента здесь.
 */
object MSNPProto {
    const val VERSION = "MSNP11"
    
    // эндпоинт нексус
    fun getNexusUrl(prefs: SecurePrefs): String {
        return "https://${prefs.nexusDomain}/rdr/pprdr.asp"
    }

    object Status {
        const val ONLINE = "NLN"
        const val BUSY = "BSY"
        const val BRB = "BRB"
        const val AWAY = "AWY"
        const val PHONE = "PHN"
        const val LUNCH = "LUN"
        const val HIDDEN = "HDN"
        const val OFFLINE = "FLN"
    }

    object List {
        const val FL = 1 
        const val AL = 2 
        const val BL = 4 
    }

    object Capabilities {
        const val DEFAULT_MASK = 1342472248
    }
}
