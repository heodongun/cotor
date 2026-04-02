class Cotor < Formula
  desc "AI company orchestration platform - CLI and desktop app"
  homepage "https://github.com/bssm-oss/cotor"
  url "https://github.com/bssm-oss/cotor.git",
      branch: "master"
  version "1.0.2"
  license "MIT"
  head "https://github.com/bssm-oss/cotor.git", branch: "master"

  depends_on "openjdk@17"

  def install
    ENV["JAVA_HOME"] = Formula["openjdk@17"].opt_prefix

    # Build the shadow JAR (CLI backend)
    system "./gradlew", "shadowJar", "--no-daemon"

    if OS.mac?
      desktop_output = buildpath/"build/homebrew-desktop"
      ENV["COTOR_PROJECT_ROOT"] = buildpath.to_s
      ENV["COTOR_DESKTOP_BUILD_OUTPUT_ROOT"] = desktop_output.to_s
      system "bash", "shell/build-desktop-app-bundle.sh"
      (pkgshare/"desktop").install desktop_output/"Cotor Desktop.app"
    end

    # Install the JAR built from the current Gradle project version.
    libexec.install Dir["build/libs/cotor-*-all.jar"].fetch(0) => "cotor.jar"

    # Create CLI wrapper with JDK 17 pinned.
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

      First desktop install:
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
