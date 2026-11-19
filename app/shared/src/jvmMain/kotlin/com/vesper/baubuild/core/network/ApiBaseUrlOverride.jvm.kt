package com.vesper.baubuild.core.network

/** Desktop (local testing): `-Dbau.apiBaseUrl=http://127.0.0.1:8000/` or env `BAU_API_BASE_URL`. */
internal actual fun debugApiBaseUrlOverride(): String? =
    System.getProperty("bau.apiBaseUrl")?.takeIf { it.isNotBlank() }
        ?: System.getenv("BAU_API_BASE_URL")?.takeIf { it.isNotBlank() }
