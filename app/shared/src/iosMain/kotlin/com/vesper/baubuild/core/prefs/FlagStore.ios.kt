package com.vesper.baubuild.core.prefs

import platform.Foundation.NSUserDefaults

actual fun platformFlagStore(): FlagStore = UserDefaultsFlagStore

private object UserDefaultsFlagStore : FlagStore {
    private const val PREFIX = "baubuild.flag."
    override fun get(key: String): Boolean = NSUserDefaults.standardUserDefaults.boolForKey(PREFIX + key)
    override fun set(key: String, value: Boolean) = NSUserDefaults.standardUserDefaults.setBool(value, PREFIX + key)
}
