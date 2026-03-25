package com.limpe.tengerium.ui

import android.graphics.Color
import com.limpe.tengerium.R
import com.limpe.tengerium.data.protocol.MSNPProto
import com.limpe.tengerium.util.UiConstants

object StatusUtils {

    fun getStatusStringRes(status: String?): Int {
        return when (status) {
            MSNPProto.Status.ONLINE -> R.string.online
            MSNPProto.Status.BUSY -> R.string.busy
            MSNPProto.Status.AWAY -> R.string.away
            MSNPProto.Status.BRB -> R.string.brb
            MSNPProto.Status.PHONE -> R.string.on_phone
            MSNPProto.Status.LUNCH -> R.string.out_to_lunch
            MSNPProto.Status.HIDDEN, MSNPProto.Status.OFFLINE -> R.string.offline
            else -> R.string.offline
        }
    }

    fun getStatusColor(status: String?): Int {
        return when (status) {
            MSNPProto.Status.ONLINE -> Color.parseColor(UiConstants.COLOR_STATUS_ONLINE)
            MSNPProto.Status.BUSY, MSNPProto.Status.PHONE -> Color.parseColor(UiConstants.COLOR_STATUS_BUSY)
            MSNPProto.Status.AWAY, MSNPProto.Status.BRB, MSNPProto.Status.LUNCH -> Color.parseColor(UiConstants.COLOR_STATUS_AWAY)
            else -> Color.parseColor(UiConstants.COLOR_STATUS_OFFLINE)
        }
    }
    
    fun getStatusColorRes(status: String?): Int {
        return when (status) {
            MSNPProto.Status.ONLINE -> R.color.status_online
            MSNPProto.Status.BUSY, MSNPProto.Status.PHONE -> R.color.status_busy
            MSNPProto.Status.AWAY, MSNPProto.Status.BRB, MSNPProto.Status.LUNCH -> R.color.status_away
            else -> R.color.status_offline
        }
    }
}
