package com.vesper.baubuild.feature.auth

/** Android has no launch-argument hook; the auth flow always starts on Welcome. */
internal actual fun debugInitialAuthScreenName(): String? = null
