#if os(iOS)
import Foundation

// These are independent UI transport objects. The canonical domain types and all
// API requests remain in BannerFoundation's common Kotlin model/repositories.
struct CinemaTitle: Codable, Hashable, Identifiable, Sendable {
    let kind: String
    let id: Int
    let title: String
    let overview: String
    let posterPath: String?
    let backdropPath: String?
    let releaseDate: String?
    let rating: Double?
    let posterURL: String?
    let backdropURL: String?
    let logoURL: String?

    var stableID: String { "\(kind):\(id)" }
    var isSeries: Bool { kind == "tv" }
    var year: String? { releaseDate.flatMap { $0.count >= 4 ? String($0.prefix(4)) : nil } }
    var poster: URL? { posterURL.flatMap(URL.init(string:)) }
    var backdrop: URL? { backdropURL.flatMap(URL.init(string:)) }
    var logo: URL? { logoURL.flatMap(URL.init(string:)) }
    var ratingLabel: String? { rating.map { String(format: "TMDB %.1f", $0) } }
    static func == (lhs: Self, rhs: Self) -> Bool { lhs.stableID == rhs.stableID }
    func hash(into hasher: inout Hasher) { hasher.combine(stableID) }
}

struct CinemaRow: Decodable, Sendable { let name: String; let items: [CinemaTitle] }
struct CinemaHome: Decodable, Sendable { let featured: [CinemaTitle]; let rows: [CinemaRow] }
struct CinemaPage: Decodable, Sendable { let items: [CinemaTitle]; let hasMore: Bool }
struct CinemaPerson: Decodable, Identifiable, Sendable {
    let name: String
    let role: String
    let portraitURL: String?
    var id: String { "\(name):\(role)" }
    var portrait: URL? { portraitURL.flatMap(URL.init(string:)) }
}
struct CinemaDetail: Decodable, Sendable {
    let card: CinemaTitle
    let genres: [String]
    let runtimeMinutes: Int?
    let tagline: String?
    let cast: [CinemaPerson]
    let directors: [String]
    let creators: [String]
    let seasons: [Int]
    let recommendations: [CinemaTitle]
    let imdbIdVerified: Bool
}
struct CinemaEpisode: Decodable, Identifiable, Sendable {
    let season: Int
    let number: Int
    let title: String
    let overview: String
    let stillURL: String?
    let runtimeMinutes: Int?
    let airDate: String?
    var id: String { "\(season):\(number)" }
    var still: URL? { stillURL.flatMap(URL.init(string:)) }
}
struct CinemaResume: Decodable, Identifiable, Sendable {
    let card: CinemaTitle
    let season: Int?
    let episode: Int?
    let positionMillis: Int64
    let durationMillis: Int64
    let updatedMillis: Int64
    let episodeTitle: String?
    var id: String { "\(card.stableID):\(season ?? -1):\(episode ?? -1)" }
    var fraction: Double { durationMillis > 0 ? min(1, Double(positionMillis) / Double(durationMillis)) : 0 }
    var minutesLeft: Int { max(1, Int((durationMillis - positionMillis) / 60_000)) }
}
struct CinemaLibrary: Decodable, Sendable { let saved: [CinemaTitle]; let continuing: [CinemaResume] }
struct CinemaStream: Decodable, Identifiable, Sendable {
    let provider: String
    let name: String
    let url: String
    let headers: [String: String]
    var id: String { "\(provider):\(name):\(url)" }
    var playableWithAVPlayer: Bool {
        guard let parsed = URL(string: url), parsed.scheme?.lowercased() == "https", headers.isEmpty else { return false }
        // AVPlayer does not reliably support arbitrary MKV/AVI, custom transport or HTTP headers.
        return ["m3u8", "mp4", "m4v", "mov"].contains(parsed.pathExtension.lowercased())
    }
}
struct CinemaSourceResults: Decodable, Sendable {
    let sources: [CinemaStream]
    let enabledProviders: Int
    let failedProviders: Int
}
struct CinemaAddon: Decodable, Identifiable, Sendable {
    let key: String
    let name: String
    let enabled: Bool
    var id: String { key }
}

struct CinemaSelection: Identifiable, Hashable {
    let title: CinemaTitle
    let season: Int
    let episode: Int
    let episodeTitle: String?
    var id: String { "\(title.stableID):\(season):\(episode)" }
    static func movie(_ title: CinemaTitle) -> Self { Self(title: title, season: -1, episode: -1, episodeTitle: nil) }
}

struct CinemaPlayback: Identifiable {
    let id = UUID()
    let selection: CinemaSelection
    let stream: CinemaStream
    let startMillis: Int64
}
#endif
