package com.example.capd2

data class AccData(
    var idx: Int = 0,
    var x: Float = 0.0f,
    var y: Float = 0.0f,
    var z: Float = 0.0f,
    var timestamp: Long = 0
)

data class HeartRateData (
    var idx: Int = 0,
    var hrate: Float = 0.0f,
    var timestamp: Long = 0
)

data class UploadRequestBody (
    var acc: List<AccData>,
    var hrate: List<HeartRateData>,
    var startAt: Long = 0,
    var endAt: Long = 0,
    var androidVersion: Int = 0,
    var sensorDelayTime: String = "",
    var createDateTime: Long,
    var deviceId: String = "",
    var collectId: Long = 0,
)