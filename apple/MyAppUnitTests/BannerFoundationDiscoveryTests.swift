import Foundation
import XCTest
@testable import BannerTV

final class BannerFoundationDiscoveryTests: XCTestCase {
    func testApplePresentationRejectsUnknownWireVersion() {
        XCTAssertThrowsError(try BannerNHLFeed.decode(#"{"version":9,"games":[]}"#))
    }

    func testRealSharedNHLDiscoveryPreservesAppleNavigationIDs() async throws {
        try XCTSkipUnless(ProcessInfo.processInfo.environment["BANNER_LIVE_PROBE"] == "1", "Explicit live integration run")
        let provider = NHLProvider()
        XCTAssertTrue(provider.usesSharedDiscovery)
        let league = League(name: "NHL", shortName: "NHL", path: "hockey/nhl", group: .hockey)
        let day = try XCTUnwrap(ISO8601DateFormatter().date(from: "2024-06-24T16:00:00Z"))
        let scores = try await SportsRepository.shared.legacyScoreboard(for: league, on: day)
        let game = try XCTUnwrap(scores.first { $0.id == "2023030417" })
        XCTAssertEqual(game.canonicalID, "game:\(league.bannerKey):nhl:2023030417")
        XCTAssertEqual(game.home.teamID, "13")
        XCTAssertEqual(game.away.teamID, "22")
        XCTAssertEqual(game.home.logoURL?.scheme, "banner-asset")
        let schedule = try await SportsRepository.shared.legacyScoreboards(for: league, starting: day, days: 1)
        XCTAssertTrue(schedule.contains { $0.canonicalID == game.canonicalID })
    }
}
