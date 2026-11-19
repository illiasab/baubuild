@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.vesper.baubuild.core.session

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

actual fun platformTokenStore(): TokenStore = KeychainTokenStore()

/**
 * Generic-password Keychain item, readable after first unlock, never synced to iCloud or restored to
 * another device (`AfterFirstUnlockThisDeviceOnly`).
 */
class KeychainTokenStore(
    private val service: String = "com.vesper.baubuild.session",
    private val account: String = "opaque_token",
) : TokenStore {

    override suspend fun read(): String? = withContext(Dispatchers.IO) {
        memScoped {
            val result = alloc<CFTypeRefVar>()
            val status = withQuery(kSecReturnData to kCFBooleanTrue, kSecMatchLimit to kSecMatchLimitOne) {
                SecItemCopyMatching(it, result.ptr)
            }
            if (status != errSecSuccess) return@memScoped null
            val data = CFBridgingRelease(result.value) as? NSData ?: return@memScoped null
            NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString()
        }
    }

    override suspend fun write(token: String) = withContext(Dispatchers.IO) {
        withQuery { SecItemDelete(it) }
        @Suppress("CAST_NEVER_SUCCEEDS")
        val data = (token as NSString).dataUsingEncoding(NSUTF8StringEncoding) ?: error("Token is not UTF-8")
        val cfData = CFBridgingRetain(data)
        try {
            val status = withQuery(kSecValueData to cfData, kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly) {
                SecItemAdd(it, null)
            }
            check(status == errSecSuccess) { "Keychain write failed: $status" }
        } finally {
            CFRelease(cfData)
        }
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        withQuery { SecItemDelete(it) }
        Unit
    }

    /** Builds `{class: genericPassword, service, account} + extra`, runs [block], releases everything. */
    private inline fun <T> withQuery(vararg extra: Pair<CFTypeRef?, CFTypeRef?>, block: (CFDictionaryRef?) -> T): T {
        val cfService = CFBridgingRetain(service)
        val cfAccount = CFBridgingRetain(account)
        val dict = CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
        try {
            CFDictionaryAddValue(dict, kSecClass, kSecClassGenericPassword)
            CFDictionaryAddValue(dict, kSecAttrService, cfService)
            CFDictionaryAddValue(dict, kSecAttrAccount, cfAccount)
            extra.forEach { (k, v) -> CFDictionaryAddValue(dict, k, v) }
            return block(dict)
        } finally {
            CFRelease(dict)
            CFRelease(cfService)
            CFRelease(cfAccount)
        }
    }
}
