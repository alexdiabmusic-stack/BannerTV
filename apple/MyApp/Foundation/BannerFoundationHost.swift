#if BANNER_SHARED_FOUNDATION
import Foundation
import BannerFoundation

@MainActor
final class BannerFoundationHost {
    private let foundation: AppleFoundation

    init() {
        let cache = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("BannerFoundation", isDirectory: true)
        foundation = AppleFoundation(cacheDirectory: cache.path, keychain: KeychainAddonVault())
    }

    func scores(on date: Date?, league: League) async throws -> [Match] {
        let payload = try await FoundationRequest().value { completion in
            foundation.nhlDiscovery.scores(atMillis: Self.millis(date ?? Date()), current: date == nil, completion: completion)
        }
        return try BannerNHLFeed.matches(from: payload, league: league)
    }

    func schedule(start: Date, days: Int, league: League) async throws -> [Match] {
        guard days > 0 else { return [] }
        guard let count = Int32(exactly: days) else { throw URLError(.badURL) }
        let payload = try await FoundationRequest().value { completion in
            foundation.nhlDiscovery.schedule(startMillis: Self.millis(start), days: count, completion: completion)
        }
        return try BannerNHLFeed.matches(from: payload, league: league)
    }

    private static func millis(_ date: Date) -> Int64 { Int64((date.timeIntervalSince1970 * 1000).rounded()) }
}

private struct FoundationServiceFailure: LocalizedError {
    let message: String
    let httpStatus: Int?
    var errorDescription: String? { message }
}

/// All state transitions run on the main actor. Kotlin cancellation alone does not resume a Swift continuation.
@MainActor
private final class FoundationRequest {
    private var continuation: CheckedContinuation<String, Error>?
    private var request: SportsRequest?

    func value(_ start: (@escaping (String?, SportsFailure?) -> Void) -> SportsRequest) async throws -> String {
        try Task.checkCancellation()
        let payload: String = try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                self.continuation = continuation
                guard !Task.isCancelled else { cancel(); return }
                request = start { [self] payload, failure in
                    // AppleFoundation dispatches this callback on Dispatchers.Main.
                    MainActor.assumeIsolated {
                        guard let continuation = self.continuation else { return }
                        self.continuation = nil
                        self.request = nil
                        if let payload {
                            continuation.resume(returning: payload)
                        } else {
                            continuation.resume(throwing: FoundationServiceFailure(
                                message: failure?.message ?? "NHL discovery is unavailable.",
                                httpStatus: failure?.httpStatus?.intValue))
                        }
                    }
                }
            }
        } onCancel: {
            Task { @MainActor in self.cancel() }
        }
        try Task.checkCancellation()
        return payload
    }

    private func cancel() {
        let pending = continuation
        continuation = nil
        request?.cancel()
        request = nil
        pending?.resume(throwing: CancellationError())
    }
}
#endif
