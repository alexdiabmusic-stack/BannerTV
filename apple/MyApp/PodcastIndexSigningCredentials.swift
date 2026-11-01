import Foundation
import Security

/// Provision this service independently of playlist, fantasy and user account credentials.
nonisolated enum PodcastIndexSigningCredentials {
    private struct Pair: Decodable { let key: String; let secret: String }

    static func load() throws -> (String, String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "com.alexdiab.BannerTV.podcast-index",
            kSecAttrAccount as String: "signing",
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound {
            #if DEBUG
            let environment = ProcessInfo.processInfo.environment
            if let key = environment["PODCAST_INDEX_KEY"], !key.isEmpty,
               let secret = environment["PODCAST_INDEX_SECRET"], !secret.isEmpty {
                return (key, secret)
            }
            #endif
            throw URLError(.userAuthenticationRequired)
        }
        guard status == errSecSuccess else { throw KeychainStore.KeychainError.unhandledStatus(status) }
        guard let data = result as? Data, let pair = try? JSONDecoder().decode(Pair.self, from: data),
              !pair.key.isEmpty, !pair.secret.isEmpty else { throw URLError(.cannotDecodeContentData) }
        return (pair.key, pair.secret)
    }
}
