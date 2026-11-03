#if os(iOS)
import Foundation
import Combine

/// Nuvio-style TMDB configuration. Preferences are nonsecret and are stored locally.
/// The actual personal API key or v4 Read Access Token lives in CinemaTokenVault (Keychain).
@MainActor
final class CinemaTMDBPreferences: ObservableObject {
    static let shared = CinemaTMDBPreferences()
    private let defaults = UserDefaults.standard
    private static let prefix = "tv.banner.cinema.tmdb.settings."

    @Published var enabled: Bool {
        didSet { defaults.set(enabled, forKey: Self.prefix + "enabled") }
    }
    @Published var language: String {
        didSet { defaults.set(language, forKey: Self.prefix + "language") }
    }
    @Published var useArtwork: Bool {
        didSet { defaults.set(useArtwork, forKey: Self.prefix + "artwork") }
    }
    @Published var useCredits: Bool {
        didSet { defaults.set(useCredits, forKey: Self.prefix + "credits") }
    }
    @Published var useEpisodes: Bool {
        didSet { defaults.set(useEpisodes, forKey: Self.prefix + "episodes") }
    }
    @Published var useRecommendations: Bool {
        didSet { defaults.set(useRecommendations, forKey: Self.prefix + "recommendations") }
    }

    private init() {
        // Avoid referencing self before all @Published stored properties are initialized.
        let localDefaults = UserDefaults.standard
        func flag(_ name: String) -> Bool {
            let key = Self.prefix + name
            return localDefaults.object(forKey: key) == nil ? true : localDefaults.bool(forKey: key)
        }
        enabled = flag("enabled")
        let initialLanguage = localDefaults.string(forKey: Self.prefix + "language")
            ?? Locale.preferredLanguages.first ?? "en-US"
        language = Self.normalizeLanguage(initialLanguage)
        useArtwork = flag("artwork")
        useCredits = flag("credits")
        useEpisodes = flag("episodes")
        useRecommendations = flag("recommendations")
    }

    static func normalizeLanguage(_ input: String) -> String {
        let text = input.trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "_", with: "-")
        let parts = text.split(separator: "-", omittingEmptySubsequences: false)
        guard let base = parts.first, (2...3).contains(base.count),
              base.allSatisfy({ $0.isASCII && $0.isLetter }) else { return "en-US" }
        if parts.count == 1 { return String(base).lowercased() }
        guard parts.count == 2, parts[1].count == 2,
              parts[1].allSatisfy({ $0.isASCII && $0.isLetter }) else { return "en-US" }
        return "\(base.lowercased())-\(parts[1].uppercased())"
    }

    static let supportedLanguages: [(label: String, code: String)] = [
        ("English (US)", "en-US"), ("English (Canada)", "en-CA"),
        ("Français (Canada)", "fr-CA"), ("Français (France)", "fr-FR"),
        ("Español", "es-ES"), ("Deutsch", "de-DE"),
        ("Italiano", "it-IT"), ("Português (Brasil)", "pt-BR"),
        ("日本語", "ja-JP")
    ]
}
#endif
