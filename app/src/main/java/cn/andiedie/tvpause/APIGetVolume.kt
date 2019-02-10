package cn.andiedie.tvpause

class APIGetVolume (
    val request_result: Int,
    val msg: String,
    val data: String
)

class Volume (
    val stream: String,
    val maxVolum: Int,
    val volum: Int
)

class APISetVolume (
    val request_result: Int
)
