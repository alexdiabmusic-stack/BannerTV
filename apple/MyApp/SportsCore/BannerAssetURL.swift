import Foundation

extension URL {
    nonisolated static func bannerImageAsset(named assetName: String) -> URL? {
        URL(string: "banner-asset:/\(assetName)")
    }

    nonisolated var bannerImageAssetName: String? {
        guard scheme == "banner-asset" else { return nil }
        let trimmedPath = path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        return trimmedPath.isEmpty ? host : trimmedPath
    }
}
