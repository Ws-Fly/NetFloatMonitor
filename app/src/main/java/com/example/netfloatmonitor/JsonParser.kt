package com.example.netfloatmonitor

import org.json.JSONObject

object JsonParser {

    fun parse(json: String): LinkStatus {
        val obj = JSONObject(json)

        return LinkStatus(
            airRssi1 = obj.optString("rssi1_a", "--"),
            airRssi2 = obj.optString("rssi2_a", "--"),
            airSnr = obj.optString("snr_a", "--"),
            airPass = obj.optString("pass_a", "0"),
            airFailed = obj.optString("failed_a", "0"),
            airAnt = obj.optString("ant_a", "--"),

            gndRssi1 = obj.optString("rssi1_g", "--"),
            gndRssi2 = obj.optString("rssi2_g", "--"),
            gndSnr = obj.optString("snr_g", "--"),
            gndPass = obj.optString("pass_g", "0"),
            gndFailed = obj.optString("failed_g", "0"),
            gndAnt = obj.optString("ant_g", "--"),

            freq = obj.optString("freq_tx", "--"),
            mcs = obj.optString("mcs", "--"),
            power = obj.optString("power", "--"),
            distance = obj.optString("distance", "0"),
            txRate = obj.optString("ethTx", "0"),
            rxRate = obj.optString("ethRx", "0"),

            airNoise = parseNoise(obj.optString("noiseFloor_a", "")),
            gndNoise = parseNoise(obj.optString("noiseFloor_g", "")),
            
            // ===== 新增：解析 role 字段 =====
            role = obj.optInt("role", 1)
        )
    }

    private fun parseNoise(value: String): Array<String> {
        if (value.isEmpty()) return emptyArray()
        return value.split(",").toTypedArray()
    }
}
