import Foundation

@main
enum CheckNHLFeed {
    static func main() throws {
        let file = CommandLine.arguments[1]
        let payload = try String(contentsOfFile: file, encoding: .utf8)
        let feed = try BannerNHLFeed.decode(payload)
        guard let game = feed.games.first(where: { $0.id == CommandLine.arguments[2] }),
              Int(game.home.id) != nil, Int(game.away.id) != nil, game.startMillis > 0 else {
            throw URLError(.cannotParseResponse)
        }
        if game.id == "2023020204" {
            guard game.home.id == "7", game.away.id == "30", game.period == 2,
                  game.clock == "08:42", game.running == true, game.seconds == 522,
                  game.broadcasts.contains("SN1") else { throw URLError(.cannotParseResponse) }
        }
        print("Swift decoded discovery v\(feed.version): \(game.away.abbreviation) \(game.away.score ?? "–") at \(game.home.abbreviation) \(game.home.score ?? "–"), \(game.state), game \(game.id)")
    }
}
