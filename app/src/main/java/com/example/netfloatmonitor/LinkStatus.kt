package com.example.netfloatmonitor

data class LinkStatus(
    val airRssi1: String = "--",
    val airRssi2: String = "--",
    val airSnr: String = "--",
    val airPass: String = "0",
    val airFailed: String = "0",
    val airAnt: String = "--",

    val gndRssi1: String = "--",
    val gndRssi2: String = "--",
    val gndSnr: String = "--",
    val gndPass: String = "0",
    val gndFailed: String = "0",
    val gndAnt: String = "--",

    val freq: String = "--",
    val mcs: String = "--",
    val power: String = "--",
    val distance: String = "0",
    val txRate: String = "0",
    val rxRate: String = "0",
    
    val airNoise: Array<String> = emptyArray(),
    val gndNoise: Array<String> = emptyArray(),
    
    val role: Int = 1
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as LinkStatus
        if (airRssi1 != other.airRssi1) return false
        if (airRssi2 != other.airRssi2) return false
        if (airSnr != other.airSnr) return false
        if (airPass != other.airPass) return false
        if (airFailed != other.airFailed) return false
        if (airAnt != other.airAnt) return false
        if (gndRssi1 != other.gndRssi1) return false
        if (gndRssi2 != other.gndRssi2) return false
        if (gndSnr != other.gndSnr) return false
        if (gndPass != other.gndPass) return false
        if (gndFailed != other.gndFailed) return false
        if (gndAnt != other.gndAnt) return false
        if (freq != other.freq) return false
        if (mcs != other.mcs) return false
        if (power != other.power) return false
        if (distance != other.distance) return false
        if (txRate != other.txRate) return false
        if (rxRate != other.rxRate) return false
        if (!airNoise.contentEquals(other.airNoise)) return false
        if (!gndNoise.contentEquals(other.gndNoise)) return false
        if (role != other.role) return false
        return true
    }

    override fun hashCode(): Int {
        var result = airRssi1.hashCode()
        result = 31 * result + airRssi2.hashCode()
        result = 31 * result + airSnr.hashCode()
        result = 31 * result + airPass.hashCode()
        result = 31 * result + airFailed.hashCode()
        result = 31 * result + airAnt.hashCode()
        result = 31 * result + gndRssi1.hashCode()
        result = 31 * result + gndRssi2.hashCode()
        result = 31 * result + gndSnr.hashCode()
        result = 31 * result + gndPass.hashCode()
        result = 31 * result + gndFailed.hashCode()
        result = 31 * result + gndAnt.hashCode()
        result = 31 * result + freq.hashCode()
        result = 31 * result + mcs.hashCode()
        result = 31 * result + power.hashCode()
        result = 31 * result + distance.hashCode()
        result = 31 * result + txRate.hashCode()
        result = 31 * result + rxRate.hashCode()
        result = 31 * result + airNoise.contentHashCode()
        result = 31 * result + gndNoise.contentHashCode()
        result = 31 * result + role
        return result
    }
}
