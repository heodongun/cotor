cask "cotor-desktop" do
  version "1.0.0"
  sha256 :no_check

  url "https://github.com/bssm-oss/cotor/releases/download/v#{version}/Cotor-Desktop-macOS.zip"
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
