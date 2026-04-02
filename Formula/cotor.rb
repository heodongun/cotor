class Cotor < Formula
  desc "AI company orchestration platform - CLI and desktop app"
  homepage "https://github.com/bssm-oss/cotor"
  version "1.0.4"
  url "https://github.com/bssm-oss/cotor/releases/download/v1.0.4/cotor-1.0.4-all.jar"
  sha256 "7b941eb00f348596a8d9dbec90fed3f041ec02bd11f5c8229f0802bf1b0d1815"
  license "MIT"
  head "https://github.com/bssm-oss/cotor.git", branch: "master"

  depends_on "openjdk@17"

  resource "desktop-dmg" do
    url "https://github.com/bssm-oss/cotor/releases/download/v1.0.4/Cotor-1.0.4.dmg"
    sha256 "0dbda3ce68ab9c1cd1a18aed3163a64893314fa18550b04b4140df465804bad0"
  end

  def install
    ENV["JAVA_HOME"] = Formula["openjdk@17"].opt_prefix

    if OS.mac?
      resource("desktop-dmg").fetch
      dmg_path = resource("desktop-dmg").cached_download
      mountpoint = buildpath/"cotor-dmg"
      mountpoint.mkpath
      system "hdiutil", "attach", dmg_path, "-mountpoint", mountpoint.to_s, "-nobrowse"
      begin
        (pkgshare/"desktop").mkpath
        cp_r mountpoint/"Cotor Desktop.app", pkgshare/"desktop"
      ensure
        system "hdiutil", "detach", mountpoint.to_s
      end
    end

    libexec.install "cotor-1.0.4-all.jar" => "cotor.jar"

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
      `cotor install` prints the final app path and installs into `/Applications`
      when writable, otherwise `~/Applications`.

      First interactive run:
      - If no local `cotor.yaml` exists, Cotor creates
        `~/.cotor/interactive/default/cotor.yaml`
      - If no authenticated AI CLI or API key is ready yet, the starter falls back
        to the safe `example-agent` echo mode instead of failing immediately.

      Update:
        brew upgrade cotor
    EOS
  end

  test do
    assert_match "Cotor version", shell_output("#{bin}/cotor version")
    assert_match "Chat with the master agent", shell_output("#{bin}/cotor interactive --help")

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

    if OS.mac?
      assert_predicate pkgshare/"desktop/Cotor Desktop.app", :directory?
      test_install_root = testpath/"Applications"
      ENV["COTOR_DESKTOP_INSTALL_ROOT"] = test_install_root.to_s
      system bin/"cotor", "install"
      assert_predicate test_install_root/"Cotor Desktop.app", :directory?
      assert_match "Launch:", shell_output("#{bin}/cotor install")
    end
  end
end
