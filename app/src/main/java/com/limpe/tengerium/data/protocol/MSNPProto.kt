package com.limpe.tengerium.data.protocol

import com.limpe.tengerium.data.security.SecurePrefs

/**
 * Все константы MSNP9 и настройки клиента здесь.
 */
object MSNPProto {
    const val VERSION = "MSNP9"
    const val CVR_VERSION = "CVR0"
    const val DEFAULT_PORT = 1863
    
    // Escargot эндпоинты
    fun getRstUrl(prefs: SecurePrefs): String {
        return "https://${prefs.nexusDomain}/RST.srf"
    }
    
    fun getNexusUrl(prefs: SecurePrefs): String {
        return "https://${prefs.nexusDomain}/rdr/pprdr.asp"
    }

    // Идентификаторы продукта для челленджа
    const val PRODUCT_ID = "PROD0101{0C7%3143"
    const val PRODUCT_KEY = "0S@7-S0WFR9W498U"

    object NS {
        const val VER = "VER"
        const val CVR = "CVR"
        const val USR = "USR"
        const val XFR = "XFR"
        const val SYN = "SYN" 
        const val LST = "LST"
        const val ADD = "ADD"
        const val REM = "REM"
        const val REA = "REA"
        const val PRP = "PRP"
        const val CHG = "CHG"
        const val ILN = "ILN"
        const val NLN = "NLN"
        const val FLN = "FLN"
        const val MSG = "MSG"
        const val NOT = "NOT"
        const val PNG = "PNG"
        const val QNG = "QNG"
        const val QRY = "QRY"
        const val CHL = "CHL"
        const val RNG = "RNG"
        const val OUT = "OUT"
        const val UUX = "UUX"
        
        const val HEARTBEAT_INTERVAL = 45000L
        const val MIN_CMD_INTERVAL = 0L 
        const val RESPONSE_TIMEOUT = 15000L
        
        const val CLIENT_VERSION = "7.5.0311"
        const val OS_TYPE = "winnt"
        const val OS_VERSION = "5.1"
        const val ARCH = "i386"
        const val CLIENT_NAME = "MSNMSGR"

        // Auth modes
        const val AUTH_TWN = "TWN"
        const val AUTH_I = "I"
        const val AUTH_S = "S"
        const val AUTH_OK = "OK"

        // List types
        const val LIST_FL = "FL"
        const val LIST_AL = "AL"
        const val LIST_BL = "BL"
        const val LIST_RL = "RL"
    }

    object SB {
        const val ANS = "ANS"
        const val CAL = "CAL"
        const val JOI = "JOI"
        const val MSG = "MSG"
        const val BYE = "BYE"
        const val IRO = "IRO"
        const val ACK = "ACK"
        const val NAK = "NAK"
        const val RNG = "RNG"
        const val USR = "USR"
        
        const val INACTIVITY_TIMEOUT = 30000L 
        const val MIN_CMD_INTERVAL = 0L 
        const val RESPONSE_TIMEOUT = 15000L

        object Mime {
            const val CONTROL = "text/x-msmsgscontrol"
            const val DATACAST = "text/x-msnmsgr-datacast"
            const val PLAIN = "text/plain"
            const val PROFILE = "text/x-msmsgsprofile"
            const val P2P = "application/x-msnmsgr-sessionreqbody"
        }
    }

    object P2P {
        const val APPID_DISPLAY_PICTURE = 1
        const val APPID_FILE_TRANSFER = 2
        
        const val GUID_EUF_DISPLAY_PICTURE = "{A4268EEC-FEC5-49E5-95C3-F126696BDBF6}"
        const val GUID_EUF_FILE_TRANSFER = "{5D3E02AB-6190-11d3-BB2C-00104B18E218}"
        const val GUID_DISPLAY_PICTURE = "{A4275306-096C-4919-994C-C460021A935D}"

        const val FLAG_NONE = 0x0
        const val FLAG_ACK = 0x2
        const val FLAG_BYE = 0x8
        const val FLAG_DATA = 0x20
        const val FLAG_ERROR = 0x40
    }

    object Status {
        const val ONLINE = "NLN"
        const val BUSY = "BSY"
        const val IDLE = "IDL"
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
        const val RL = 8 
        const val PL = 16 
    }

    object Capabilities {
        const val DEFAULT_MASK = 1342472248
    }

    object Soap {
        const val HOSTING_APP = "{7108E71A-9926-4FCB-BCC9-9A9D3F32E423}"
        const val REQUEST_PARAMS = "AQAAAAIAAABsYwQAAAAyMDU3"
        const val PASSPORT_URL = "http://Passport.NET/tb"
        const val MESSENGER_DOMAIN = "messenger.msn.com"
        const val REQUEST_TYPE_ISSUE = "http://schemas.xmlsoap.org/ws/2004/04/security/trust/Issue"
    }

    object Service {
        const val CHANNEL_ID = "MSNP_SERVICE_CHANNEL"
        const val MESSAGE_CHANNEL_ID = "MSNP_MESSAGE_CHANNEL"
        const val FOREGROUND_ID = 1001
        
        const val CHANNEL_NAME_CONNECTION = "Connection Status"
        const val CHANNEL_NAME_MESSAGES = "Messages"
    }
    
    object UI {
        const val TABLET_WIDTH_THRESHOLD = 600
        const val MAX_CARD_WIDTH_DP = 500
        const val SNACKBAR_DURATION = 3000
        const val TYPING_TIMEOUT = 5000L
        const val AVATAR_SIZE_PX = 120
    }
}
