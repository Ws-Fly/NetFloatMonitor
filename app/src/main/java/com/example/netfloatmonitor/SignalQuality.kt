package com.example.netfloatmonitor

enum class SignalQuality {
    EXCELLENT, GOOD, FAIR, POOR, BAD, DISCONNECTED;

    companion object {
        fun fromRawStrings(rssiStr: String?, snrStr: String?): SignalQuality {
            val rssi = rssiStr?.toFloatOrNull()
            val snr = snrStr?.toFloatOrNull()

            if (rssiStr.isNullOrBlank() || snrStr.isNullOrBlank()) return DISCONNECTED
            if (rssi == 110f || snr == 0f) return DISCONNECTED

            return when {
                rssi <= 75 && snr >= 20 -> EXCELLENT
                rssi <= 85 && snr >= 15 -> GOOD
                rssi <= 90 && snr >= 10 -> FAIR
                rssi <= 98 && snr >= 5 -> POOR
                else -> BAD
            }
        }

        fun worse(a: SignalQuality, b: SignalQuality): SignalQuality =
            values().maxOf { it.ordinal - a.ordinal + b.ordinal }
                .let { values()[it] }
    }
}
