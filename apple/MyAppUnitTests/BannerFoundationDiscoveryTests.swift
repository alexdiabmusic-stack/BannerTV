import Foundation
import XCTest
@testable import BannerTV

final class BannerFoundationDiscoveryTests: XCTestCase {
    func testSharedScorePreservesTheNativeMatchUsedForNavigationAndPlayback() throws {
        let bundle = Bundle(for: Self.self)
        let providerURL = try XCTUnwrap(bundle.url(forResource: "live-regulation", withExtension: "json"))
        let contractURL = try XCTUnwrap(bundle.url(forResource: "score-v1", withExtension: "json"))
        let dto = try JSONDecoder().decode(NHLGameDTO.self, from: Data(contentsOf: providerURL))
        let league = League(name: "NHL", shortName: "NHL", path: "hockey/nhl", group: .hockey)
        let native = NHLGameMapper.match(try XCTUnwrap(NHLGameMapper.game(dto)), league: league)
        let payload = try String(contentsOf: contractURL, encoding: .utf8)
        let shared = try XCTUnwrap(BannerNHLFeed.matches(from: payload, league: league).first)

        // Match equality checks only its ID. These fields also drive favourites, EPG lookup and the live desk.
        XCTAssertEqual(shared.id, native.id)
        XCTAssertEqual(shared.canonicalID, native.canonicalID)
        XCTAssertEqual(shared.home, native.home)
        XCTAssertEqual(shared.away, native.away)
        XCTAssertEqual(shared.date, native.date)
        XCTAssertEqual(shared.name, native.name)
        XCTAssertEqual(shared.shortName, native.shortName)
        XCTAssertEqual(shared.state, native.state)
        XCTAssertEqual(shared.statusDetail, native.statusDetail)
        XCTAssertEqual(shared.liveContext, native.liveContext)
        XCTAssertEqual(shared.venue, native.venue)
        XCTAssertEqual(shared.broadcasts, native.broadcasts)
    }

    func testApplePresentationRejectsUnknownWireVersion() {
        XCTAssertThrowsError(try BannerNHLFeed.decode(#"{"version":9,"games":[]}"#))
    }

    @MainActor func testRealSharedNHLDiscoveryPreservesAppleNavigationIDs() async throws {
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
