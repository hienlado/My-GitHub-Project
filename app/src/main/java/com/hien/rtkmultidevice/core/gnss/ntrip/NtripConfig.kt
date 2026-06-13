package com.hien.rtkmultidevice.core.gnss.ntrip

data class NtripConfig(
    val host: String = "",
    val port: Int = 2101,
    val mountPoint: String = "",
    val username: String = "",
    val password: String = "",
    val ggaIntervalSeconds: Int = 5
) {
    val normalizedMountPoint: String
        get() = mountPoint.trim().trimStart('/')

    fun isValid(): Boolean =
        host.isNotBlank() && port > 0 && normalizedMountPoint.isNotBlank()

    fun normalized(): NtripConfig = copy(
        host = host.trim(),
        mountPoint = normalizedMountPoint,
        username = username.trim()
    )
}
