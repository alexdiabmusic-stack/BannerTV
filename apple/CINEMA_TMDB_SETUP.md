# TMDB metadata setup for BannerTV on iPhone

This implementation adapts Nuvio's **configurable TMDB enrichment** concept to BannerTV's existing Kotlin Multiplatform TMDB client. It is **not** a streaming add-on: TMDB supplies titles, artwork and metadata, while playback is provided by the user's separately configured legal/authorized Stremio-compatible sources or IPTV integration.

## iPhone setup

1. Merge the TMDB configuration PR and update the local `main` checkout on your Mac.
2. From the BannerTV root, run `python3 scripts/bootstrap-public-sports.py` once if `apple/Config/ProviderDefaults.xcconfig` does not exist. Have full Xcode and a supported JDK configured.
3. Open `apple/Stadia TV.xcodeproj`, select **MyApp** and an iPhone simulator/device, then build. The Xcode precompile phase builds `BannerFoundation` with Kotlin/Native.
4. In BannerTV, select the **Complete** viewing experience and open the **Movies** tab. Tap Cinema Settings (sliders icon).
5. Leave **Enable TMDB metadata** on, choose your preferred metadata language (for example **en-CA** or **fr-CA**), and enter **your own** TMDB v3 personal API key (32-character hexadecimal) or TMDB v4 **API Read Access Token**. Save it to the device Keychain.
6. Tap **Test TMDB connection**. A successful test means the TMDB configuration endpoint returned valid metadata; it does **not** certify the rest of the app's playback functionality. Browse, search, load a movie/series detail page and check cast, episodes, and recommendations. Toggle the supported modules to confirm the settings affect data returned.
7. To test streaming, independently configure a valid HTTPS Stremio-compatible `manifest.json` and use an authorized, Apple-playable HTTPS HLS/MP4 source. TMDB does not provide streams. The initial AVPlayer path does not support arbitrary MKV or custom request headers.

## Nuvio comparison

Nuvio's current code has an enable switch, personal API key override, language preference, and individual enrichment modules. It also has an embedded application-key fallback (`TmdbConfig.API_KEY`). BannerTV intentionally **does not copy or bundle Nuvio's app credentials**. The iPhone setup accepts your personal v3 API key *or* your v4 Read Access Token and stores it using Apple's Keychain; module preferences are device-local in UserDefaults.

Implemented module switches: **artwork**, **cast/creators**, **episode information** and **recommendations**. Basic title details and verified IMDb external IDs are required by BannerTV's current add-on identity resolution, so they remain available even when optional modules are turned off. Nuvio's richer trailer, collection, production-company and network enrichment modules are **not yet implemented** in this PR; controls for unavailable capabilities are deliberately not shown.

## Security / verification limits

- No TMDB credential is committed to the repository. The credential is entered on the iPhone and is not part of the watchlist, logs, or cloud synchronization.
- v3 keys are supplied using TMDB's standard API query parameter; v4 read tokens use the HTTPS `Authorization: Bearer` header. Do not log full request URLs containing v3 API keys in production.
- Switching TMDB off stops new discovery searches. The watchlist continues to load locally without a network connection.
- This GitHub change has not been compiled in full Xcode/Kotlin/Native or run on an iPhone in the authoring environment. Successful installation requires resolving any compiler or interop issues found on the user's Mac.
- Before public distribution, review TMDB attribution, key provisioning and usage terms; user-entered credentials are for personal/development testing.
