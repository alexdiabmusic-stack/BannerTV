import Foundation

// Run on Linux/macOS with:
// swiftc Swift/CinemaModels.swift Tests/main.swift -o /tmp/cinema-contract && /tmp/cinema-contract

let homeJSON = #"""
{"featured":[{"kind":"movie","id":603,"title":"Sample Movie","overview":"Example","posterPath":"/abcd.jpg","backdropPath":"/efgh.jpg","releaseDate":"2026-05-01","rating":8.1,"posterURL":"https://image.tmdb.org/t/p/w500/abcd.jpg","backdropURL":null,"logoURL":null}],"rows":[{"name":"Popular · Movies","items":[]}]}
"""#
let home = try JSONDecoder().decode(CinemaHome.self, from: Data(homeJSON.utf8))
precondition(home.featured.first?.stableID == "movie:603")
precondition(home.featured.first?.ratingLabel == "TMDB 8.1")
precondition(home.rows.count == 1)
let title = try XCTitleRoundTrip(home.featured[0])
precondition(title.stableID == home.featured[0].stableID)

let showJSON = #"""
{"card":{"kind":"tv","id":208,"title":"Demo Series","overview":"","posterPath":null,"backdropPath":null,"releaseDate":null,"rating":null,"posterURL":null,"backdropURL":null,"logoURL":null},"genres":[],"runtimeMinutes":45,"tagline":null,"cast":[],"directors":[],"creators":[],"seasons":[1,2],"recommendations":[],"imdbIdVerified":true}
"""#
let detail = try JSONDecoder().decode(CinemaDetail.self, from: Data(showJSON.utf8))
precondition(detail.card.isSeries && detail.seasons == [1,2])

let libraryJSON = #"""
{"saved":[],"continuing":[{"card":{"kind":"tv","id":208,"title":"Demo Series","overview":"","posterPath":null,"backdropPath":null,"releaseDate":null,"rating":null,"posterURL":null,"backdropURL":null,"logoURL":null},"season":2,"episode":3,"positionMillis":600000,"durationMillis":2400000,"updatedMillis":1780000000000,"episodeTitle":"An Episode"}]}
"""#
let library = try JSONDecoder().decode(CinemaLibrary.self, from: Data(libraryJSON.utf8))
precondition(library.continuing[0].fraction == 0.25)
precondition(library.continuing[0].minutesLeft == 30)

let unsupported = CinemaStream(provider: "Demo",name: "MKV",url: "https://example.com/video.mkv",headers: [:])
let supported = CinemaStream(provider: "Demo",name: "HLS",url: "https://example.com/master.m3u8",headers: [:])
let headerStream = CinemaStream(provider: "Demo",name: "HLS",url: "https://example.com/master.m3u8",headers: ["Authorization":"example"])
precondition(!unsupported.playableWithAVPlayer && supported.playableWithAVPlayer && !headerStream.playableWithAVPlayer)

func XCTitleRoundTrip(_ title: CinemaTitle) throws -> CinemaTitle {
    let data = try JSONEncoder().encode(title)
    return try JSONDecoder().decode(CinemaTitle.self, from: data)
}
print("Cinema wire DTO smoke test passed")
