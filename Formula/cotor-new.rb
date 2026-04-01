class Cotor < Formula
  desc "AI company orchestration platform - CLI and desktop app"
  homepage "https://github.com/bssm-oss/cotor"
  version "1.0.0"
  license "MIT"

  # CLI JAR download
  url "https://github.com/bssm-oss/cotor/releases/download/v1.0.0/cotor-1.0.0-all.jar"
  sha256 "62ebb5260af7c644c4ff2fe0d949c9234573abe154362a4e86d1fded807d55fa"

  # Desktop App DMG (resource)
  resource "desktop-dmg" do
    url "https://github.com/bssm-oss/cotor/releases/download/v1.0.0/Cotor-1.0.0.dmg"
    sha256 "e918c42e8f74e8e2510a738bc54b0742500f065830bafd38ca239698ffd8205a"
  end

  depends_on "openjdk@17"
  depends_on :macos

  def install
    # Install the JAR
    libexec.install "cotor-1.0.0-all.jar" => "cotor.jar"

    # Download and extract desktop app DMG
    if OS.mac?
      resource("desktop-dmg").stage do
        # Mount DMG
        system "hdiutil", "attach", "Cotor-1.0.0.dmg", "-mountpoint", "/tmp/cotor-dmg-mount"
        
        # Copy app bundle
        (pkgshare/"desktop").install "/tmp/cotor-dmg-mount/Cotor Desktop.app"
        
        # Unmount
        system "hdiutil", "detach", "/tmp/cotor-dmg-mount"
      end
    end

    # Create CLI wrapper with JDK 17 pinned
    (bin/"cotor").write <<~EOS
      #!/bin/bash
      export JAVA_HOME="#{Formula["openjdk@17"].opt_prefix}"
      export COTOR_INSTALL_KIND="packaged"
      export COTOR_INSTALL_ROOT="#{pkgshare}"
      exec "#{Formula["openjdk@17"].opt_bin}/java" -jar "#{libexec}/cotor.jar" "$@"
    EOS
  end

  def caveats
    <<~EOS
      Cotor CLI is ready:
        cotor version
        cotor
        cotor app-server

      Desktop app install:
        cotor install
      
      This will install Cotor Desktop.app to /Applications or ~/Applications.

      First interactive run:
      - If no local cotor.yaml exists, Cotor creates ~/.cotor/interactive/default/cotor.yaml
      - If no authenticated AI CLI or API key is ready, it falls back to safe echo mode.

      Update:
        brew upgrade cotor
    EOS
  end

  test do
    # Test CLI
    assert_match "Cotor version", shell_output("#{bin}/cotor version")
    assert_match "Chat with the master agent", shell_output("#{bin}/cotor interactive --help")

    # Test interactive mode
    ENV["HOME"] = (testpath/"home").to_s
    ENV["PATH"] = "/usr/bin:/bin:/usr/sbin:/sbin"
    ENV.delete("ANTHROPIC_API_KEY")
    ENV.delete("CLAUDE_CODE_OAUTH_TOKEN")
    ENV.delete("OPENAI_API_KEY")
    ENV.delete("GEMINI_API_KEY")
    ENV.delete("GOOGLE_API_KEY")
    
    output = shell_output("#{bin}/cotor interactive --no-context --prompt hello")
    assert_match "hello", output
    assert_predicate testpath/"home/.cotor/interactive/default/cotor.yaml", :exist?
    assert_predicate testpath/"home/.cotor/interactive/default/interactive.log", :exist?

    # Test desktop app exists
    if OS.mac?
      assert_predicate pkgshare/"desktop/Cotor Desktop.app", :directory?
      
      # Test install command
      test_install_root = testpath/"Applications"
      ENV["COTOR_DESKTOP_INSTALL_ROOT"] = test_install_root.to_s
      system bin/"cotor", "install"
      assert_predicate test_install_root/"Cotor Desktop.app", :directory?
      assert_match "Launch:", shell_output("#{bin}/cotor install")
    end
  end
end
