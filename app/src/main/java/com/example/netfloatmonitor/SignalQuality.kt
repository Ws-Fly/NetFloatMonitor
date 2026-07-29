package com.example.netfloatmonitor

enum class SignalQuality {
    EXCELLENT, GOOD, FAIR, POOR, BAD, DISCONNECTED;

    companion object {
        /**
         * rssi / snr 都是字符串
         * 断链判定：SNR=="0" 或 RSSI=="110" 或空白
         */
        fun fromRawStrings(rssiStr: String?, snrStr: String?): SignalQuality {
            val rssi = rssiStr?.toIntOrNull()
            val snr = snrStr?.toIntOrNull()

            if (rssiStr.isNullOrBlank() || snrStr.isNullOrBlank()) return DISCONNECTED
            if (rssi == null || snr == null) return DISCONNECTED
            if (rssi == 110 || snr == 0) return DISCONNECTED

            return when {
                rssi <= 75 && snr >= 20 -> EXCELLENT
                rssi <= 85 && snr >= 15 -> GOOD
                rssi <= 90 && snr >= 10 -> FAIR
                rssi <= 98 && snr >= 5 -> POOR
                else -> BAD
            }
        }

        fun worse(a: SignalQuality, b: SignalQuality): SignalQuality =
            if (a.ordinal > b.ordinal) a else b
    }
}
