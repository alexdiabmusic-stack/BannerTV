import SwiftUI
import AVKit
import Combine
import BannerFoundation

struct CinemaSourcePicker: View {
    let selection: CinemaSelection
    let onPlay: (CinemaPlayback) -> Void
    @Environment(\.dismiss) private var dismiss
    @StateObject private var runtime = CinemaRuntime.shared
    @State private var sources: CinemaSourceResults?
    @State private var request: CinemaRequest?
    @State private var loading = true
    @State private var failure: String?

    var body: some View {
        NavigationStack {
            List {
                if let sources {
                    Section {
                        if sources.sources.isEmpty {
                            ContentUnavailableView("No sources found", systemImage: "film.stack",
                                description: Text(sources.enabledProviders == 0
                                    ? "Connect an add-on in Cinema Settings."
                                    : "Your enabled add-ons did not return a compatible stream."))
                        }
                        ForEach(sources.sources) { source in
                            Button {
                                let resume = runtime.resume(for: selection.title,
                                    season: selection.season, episode: selection.episode)?.positionMillis ?? 0
                                onPlay(CinemaPlayback(selection: selection, stream: source, startMillis: resume))
                                dismiss()
                            } label: {
                                HStack {
                                    VStack(alignment: .leading, spacing: 5) {
                                        Text(source.name).font(.headline)
                                        Text(source.provider).font(.caption).foregroundStyle(.secondary)
                                    }
                                    Spacer()
                                    if source.playableWithAVPlayer {
                                        Image(systemName: "play.circle.fill").font(.title2)
                                    } else {
                                        Label("Needs other player", systemImage: "info.circle")
                                            .font(.caption).foregroundStyle(.secondary)
                                    }
                                }
                            }
                            .disabled(!source.playableWithAVPlayer)
                        }
                    } header: { Text("Available sources") }
                    if sources.failedProviders > 0 {
                        Section { Text("\(sources.failedProviders) provider(s) could not respond. Working sources are shown above.")
                            .font(.caption).foregroundStyle(.secondary) }
                    }
                }
                if loading { ProgressView("Checking configured add-ons…") }
                if let failure {
                    Text(failure).foregroundStyle(.orange)
                    Button("Retry", action: refresh)
                }
                Section {
                    Text("AVPlayer accepts common Apple-supported HTTPS media formats without custom request headers. Other sources need an MPV-based or equivalent adapter.")
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
            .navigationTitle(selection.title.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } } }
            .task { refresh() }
            .onDisappear { request?.cancel() }
        }
        .preferredColorScheme(.dark)
    }

    private func refresh() {
        request?.cancel()
        loading = true
        failure = nil
        request = runtime.bridge.sources(kind: selection.title.kind, id: Int32(selection.title.id),
            season: Int32(selection.season), episode: Int32(selection.episode), language: runtime.language) { raw, error in
            let result: Result<CinemaSourceResults, Error> = CinemaRuntime.decode(raw, error, as: CinemaSourceResults.self)
            Task { @MainActor in
                loading = false
                switch result {
                case .success(let found): sources = found
                case .failure(let error): failure = error.localizedDescription
                }
            }
        }
    }
}

@MainActor
final class CinemaPlayerState: ObservableObject {
    @Published var error: String?
    @Published private(set) var isPlaying = false
    @Published private(set) var secondsWatched = 0.0
    let player = AVPlayer()
    private var observer: Any?
    private var playback: CinemaPlayback?
    private var didSeek = false
    private var lastSavedSecond = -1

    func start(_ playback: CinemaPlayback) {
        self.playback = playback
        guard playback.stream.playableWithAVPlayer,
              let address = URL(string: playback.stream.url) else {
            error = "This source needs a different playback engine."
            return
        }
        stop(shouldSave: false)
        self.playback = playback
        error = nil
        didSeek = false
        lastSavedSecond = -1
        player.replaceCurrentItem(with: AVPlayerItem(url: address))
        observer = player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 1, preferredTimescale: 600), queue: .main) { [weak self] time in
            Task { @MainActor [weak self] in self?.tick(time) }
        }
        player.play()
        isPlaying = true
    }

    private func tick(_ time: CMTime) {
        guard let playback, let item = player.currentItem else { return }
        if !didSeek && item.status == .readyToPlay {
            didSeek = true
            if playback.startMillis > 0 {
                player.seek(to: CMTime(seconds: Double(playback.startMillis) / 1000,
                                       preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero)
            }
        }
        let seconds = time.seconds
        guard seconds.isFinite, seconds >= 0 else { return }
        secondsWatched = seconds
        if Int(seconds) / 15 != lastSavedSecond / 15 {
            lastSavedSecond = Int(seconds)
            saveProgress()
        }
    }

    func toggle() {
        if isPlaying { player.pause() } else { player.play() }
        isPlaying.toggle()
    }
    func saveProgress() {
        guard let playback, let duration = player.currentItem?.duration.seconds,
              duration.isFinite, duration > 0, secondsWatched >= 0 else { return }
        CinemaRuntime.shared.record(playback.selection,
            positionMillis: Int64(secondsWatched * 1000), durationMillis: Int64(duration * 1000))
    }
    func stop(shouldSave: Bool = true) {
        if shouldSave { saveProgress() }
        player.pause()
        if let observer { player.removeTimeObserver(observer); self.observer = nil }
        player.replaceCurrentItem(with: nil)
        isPlaying = false
        playback = nil
    }
}

struct BannerCinemaPlayer: View {
    let playback: CinemaPlayback
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var state = CinemaPlayerState()
    var body: some View {
        ZStack(alignment: .top) {
            Color.black.ignoresSafeArea()
            VideoPlayer(player: state.player).ignoresSafeArea(edges: .bottom)
            HStack {
                Button { dismiss() } label: {
                    Label("Back", systemImage: "chevron.left").padding(10)
                        .background(.black.opacity(0.8), in: Capsule())
                }
                Spacer()
                Text(playback.selection.title.title).font(.headline).lineLimit(1)
                Spacer()
                Button { state.toggle() } label: {
                    Image(systemName: state.isPlaying ? "pause.fill" : "play.fill")
                        .padding(10).background(.black.opacity(0.8), in: Circle())
                }
                .accessibilityLabel(state.isPlaying ? "Pause" : "Play")
            }
            .padding(.horizontal, 18).padding(.top, 10)
            if let error = state.error {
                VStack {
                    Spacer()
                    Text(error).padding().background(.black.opacity(0.85), in: RoundedRectangle(cornerRadius: 10))
                    Button("Choose another source") { dismiss() }
                    Spacer()
                }
            }
        }
        .foregroundStyle(.white)
        .task(id: playback.id) { state.start(playback) }
        .onDisappear { state.stop() }
        .onChange(of: scenePhase) { _, value in
            if value != .active { state.saveProgress() }
        }
        .statusBarHidden()
    }
}
