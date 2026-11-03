#if os(iOS)
import SwiftUI
import BannerFoundation

/// TMDB is a metadata service. Streaming sources are configured separately below.
struct BannerCinemaSettings: View {
    @Environment(\.dismiss) private var dismiss
    @StateObject private var runtime = CinemaRuntime.shared
    @ObservedObject private var metadata = CinemaTMDBPreferences.shared
    @State private var credential = ""
    @State private var manifest = ""
    @State private var adding = false
    @State private var credentialMessage: String?

    private var hasCredential: Bool { !CinemaTokenVault.shared.read().isEmpty }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Toggle("Enable TMDB metadata", isOn: $metadata.enabled)
                    Text("TMDB supplies posters, information and discovery. It does not supply movie or TV streams.")
                        .font(.caption).foregroundStyle(.secondary)
                    Label(hasCredential ? "Personal credential saved on this iPhone" : "Personal credential not configured",
                          systemImage: hasCredential ? "key.fill" : "key")
                        .font(.subheadline)
                        .foregroundStyle(hasCredential ? .green : .orange)
                    SecureField("TMDB v3 API key or v4 Read Access Token", text: $credential)
                        .textContentType(.password)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    HStack {
                        Button("Save credential") {
                            do {
                                try CinemaTokenVault.shared.set(credential)
                                credential = ""
                                credentialMessage = "Saved securely to this device."
                                runtime.resetTMDBConnectionState()
                                runtime.checkTMDBConnection()
                            } catch {
                                credentialMessage = "The credential could not be saved."
                            }
                        }
                        .disabled(credential.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                        Spacer()
                        Button("Remove", role: .destructive) {
                            do {
                                try CinemaTokenVault.shared.delete()
                                runtime.resetTMDBConnectionState()
                                runtime.refresh()
                                credentialMessage = "The saved credential was removed."
                            } catch {
                                credentialMessage = "The Keychain credential could not be removed."
                            }
                        }
                        .disabled(!hasCredential)
                    }
                    Button {
                        runtime.checkTMDBConnection()
                    } label: {
                        if runtime.testingTMDB {
                            HStack { ProgressView(); Text("Checking TMDB…") }
                        } else {
                            Label("Test TMDB connection", systemImage: "network")
                        }
                    }
                    .disabled(!hasCredential || runtime.testingTMDB)
                    if let message = credentialMessage {
                        Text(message).font(.caption).foregroundStyle(.secondary)
                    }
                    if let message = runtime.tmdbConnectionMessage {
                        Label(message, systemImage: runtime.tmdbConnected ? "checkmark.circle.fill" : "exclamationmark.circle")
                            .font(.caption)
                            .foregroundStyle(runtime.tmdbConnected ? .green : .orange)
                    }
                    Text("Use your own TMDB credential from themoviedb.org. No shared key or token is committed to this repository.")
                        .font(.caption).foregroundStyle(.secondary)
                    Text("This product uses the TMDB API but is not endorsed or certified by TMDB.")
                        .font(.caption2).foregroundStyle(.secondary)
                } header: {
                    Text("TMDB metadata")
                }

                Section("Localization") {
                    Picker("Preferred language", selection: $metadata.language) {
                        ForEach(CinemaTMDBPreferences.supportedLanguages, id: \.code) { item in
                            Text(item.label).tag(item.code)
                        }
                        if !CinemaTMDBPreferences.supportedLanguages.contains(where: { $0.code == metadata.language }) {
                            Text(metadata.language).tag(metadata.language)
                        }
                    }
                    Text("Changes the language of movie and series information in future TMDB requests.")
                        .font(.caption).foregroundStyle(.secondary)
                }

                Section {
                    Toggle("Posters, backdrops and logos", isOn: $metadata.useArtwork)
                    Toggle("Cast and creators", isOn: $metadata.useCredits)
                    Toggle("Seasons and episodes", isOn: $metadata.useEpisodes)
                    Toggle("Recommendations", isOn: $metadata.useRecommendations)
                    Text("These switches control available BannerTV metadata modules. Basic details and verified external IDs remain enabled for stream matching. Trailers, collections and production-network modules are planned separately.")
                        .font(.caption).foregroundStyle(.secondary)
                } header: {
                    Text("Metadata modules")
                }
                .disabled(!metadata.enabled)

                Section("Streaming add-ons") {
                    Text("Separately connect a configured HTTPS Stremio-compatible add-on URL ending in /manifest.json. TMDB itself cannot play video.")
                        .font(.caption).foregroundStyle(.secondary)
                    SecureField("https://provider.example/.../manifest.json", text: $manifest)
                        .textContentType(.URL)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                    Button(adding ? "Connecting…" : "Connect add-on") {
                        adding = true
                        runtime.connectAddon(manifest) { okay in
                            adding = false
                            if okay { manifest = "" }
                        }
                    }
                    .disabled(adding || manifest.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    if let message = runtime.addonsMessage {
                        Text(message).font(.caption).foregroundStyle(.orange)
                    }
                    ForEach(runtime.addons) { addon in
                        HStack {
                            VStack(alignment: .leading) {
                                Text(addon.name)
                                Text(addon.enabled ? "Enabled" : "Disabled")
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer()
                            Toggle(addon.name, isOn: Binding(
                                get: { addon.enabled },
                                set: { runtime.setAddon(addon, enabled: $0) }
                            ))
                            .labelsHidden()
                            Button(role: .destructive) { runtime.deleteAddon(addon) } label: {
                                Image(systemName: "trash")
                            }
                            .buttonStyle(.borderless)
                            .accessibilityLabel("Remove \(addon.name)")
                        }
                    }
                }
            }
            .navigationTitle("Cinema Settings")
            .toolbar { Button("Done") { dismiss() } }
            .task { runtime.refreshAddons() }
            .onChange(of: metadata.enabled) { _, _ in runtime.applyTMDBPreferences() }
            .onChange(of: metadata.language) { _, _ in runtime.applyTMDBPreferences() }
            .onChange(of: metadata.useArtwork) { _, _ in runtime.applyTMDBPreferences() }
            .onChange(of: metadata.useCredits) { _, _ in runtime.applyTMDBPreferences() }
            .onChange(of: metadata.useEpisodes) { _, _ in runtime.applyTMDBPreferences() }
            .onChange(of: metadata.useRecommendations) { _, _ in runtime.applyTMDBPreferences() }
        }
        .preferredColorScheme(.dark)
    }
}
#endif
