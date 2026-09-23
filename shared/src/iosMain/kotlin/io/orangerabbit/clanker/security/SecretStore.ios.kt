package io.orangerabbit.clanker.security

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFTypeRefVar
import platform.Foundation.NSData
import platform.Foundation.NSMutableDictionary
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemUpdate
import platform.Security.errSecDuplicateItem
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

private const val KEYCHAIN_SERVICE = "io.orangerabbit.clanker"

/**
 * iOS actual: stores secrets in the system Keychain under kSecClassGenericPassword keyed by
 * [KEYCHAIN_SERVICE] + the caller-supplied [id]. Toll-free bridging from NSMutableDictionary to
 * CFDictionaryRef is valid per Apple's Core Foundation documentation.
 *
 * NOTE: this file cannot be compiled without a macOS toolchain and is CI/macOS-verified.
 *
 * Fail-closed: every non-success OSStatus is thrown as [IllegalStateException].
 */
@OptIn(ExperimentalForeignApi::class)
actual class PlatformSecretStore actual constructor(@Suppress("UNUSED_PARAMETER") context: Any) :
    SecretStore {

    override suspend fun get(id: String): String? = memScoped {
        @Suppress("UNCHECKED_CAST")
        val query = NSMutableDictionary().apply {
            setObject(kSecClassGenericPassword, forKey = kSecClass as NSString)
            setObject(KEYCHAIN_SERVICE, forKey = kSecAttrService as NSString)
            setObject(id, forKey = kSecAttrAccount as NSString)
            setObject(true, forKey = kSecReturnData as NSString)
            setObject(kSecMatchLimitOne, forKey = kSecMatchLimit as NSString)
        }

        val result = alloc<CFTypeRefVar>()
        val status = SecItemCopyMatching(query as CFDictionaryRef, result.ptr)

        @Suppress("UNCHECKED_CAST")
        when (status) {
            errSecItemNotFound -> null
            errSecSuccess -> {
                val data = result.value as? NSData
                    ?: throw IllegalStateException("Keychain returned unexpected type for id=$id")
                NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString()
                    ?: throw IllegalStateException("Keychain data not valid UTF-8 for id=$id")
            }

            else -> throw IllegalStateException("Keychain read failed: OSStatus=$status id=$id")
        }
    }

    override suspend fun getOrPut(id: String, generator: suspend () -> String): String {
        get(id)?.let { return it }
        val value = generator() // exceptions propagate — never fall back to plaintext
        save(id, value)
        return value
    }

    override suspend fun put(id: String, value: String) {
        // save() already handles both SecItemAdd (new) and SecItemUpdate (existing).
        save(id, value)
    }

    @Suppress("UNCHECKED_CAST")
    private fun save(id: String, value: String) {
        val data = (NSString.create(string = value) as NSString).dataUsingEncoding(NSUTF8StringEncoding)
            ?: throw IllegalStateException("Failed to encode secret value for id=$id")

        val addQuery = NSMutableDictionary().apply {
            setObject(kSecClassGenericPassword, forKey = kSecClass as NSString)
            setObject(KEYCHAIN_SERVICE, forKey = kSecAttrService as NSString)
            setObject(id, forKey = kSecAttrAccount as NSString)
            setObject(data, forKey = kSecValueData as NSString)
        }

        when (val status = SecItemAdd(addQuery as CFDictionaryRef, null)) {
            errSecSuccess -> return
            errSecDuplicateItem -> {
                val searchQuery = NSMutableDictionary().apply {
                    setObject(kSecClassGenericPassword, forKey = kSecClass as NSString)
                    setObject(KEYCHAIN_SERVICE, forKey = kSecAttrService as NSString)
                    setObject(id, forKey = kSecAttrAccount as NSString)
                }
                val updateAttrs = NSMutableDictionary().apply {
                    setObject(data, forKey = kSecValueData as NSString)
                }
                val updateStatus = SecItemUpdate(
                    searchQuery as CFDictionaryRef,
                    updateAttrs as CFDictionaryRef,
                )
                if (updateStatus != errSecSuccess) {
                    throw IllegalStateException("Keychain update failed: OSStatus=$updateStatus id=$id")
                }
            }

            else -> throw IllegalStateException("Keychain add failed: OSStatus=$status id=$id")
        }
    }
}
