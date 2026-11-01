#if BANNER_SHARED_FOUNDATION
import Foundation
import Security
import BannerFoundation

/// Device-only add-on configuration. The normal cloud-backup document never includes these URLs.
/// A locked keychain is an error, never an empty library that could overwrite saved credentials.
final class KeychainAddonVault: NSObject, AddonVault {
    private let service = "tv.banner.foundation.addons.v1"
    private let account = "configured-manifests"

    private var key: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: service,
         kSecAttrAccount as String: account,
         kSecAttrSynchronizable as String: false]
    }

    func read() throws -> String? {
        var query = key
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess,
              let data = result as? Data,
              let value = String(data: data, encoding: .utf8) else {
            throw failure(status)
        }
        return value
    }

    func replace(value: String) throws {
        let data = Data(value.utf8)
        let update: [String: Any] = [kSecValueData as String: data,
                                    kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly]
        var status = SecItemUpdate(key as CFDictionary, update as CFDictionary)
        if status == errSecItemNotFound {
            status = SecItemAdd(key.merging(update) { _, new in new } as CFDictionary, nil)
        }
        guard status == errSecSuccess else { throw failure(status) }
    }

    private func failure(_ status: OSStatus) -> NSError {
        NSError(domain: "BannerFoundation.Keychain", code: Int(status),
                userInfo: [NSLocalizedDescriptionKey: "The add-on configuration could not be unlocked or saved."])
    }
}
#endif
