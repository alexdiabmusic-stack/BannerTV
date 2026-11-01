import Foundation

nonisolated extension BannerNHLFeed.Side {
    func native(in league: League) -> TeamSide {
        TeamSide(displayName: name, shortName: abbreviation, abbreviation: abbreviation,
                 logoURL: .bannerImageAsset(named: "NHLLogo_\(abbreviation)"), score: score,
                 record: nil, isWinner: winner, teamID: id, canonicalIDString: "team:\(league.bannerKey):nhl:\(id)")
    }
}

nonisolated extension BannerNHLFeed.Game {
    func native(in league: League) throws -> Match {
        guard let nativeState = GameState(rawValue: state) else { throw URLError(.cannotParseResponse) }
        let live = MatchLiveContext(clock: MatchClock(displayValue: clock, remainingSeconds: seconds, isRunning: running),
                                    period: MatchPeriod(number: period, displayName: periodLabel))
        var match = Match(id: id, league: league, date: Date(timeIntervalSince1970: Double(startMillis) / 1000),
                          name: name, shortName: shortName, state: nativeState, statusDetail: status,
                          home: home.native(in: league), away: away.native(in: league), broadcasts: broadcasts,
                          venue: venue, liveContext: live)
        match.canonicalID = "game:\(league.bannerKey):nhl:\(id)"
        return match
    }
}

nonisolated extension BannerNHLFeed {
    static func matches(from payload: String, league: League) throws -> [Match] {
        guard league.path == "hockey/nhl" else { throw URLError(.cannotParseResponse) }
        return try decode(payload).games.map { try $0.native(in: league) }
    }
}
