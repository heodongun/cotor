cask "cotor-desktop" do
  version "1.0.6"
  sha256 "24999e76baf9f2a97c533dc1b370c2d5528430db328cdf257dc40be169befe1f"

  url "https://github.com/bssm-oss/cotor/releases/download/v#{version}/Cotor-#{version}.dmg"
  name "Cotor Desktop"
  desc "AI company orchestration platform - macOS desktop app"
  homepage "https://github.com/bssm-oss/cotor"

  depends_on formula: "bssm-oss/cotor/cotor"

  app "Cotor Desktop.app"

  zap trash: [
    "~/Library/Application Support/CotorDesktop",
    "~/Library/Logs/CotorDesktop",
  ]
end
