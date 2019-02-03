package cn.andiedie.tvpause

class APIVolume (
    val request_result: Int,
    val msg: String,
    val data: String
)

class Volume (
    val stream: String,
    val maxVolum: Int,
    val volum: Int
)
