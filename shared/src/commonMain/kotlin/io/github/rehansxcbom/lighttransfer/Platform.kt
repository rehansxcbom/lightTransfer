package io.github.rehansxcbom.lighttransfer

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform