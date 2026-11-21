package com.bestwinesport.staff

/**
 * Address of your Railway backend (see backend/README.md). No secret goes here - the Telegram bot token
 * lives only in Railway variables. Images are saved in Firebase as "tgimg:<imageId>" and shown through
 * BASE_URL/api/images/<imageId>.
 */
object BackendConfig {
    /** imgbb.com API key used by the admin app to upload images (images are then shown from their https link). */
    const val IMGBB_KEY = "193451a0e18cf8acfc5a673dbf5a163e"

    const val BASE_URL = "https://full-app-built-production.up.railway.app"

    fun resolve(v: String): String =
        if (v.startsWith("tgimg:")) BASE_URL.trimEnd('/') + "/api/images/" + v.removePrefix("tgimg:") else v
}
