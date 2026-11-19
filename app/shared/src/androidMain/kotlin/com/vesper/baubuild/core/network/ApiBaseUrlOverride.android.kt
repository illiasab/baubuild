package com.vesper.baubuild.core.network

/**
 * Android has no launch-argument override (always production). For a local build, call
 * `AppGraph.init(AppGraph(ApiConfig(baseUrl = "http://10.0.2.2:8000/")))` in `MainActivity.onCreate`.
 */
internal actual fun debugApiBaseUrlOverride(): String? = null
