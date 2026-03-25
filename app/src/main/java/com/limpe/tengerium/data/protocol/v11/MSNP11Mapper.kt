package com.limpe.tengerium.data.protocol.v11

import uniffi.msnp11_sdk.MsnpList
import uniffi.msnp11_sdk.MsnpStatus

object MSNP11Mapper {

    fun mapStatus(status: MsnpStatus): String = when (status) {
        MsnpStatus.ONLINE -> "NLN"
        MsnpStatus.BUSY -> "BSY"
        MsnpStatus.AWAY -> "AWY"
        MsnpStatus.IDLE -> "IDL"
        MsnpStatus.ON_THE_PHONE -> "PHN"
        MsnpStatus.OUT_TO_LUNCH -> "LUN"
        MsnpStatus.BE_RIGHT_BACK -> "BRB"
        MsnpStatus.APPEAR_OFFLINE -> "HDN"
    }

    fun mapToMsnpStatus(status: String): MsnpStatus = when (status) {
        "NLN" -> MsnpStatus.ONLINE
        "BSY" -> MsnpStatus.BUSY
        "AWY" -> MsnpStatus.AWAY
        "IDL" -> MsnpStatus.IDLE
        "PHN" -> MsnpStatus.ON_THE_PHONE
        "LUN" -> MsnpStatus.OUT_TO_LUNCH
        "BRB" -> MsnpStatus.BE_RIGHT_BACK
        "HDN" -> MsnpStatus.APPEAR_OFFLINE
        else -> MsnpStatus.ONLINE
    }

    fun mapLists(lists: List<MsnpList>): String {
        val res = mutableListOf<String>()
        if (lists.contains(MsnpList.FORWARD_LIST)) res.add("FL")
        if (lists.contains(MsnpList.ALLOW_LIST)) res.add("AL")
        if (lists.contains(MsnpList.BLOCK_LIST)) res.add("BL")
        if (lists.contains(MsnpList.REVERSE_LIST)) res.add("RL")
        if (lists.contains(MsnpList.PENDING_LIST)) res.add("PL")
        return res.joinToString(",")
    }
}
