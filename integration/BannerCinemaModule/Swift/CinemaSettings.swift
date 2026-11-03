import SwiftUI
import BannerFoundation

struct BannerCinemaSettings: View {
    @Environment(\.dismiss) private var dismiss
    @StateObject private var runtime = CinemaRuntime.shared
    @State private var token = ""
    @State private var manifest = ""
    @State private var adding = false
    @State private var tokenMessage: String?

    var body: some View {
        NavigationStack {
            Form {
                Section("Movie metadata") {
                    Text("For development or personal installations, supply your own TMDB API Read Access Token. Production releases need a separate compliant provisioning strategy.")
                        .font(.caption).foregroundStyle(.secondary)
                    SecureField("TMDB API read token", text: $token)
                        .textContentType(.password)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                    Button("Store token in Keychain") {
                        do {
                            try CinemaTokenVault.shared.set(token)
                            token = ""
                            tokenMessage = "Token saved to device Keychain."
                            runtime.refresh()
                        } catch { tokenMessage = "Could not save the token." }
                    }
                    .disabled(token.isEmpty)
                    if let tokenMessage { Text(tokenMessage).font(.caption).foregroundStyle(.secondary) }
                    Text("This product uses the TMDB API but is not endorsed or certified by TMDB.")
                        .font(.caption2).foregroundStyle(.secondary)
                }
                Section("Streaming add-ons") {
                    Text("Use a configured HTTPS Stremio-compatible add-on URL ending in /manifest.json. No add-on credentials are included in your watch history or cloud backup.")
                        .font(.caption).foregroundStyle(.secondary)
                    SecureField("https://provider.example/.../manifest.json", text: $manifest)
                        .textContentType(.URL)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                    Button(adding ? "Connecting…" : "Connect add-on") {
                        adding = true
                        let address = manifest
                        runtime.connectAddon(address) { okay in
                            adding = false
                            if okay { manifest = "" }
                        }
                    }
                    .disabled(adding || manifest.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    if let message = runtime.addonsMessage { Text(message).font(.caption).foregroundStyle(.orange) }
                    ForEach(runtime.addons) { addon in
                        HStack {
                            VStack(alignment: .leading) {
                                Text(addon.name)
                                Text(addon.enabled ? "Enabled" : "Disabled").font(.caption).foregroundStyle(.secondary)
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
        }
        .preferredColorScheme(.dark)
    }
}
