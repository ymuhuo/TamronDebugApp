package com.bominwell.cctvsdk

/**
 * 调试 App 使用的 UI 层相机配置。
 * 大部分参数统一按 0~100 表示，快门 shutter 按 0~19 表示。
 */
data class CameraConfigInfo(
    var wbModel: Int = 0,
    var wbRed: Int = 50,
    var wbBlue: Int = 50,
    var ExpMode: Int = 0,
    var gain: Int = 0,
    var iris: Int = 100,
    var shutter: Int = 0,
    var exposureComp: Int = 50,
    var sharpness: Int = 50,
    var contrast: Int = 50,
    var saturation: Int = 50,
    var hue: Int = 50,
    var gamma: Int = 0,
    var nr2d: Int = 3,
    var nr3d: Int = 3,
    var dayNightMode: Int = 0,
    var wdr: Boolean = false,
    var blc: Boolean = false,
    var eis: Boolean = false,
    var defog: Boolean = false,
    var defogLevel: Int = 2
)
