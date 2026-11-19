package com.vesper.baubuild

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform