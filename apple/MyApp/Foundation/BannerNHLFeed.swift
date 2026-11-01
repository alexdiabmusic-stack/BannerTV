import Foundation

/// This wire format is transient. Persisted Apple match and team identifiers retain their existing representation.
nonisolated struct BannerNHLFeed: Decodable {
    struct Side: Decodable {
        let id, name, abbreviation: String
        let score: String?
        let winner: Bool
    }

    struct Game: Decodable {
        let id, name, shortName, status: String
        let state: String
        let startMillis: Int64
        let home, away: Side
        let broadcasts: [String]
        let venue, clock, periodLabel: String?
        let seconds, period: Int?
        let running: Bool?
        let phase: String?
        let intermission, extraPeriod: Bool?
    }

    let version: Int
    let games: [Game]

    static func decode(_ payload: String) throws -> Self {
        let feed = try JSONDecoder().decode(Self.self, from: Data(payload.utf8))
        guard feed.version == 1 else { throw URLError(.cannotParseResponse) }
        return feed
    }
}
