package com.limpe.tengerium.data.protocol.v11

import com.limpe.tengerium.data.protocol.MSNPProto

/**
 * Расширение протокола для версии 11 (MSNP11).
 * Здесь добавлены команды и параметры, специфичные для этой версии.
 */
object MSNP11Proto {
    const val VERSION = "MSNP11"
    
    // Команды, появившиеся или изменившиеся в MSNP11
    object NS {
        const val UBX = "UBX" // User Boundary Extension (для PSM и Media)
        const val UUX = "UUX" // User Update Extension
        const val GCF = "GCF" // Get Configuration File (Shields.xml)
        const val SBS = "SBS" // Store-based Status (для OIM)
        
        // Mime-типы для OIM (Offline Instant Messaging)
        const val MIME_OIM_NOTIFICATION = "text/x-msmsgsoimnotification"
        
        // Capabilities для MSNP11 обычно выше
        const val DEFAULT_CAPABILITIES = 0x10000000 or MSNPProto.Capabilities.DEFAULT_MASK
    }
    
    // Структура XML для UBX/UUX
    fun createUuxPayload(psm: String, media: String = ""): String {
        return "<Data><PSM>$psm</PSM><CurrentMedia>$media</CurrentMedia></Data>"
    }
}