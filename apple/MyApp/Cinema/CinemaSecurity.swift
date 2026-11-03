#if os(iOS)
import Foundation
import Security
import BannerFoundation

/// TMDB credential is NOT embedded in a public build. Provision via your secure
/// settings/backend. This vault is for development and personal installs.
final class CinemaTokenVault: @unchecked Sendable {
    static let shared = CinemaTokenVault()
    private init() {}
    private let service = "tv.banner.cinema.tmdb.v1"
    private let account = "tmdb-read-token"

    private var query: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: service,
         kSecAttrAccount as String: account,
         kSecAttrSynchronizable as String: false]
    }
    func read() -> String {
        var q = query
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var value: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &value) == errSecSuccess,
              let data = value as? Data else { return "" }
        return String(data: data, encoding: .utf8) ?? ""
    }
    func set(_ token: String) throws {
        let data = Data(token.trimmingCharacters(in: .whitespacesAndNewlines).utf8)
        guard !data.isEmpty else { throw CinemaStorageError.invalidToken }
        let attributes: [String: Any] = [kSecValueData as String: data,
                         kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
        var result = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if result == errSecItemNotFound { result = SecItemAdd(query.merging(attributes) { _, v in v } as CFDictionary, nil) }
        guard result == errSecSuccess else { throw CinemaStorageError.unavailable }
    }
    func delete() throws {
        let status = SecItemDelete(query as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw CinemaStorageError.unavailable
        }
    }
}

enum CinemaStorageError: LocalizedError {
    case invalidToken, unavailable
    var errorDescription: String? { "Could not access secure cinema configuration." }
}

/// Non-secret watchlist/progress. Failed reads THROW rather than appearing empty.
final class FileCinemaStateStore: NSObject, CinemaStateStore {
    private let url: URL
    init(fileManager: FileManager = .default) {
        let base = fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        url = base.appendingPathComponent("BannerCinema", isDirectory: true)
            .appendingPathComponent("media-library.json")
        super.init()
    }
    func read() throws -> String? {
        guard FileManager.default.fileExists(atPath: url.path) else { return nil }
        return try String(contentsOf: url, encoding: .utf8)
    }
    func replace(value: String) throws {
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try Data(value.utf8).write(to: url, options: [.atomic, .completeFileProtectionUnlessOpen])
    }
}
#endif
