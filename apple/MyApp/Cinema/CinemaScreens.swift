#if os(iOS)
import SwiftUI
import BannerFoundation

private enum CinemaStyle {
    static let background = Color(red: 9/255, green: 12/255, blue: 18/255)
    static let surface = Color(red: 27/255, green: 32/255, blue: 41/255)
    static let secondary = Color(red: 173/255, green: 179/255, blue: 190/255)
    static let radius: CGFloat = 13
}

/// Drop-in cinema destination. Embed beside existing Sports and Live TV tabs.
struct BannerCinemaRoot: View {
    @StateObject private var model = CinemaRuntime.shared
    @State private var query = ""
    @State private var selection: CinemaTitle?
    @State private var showSettings = false
    @State private var showSaved = false
    @State private var showingSearch = false
    @State private var debounce: Task<Void, Never>?

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 30) {
                    if showingSearch {
                        searchBody
                    } else if showSaved {
                        CinemaPosterGrid(items: model.library.saved) { selection = $0 }
                    } else {
                        if let home = model.home {
                            if !home.featured.isEmpty { CinemaHeroPager(cards: home.featured) { selection = $0 } }
                            if !model.library.continuing.isEmpty {
                                CinemaContinueRail(items: model.library.continuing) { selection = $0.card }
                            }
                            ForEach(home.rows.indices, id: \.self) { index in
                                CinemaPosterRail(title: home.rows[index].name, items: home.rows[index].items) { selection = $0 }
                            }
                        }
                        if model.loadingHome { ProgressView("Finding something worth watching…").frame(maxWidth: .infinity).padding(24) }
                    }
                    if let message = model.message {
                        CinemaNotice(message: message) { model.refresh() }
                        if !model.tmdbPreferences.enabled || CinemaTokenVault.shared.read().isEmpty {
                            Button {
                                showSettings = true
                            } label: {
                                Label("Configure TMDB", systemImage: "gearshape")
                                    .frame(maxWidth: .infinity)
                            }
                            .buttonStyle(.borderedProminent)
                            .padding(.horizontal, 18)
                        }
                    }
                    Text("This product uses the TMDB API but is not endorsed or certified by TMDB.")
                        .font(.caption2).foregroundStyle(CinemaStyle.secondary).padding(.horizontal, 18)
                }
                .padding(.bottom, 36)
            }
            .background(CinemaStyle.background.ignoresSafeArea())
            .navigationTitle(showSaved ? "My list" : "Movies & Shows")
            .toolbar {
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Button {
                        showSaved.toggle(); showingSearch = false
                    } label: { Image(systemName: showSaved ? "square.grid.2x2" : "bookmark") }
                        .accessibilityLabel(showSaved ? "Browse movies" : "My list")
                    Button {
                        showingSearch.toggle(); showSaved = false
                    } label: { Image(systemName: "magnifyingglass") }
                        .accessibilityLabel("Search movies and shows")
                    Button { showSettings = true } label: { Image(systemName: "slider.horizontal.3") }
                        .accessibilityLabel("Cinema settings")
                }
            }
            .navigationDestination(item: $selection) { title in
                BannerCinemaDetail(title: title)
            }
            .sheet(isPresented: $showSettings) { BannerCinemaSettings() }
            .task { model.refresh() }
            .refreshable { model.refresh() }
            .onDisappear { debounce?.cancel(); model.cancelTransientRequests() }
        }
        .tint(.white)
        .preferredColorScheme(.dark)
    }

    private var searchBody: some View {
        VStack(alignment: .leading, spacing: 16) {
            TextField("Movie or series title", text: $query)
                .textFieldStyle(.roundedBorder)
                .textInputAutocapitalization(.words)
                .autocorrectionDisabled()
                .padding(.horizontal, 18)
                .onChange(of: query) { _, next in
                    debounce?.cancel()
                    debounce = Task {
                        try? await Task.sleep(for: .milliseconds(350))
                        guard !Task.isCancelled else { return }
                        model.search(next)
                    }
                }
            if query.count < 2 {
                Text("Search movies and series by title.").foregroundStyle(CinemaStyle.secondary).padding(.horizontal, 18)
            } else {
                CinemaPosterGrid(items: model.searchResults) { selection = $0 }
                if model.searching { ProgressView().frame(maxWidth: .infinity).padding() }
                else if model.hasMoreSearch {
                    Button("Load more") { model.nextSearchPage() }
                        .frame(maxWidth: .infinity).buttonStyle(.bordered).padding(.horizontal, 18)
                }
            }
        }
    }
}

private struct CinemaHeroPager: View {
    let cards: [CinemaTitle]
    let onOpen: (CinemaTitle) -> Void
    @State private var current = 0
    var body: some View {
        VStack(spacing: 2) {
            TabView(selection: $current) {
                ForEach(cards.indices, id: \.self) { index in
                    CinemaHero(card: cards[index], action: { onOpen(cards[index]) }).tag(index)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .always))
            .frame(height: min(540, max(360, UIScreen.main.bounds.height * 0.63)))
            .accessibilityLabel("Featured movies and series")
        }
    }
}

private struct CinemaHero: View {
    let card: CinemaTitle
    let action: () -> Void
    var body: some View {
        GeometryReader { geometry in
            ZStack(alignment: .bottom) {
                CinemaRemoteImage(url: card.backdrop ?? card.poster, fill: true)
                    .frame(width: geometry.size.width, height: geometry.size.height).clipped()
                LinearGradient(stops: [
                    .init(color: CinemaStyle.background.opacity(0.05), location: 0),
                    .init(color: CinemaStyle.background.opacity(0.16), location: 0.35),
                    .init(color: CinemaStyle.background.opacity(0.96), location: 0.81),
                    .init(color: CinemaStyle.background, location: 1)
                ], startPoint: .top, endPoint: .bottom)
                VStack(spacing: 12) {
                    if let url = card.logo {
                        CinemaRemoteImage(url: url, fill: false)
                            .frame(maxWidth: 280).frame(height: 72)
                    } else {
                        Text(card.title).font(.system(size: 32, weight: .bold, design: .rounded))
                            .lineLimit(2).multilineTextAlignment(.center)
                    }
                    HStack(spacing: 8) {
                        Text(card.isSeries ? "Series" : "Movie")
                        if let year = card.year { Text("·"); Text(year) }
                        if let rating = card.ratingLabel { Text("·"); Text(rating) }
                    }
                    .font(.subheadline.weight(.medium)).foregroundStyle(CinemaStyle.secondary)
                    Button(action: action) {
                        Label("View details", systemImage: "play.fill")
                            .font(.headline).foregroundStyle(.black).frame(maxWidth: 240)
                            .padding(.vertical, 13)
                    }
                    .buttonStyle(.plain).background(.white, in: Capsule())
                    .accessibilityLabel("View \(card.title) details")
                }
                .padding(.horizontal, 20).padding(.bottom, 30)
            }
        }
    }
}

private struct CinemaPosterRail: View {
    let title: String
    let items: [CinemaTitle]
    let open: (CinemaTitle) -> Void
    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(title).font(.title3.bold()).padding(.horizontal, 18)
            ScrollView(.horizontal) {
                LazyHStack(alignment: .top, spacing: 12) {
                    ForEach(items) { item in
                        Button { open(item) } label: { CinemaPoster(title: item).frame(width: 138) }
                            .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 18)
            }
            .scrollIndicators(.hidden)
        }
    }
}

private struct CinemaPosterGrid: View {
    let items: [CinemaTitle]
    let open: (CinemaTitle) -> Void
    private let columns = [GridItem(.adaptive(minimum: 130, maximum: 190), spacing: 14, alignment: .top)]
    var body: some View {
        LazyVGrid(columns: columns, alignment: .center, spacing: 22) {
            ForEach(items) { item in
                Button { open(item) } label: { CinemaPoster(title: item) }
                    .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 18)
    }
}

private struct CinemaPoster: View {
    let title: CinemaTitle
    var body: some View {
        VStack(alignment: .leading, spacing: 7) {
            CinemaRemoteImage(url: title.poster, fill: true)
                .aspectRatio(2/3, contentMode: .fit)
                .clipShape(RoundedRectangle(cornerRadius: CinemaStyle.radius))
            Text(title.title).font(.subheadline.weight(.semibold)).lineLimit(2)
            HStack {
                if let year = title.year { Text(year) }
                if let score = title.ratingLabel { Spacer(minLength: 3); Text(score) }
            }
            .font(.caption2).foregroundStyle(CinemaStyle.secondary).lineLimit(1)
        }
        .accessibilityElement(children: .combine)
    }
}

private struct CinemaContinueRail: View {
    let items: [CinemaResume]
    let open: (CinemaResume) -> Void
    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Continue Watching").font(.title3.bold()).padding(.horizontal, 18)
            ScrollView(.horizontal) {
                LazyHStack(spacing: 14) {
                    ForEach(items) { item in
                        Button { open(item) } label: {
                            VStack(alignment: .leading, spacing: 8) {
                                CinemaRemoteImage(url: item.card.backdrop ?? item.card.poster, fill: true)
                                    .frame(width: 250, height: 140).clipped()
                                    .clipShape(RoundedRectangle(cornerRadius: 12))
                                    .overlay(alignment: .topTrailing) {
                                        Text("\(item.minutesLeft)m left")
                                            .font(.caption.bold()).padding(5)
                                            .background(.black.opacity(0.78), in: RoundedRectangle(cornerRadius: 5))
                                            .padding(8)
                                    }
                                GeometryReader { g in
                                    ZStack(alignment: .leading) {
                                        Capsule().fill(CinemaStyle.surface)
                                        Capsule().fill(.white).frame(width: g.size.width * item.fraction)
                                    }
                                }
                                .frame(width: 250, height: 3)
                                Text(item.card.title).font(.subheadline.bold()).lineLimit(1)
                                if let season = item.season, let episode = item.episode {
                                    Text("S\(season) · E\(episode)").font(.caption).foregroundStyle(CinemaStyle.secondary)
                                }
                            }
                            .frame(width: 250, alignment: .leading)
                        }.buttonStyle(.plain)
                    }
                }.padding(.horizontal, 18)
            }
            .scrollIndicators(.hidden)
        }
    }
}

struct CinemaRemoteImage: View {
    let url: URL?
    let fill: Bool
    var body: some View {
        GeometryReader { g in
            AsyncImage(url: url) { phase in
                switch phase {
                case .success(let image):
                    image.resizable().aspectRatio(contentMode: fill ? .fill : .fit)
                        .frame(width: g.size.width, height: g.size.height).clipped()
                case .empty: CinemaStyle.surface.overlay { ProgressView().tint(.gray) }
                case .failure: CinemaStyle.surface.overlay {
                    Image(systemName: "film").foregroundStyle(.gray).font(.largeTitle)
                }
                @unknown default: CinemaStyle.surface
                }
            }
        }
        .background(CinemaStyle.surface)
    }
}

private struct CinemaNotice: View {
    let message: String
    let retry: () -> Void
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(message).foregroundStyle(CinemaStyle.secondary)
            Button("Try again", action: retry).buttonStyle(.bordered)
        }
        .padding(18).frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct BannerCinemaDetail: View {
    let title: CinemaTitle
    @StateObject private var model = CinemaRuntime.shared
    @State private var detail: CinemaDetail?
    @State private var episodes: [CinemaEpisode] = []
    @State private var selectedSeason: Int?
    @State private var error: String?
    @State private var loading = false
    @State private var sourceSelection: CinemaSelection?
    @State private var relatedTitle: CinemaTitle?
    @State private var selectedPlayback: CinemaPlayback?
    @State private var detailRequest: CinemaRequest?
    @State private var episodeRequest: CinemaRequest?

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 22) {
                if let detail {
                    CinemaHero(card: detail.card, action: {
                        if title.isSeries {
                            if let resume = model.resume(for: title), let s = resume.season, let e = resume.episode {
                                sourceSelection = CinemaSelection(title: title, season: s, episode: e, episodeTitle: resume.episodeTitle)
                            } else if let first = episodes.first {
                                select(first)
                            }
                        } else { sourceSelection = .movie(title) }
                    })
                    .frame(height: 390)
                    detailBody(detail)
                } else if loading { ProgressView("Loading details…").frame(maxWidth: .infinity).padding(70) }
                if let error { Text(error).foregroundStyle(.orange).padding(.horizontal, 18) }
            }.padding(.bottom, 38)
        }
        .background(CinemaStyle.background.ignoresSafeArea())
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            Button { model.toggle(title) } label: {
                Image(systemName: model.isSaved(title) ? "bookmark.fill" : "bookmark")
            }
            .accessibilityLabel(model.isSaved(title) ? "Remove from My list" : "Add to My list")
        }
        .navigationDestination(item: $relatedTitle) { next in BannerCinemaDetail(title: next) }
        .task(id: title.stableID) { loadDetail() }
        .onDisappear { detailRequest?.cancel(); episodeRequest?.cancel() }
        .sheet(item: $sourceSelection) { selection in
            CinemaSourcePicker(selection: selection) { playback in selectedPlayback = playback }
        }
        .fullScreenCover(item: $selectedPlayback) { playback in
            BannerCinemaPlayer(playback: playback)
        }
    }

    @ViewBuilder private func detailBody(_ details: CinemaDetail) -> some View {
        VStack(alignment: .leading, spacing: 20) {
            HStack(spacing: 10) {
                if let year = details.card.year { Text(year) }
                if let runtime = details.runtimeMinutes { Text("·"); Text("\(runtime) min") }
                if let score = details.card.ratingLabel { Text("·"); Text(score) }
            }
            .font(.subheadline.bold()).foregroundStyle(CinemaStyle.secondary)
            if !details.genres.isEmpty {
                Text(details.genres.joined(separator: " · ")).font(.subheadline).foregroundStyle(CinemaStyle.secondary)
            }
            if let tagline = details.tagline, !tagline.isEmpty { Text(tagline).italic().foregroundStyle(CinemaStyle.secondary) }
            Text(details.card.overview.isEmpty ? "No synopsis available." : details.card.overview)
                .font(.body).lineSpacing(4)
            if !details.imdbIdVerified {
                Label("No verified IMDb ID: Stremio playback may be unavailable.", systemImage: "info.circle")
                    .font(.caption).foregroundStyle(.orange)
            }
            if !details.directors.isEmpty { Text("Directed by: \(details.directors.joined(separator: ", "))").font(.subheadline) }
            if !details.creators.isEmpty { Text("Created by: \(details.creators.joined(separator: ", "))").font(.subheadline) }
            if title.isSeries {
                Divider().overlay(.gray)
                if details.seasons.isEmpty {
                    Text(CinemaTMDBPreferences.shared.useEpisodes
                        ? "No episode list is available."
                        : "Episode metadata is turned off in TMDB Settings.")
                }
                else {
                    Picker("Season", selection: Binding(
                        get: { selectedSeason ?? details.seasons.first ?? 1 },
                        set: { loadEpisodes($0) }
                    )) {
                        ForEach(details.seasons, id: \.self) { season in
                            Text(season == 0 ? "Specials" : "Season \(season)").tag(season)
                        }
                    }
                    .pickerStyle(.menu)
                    ForEach(episodes) { episode in
                        Button { select(episode) } label: {
                            HStack(alignment: .top, spacing: 12) {
                                CinemaRemoteImage(url: episode.still, fill: true)
                                    .frame(width: 124, height: 74).clipShape(RoundedRectangle(cornerRadius: 8))
                                VStack(alignment: .leading, spacing: 5) {
                                    Text("\(episode.number). \(episode.title)").font(.subheadline.bold())
                                    if let duration = episode.runtimeMinutes { Text("\(duration) min").font(.caption).foregroundStyle(CinemaStyle.secondary) }
                                    Text(episode.overview).lineLimit(2).font(.caption).foregroundStyle(CinemaStyle.secondary)
                                }
                                Spacer(minLength: 0)
                                Image(systemName: "play.circle").font(.title2)
                            }
                        }
                        .buttonStyle(.plain)
                        Divider().overlay(.gray.opacity(0.35))
                    }
                }
            }
        }.padding(.horizontal, 18)
        if !details.cast.isEmpty {
            VStack(alignment: .leading) {
                Text("Cast").font(.title3.bold()).padding(.horizontal, 18)
                ScrollView(.horizontal) {
                    LazyHStack(alignment: .top, spacing: 16) {
                        ForEach(details.cast) { actor in
                            VStack(spacing: 7) {
                                CinemaRemoteImage(url: actor.portrait, fill: true)
                                    .frame(width: 88, height: 88).clipShape(Circle())
                                Text(actor.name).font(.caption.bold()).lineLimit(2)
                                Text(actor.role).font(.caption2).foregroundStyle(CinemaStyle.secondary).lineLimit(2)
                            }
                            .frame(width: 102)
                        }
                    }.padding(.horizontal, 18)
                }.scrollIndicators(.hidden)
            }
        }
        if !details.recommendations.isEmpty {
            CinemaPosterRail(title: "You might also like", items: details.recommendations) { next in
                relatedTitle = next
            }
        }
    }

    private func select(_ episode: CinemaEpisode) {
        sourceSelection = CinemaSelection(title: title, season: episode.season,
                                          episode: episode.number, episodeTitle: episode.title)
    }
    private func loadDetail() {
        loading = true
        detailRequest?.cancel()
        detailRequest = model.bridge.detail(kind: title.kind, id: Int32(title.id), language: model.language) { raw, err in
            let result: Result<CinemaDetail, Error> = CinemaRuntime.decode(raw, err, as: CinemaDetail.self)
            Task { @MainActor in
                loading = false
                switch result {
                case .success(let data):
                    detail = data
                    if let season = data.seasons.first(where: { $0 > 0 }) ?? data.seasons.first { loadEpisodes(season) }
                case .failure(let error): self.error = error.localizedDescription
                }
            }
        }
    }
    private func loadEpisodes(_ season: Int) {
        selectedSeason = season
        episodes = []
        episodeRequest?.cancel()
        episodeRequest = model.bridge.episodes(id: Int32(title.id), season: Int32(season), language: model.language) { raw, err in
            let result: Result<[CinemaEpisode], Error> = CinemaRuntime.decode(raw, err, as: [CinemaEpisode].self)
            Task { @MainActor in
                switch result {
                case .success(let items): episodes = items
                case .failure(let error): self.error = error.localizedDescription
                }
            }
        }
    }
}
#endif
