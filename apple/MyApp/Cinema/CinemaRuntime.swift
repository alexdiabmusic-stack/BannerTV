#if os(iOS)
import Foundation
import Combine
import BannerFoundation

@MainActor
final class CinemaRuntime: ObservableObject {
    static let shared = CinemaRuntime()
    let foundation: AppleFoundation
    let bridge: CinemaBridge
    private var homeRequest: CinemaRequest?
    private var libraryRequest: CinemaRequest?
    private var searchRequest: CinemaRequest?
    private var addonRequest: CinemaRequest?

    @Published private(set) var home: CinemaHome?
    @Published private(set) var library = CinemaLibrary(saved: [], continuing: [])
    @Published private(set) var searchResults: [CinemaTitle] = []
    @Published private(set) var addons: [CinemaAddon] = []
    @Published var loadingHome = false
    @Published var searching = false
    @Published var hasMoreSearch = false
    @Published var message: String?
    @Published var addonsMessage: String?
    var language = Locale.current.identifier.replacingOccurrences(of: "_", with: "-")
    private var page = 1
    private var currentQuery = ""

    private init() {
        let base = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("BannerFoundationCache", isDirectory: true)
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        foundation = AppleFoundation(cacheDirectory: base.path, vault: KeychainAddonVault())
        bridge = foundation.cinema(readToken: { CinemaTokenVault.shared.read() }, stateStore: FileCinemaStateStore())
    }

    nonisolated static func decode<T: Decodable>(_ text: String?, _ error: String?, as: T.Type) -> Result<T, Error> {
        if let error { return .failure(CinemaUIError.message(error)) }
        guard let text, let data = text.data(using: .utf8) else { return .failure(CinemaUIError.message("No content was returned.")) }
        do { return .success(try JSONDecoder().decode(T.self, from: data)) }
        catch { return .failure(CinemaUIError.message("A media response could not be decoded.")) }
    }

    func refresh() {
        guard !CinemaTokenVault.shared.read().isEmpty else {
            message = "Connect TMDB metadata in Cinema Settings to browse titles."
            return
        }
        loadingHome = true
        message = nil
        homeRequest?.cancel()
        homeRequest = bridge.home(language: language) { [weak self] text, error in
            let result: Result<CinemaHome, Error> = Self.decode(text, error, as: CinemaHome.self)
            Task { @MainActor [weak self] in
                self?.loadingHome = false
                switch result {
                case .success(let value): self?.home = value
                case .failure(let error): self?.message = error.localizedDescription
                }
            }
        }
        refreshLibrary()
    }

    func refreshLibrary() {
        guard !CinemaTokenVault.shared.read().isEmpty else { return }
        libraryRequest?.cancel()
        libraryRequest = bridge.library { [weak self] text, error in
            let result: Result<CinemaLibrary, Error> = Self.decode(text, error, as: CinemaLibrary.self)
            Task { @MainActor [weak self] in
                if case .success(let value) = result { self?.library = value }
                else if case .failure(let error) = result { self?.message = error.localizedDescription }
            }
        }
    }

    func search(_ text: String) {
        searchRequest?.cancel()
        currentQuery = text.trimmingCharacters(in: .whitespacesAndNewlines)
        page = 1
        hasMoreSearch = false
        searchResults = []
        guard currentQuery.count >= 2 else { searching = false; return }
        performSearch()
    }
    func nextSearchPage() {
        guard !searching && hasMoreSearch else { return }
        page += 1
        performSearch()
    }
    private func performSearch() {
        searching = true
        let query = currentQuery
        let nextPage = page
        searchRequest = bridge.search(query: query, page: Int32(nextPage), language: language) { [weak self] raw, error in
            let result: Result<CinemaPage, Error> = Self.decode(raw, error, as: CinemaPage.self)
            Task { @MainActor [weak self] in
                guard let self, self.currentQuery == query, self.page == nextPage else { return }
                self.searching = false
                switch result {
                case .success(let result):
                    self.hasMoreSearch = result.hasMore
                    let joined = nextPage == 1 ? result.items : self.searchResults + result.items
                    var seen = Set<String>()
                    self.searchResults = joined.filter { seen.insert($0.stableID).inserted }
                case .failure(let error): self.message = error.localizedDescription
                }
            }
        }
    }

    func isSaved(_ card: CinemaTitle) -> Bool { library.saved.contains(card) }
    func resume(for card: CinemaTitle, season: Int = -1, episode: Int = -1) -> CinemaResume? {
        library.continuing.first {
            $0.card == card && (season < 0 || ($0.season == season && $0.episode == episode))
        }
    }
    func toggle(_ card: CinemaTitle) {
        guard let payload = try? JSONEncoder().encode(card), let json = String(data: payload, encoding: .utf8) else { return }
        _ = bridge.toggleSaved(cardJson: json) { [weak self] _, error in
            Task { @MainActor [weak self] in
                if let error { self?.message = error } else { self?.refreshLibrary() }
            }
        }
    }
    func record(_ selection: CinemaSelection, positionMillis: Int64, durationMillis: Int64) {
        guard durationMillis > 0, positionMillis >= 0,
              let data = try? JSONEncoder().encode(selection.title),
              let json = String(data: data, encoding: .utf8) else { return }
        _ = bridge.recordProgress(cardJson: json, season: Int32(selection.season), episode: Int32(selection.episode),
            positionMillis: positionMillis, durationMillis: durationMillis,
            updatedMillis: Int64(Date().timeIntervalSince1970 * 1000), episodeTitle: selection.episodeTitle) { [weak self] _, error in
                Task { @MainActor [weak self] in
                    if let error { self?.message = error } else { self?.refreshLibrary() }
                }
            }
    }

    func refreshAddons() {
        addonRequest?.cancel()
        addonRequest = bridge.listAddons { [weak self] raw, error in
            let result: Result<[CinemaAddon], Error> = Self.decode(raw, error, as: [CinemaAddon].self)
            Task { @MainActor [weak self] in
                switch result {
                case .success(let value): self?.addons = value
                case .failure(let error): self?.addonsMessage = error.localizedDescription
                }
            }
        }
    }
    func connectAddon(_ address: String, completion: @escaping (Bool) -> Void) {
        _ = bridge.addAddon(manifestUrl: address) { [weak self] raw, error in
            Task { @MainActor [weak self] in
                self?.addonsMessage = error
                if error == nil { self?.refreshAddons() }
                completion(error == nil && raw != nil)
            }
        }
    }
    func setAddon(_ addon: CinemaAddon, enabled: Bool) {
        _ = bridge.enableAddon(key: addon.key, enabled: enabled) { [weak self] _, error in
            Task { @MainActor [weak self] in
                if let error { self?.addonsMessage = error }
                self?.refreshAddons()
            }
        }
    }
    func deleteAddon(_ addon: CinemaAddon) {
        _ = bridge.removeAddon(key: addon.key) { [weak self] _, error in
            Task { @MainActor [weak self] in
                if let error { self?.addonsMessage = error }
                self?.refreshAddons()
            }
        }
    }

    func cancelTransientRequests() {
        homeRequest?.cancel()
        searchRequest?.cancel()
        libraryRequest?.cancel()
    }
}

enum CinemaUIError: LocalizedError {
    case message(String)
    var errorDescription: String? {
        switch self { case .message(let value): return value }
    }
}
#endif
