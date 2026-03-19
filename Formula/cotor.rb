class Cotor < Formula
  desc "AI company orchestration platform - CLI and desktop app"
  homepage "https://github.com/bssm-oss/cotor"
  url "https://github.com/bssm-oss/cotor.git", tag: "v1.0.0"
  license "MIT"
  head "https://github.com/bssm-oss/cotor.git", branch: "master"

  depends_on "openjdk@17"

  def install
    ENV["JAVA_HOME"] = Formula["openjdk@17"].opt_prefix

    # Build the shadow JAR (CLI backend)
    system "./gradlew", "shadowJar", "--no-daemon"

    # Install the JAR
    libexec.install "build/libs/cotor-1.0.0-all.jar" => "cotor.jar"

    # Create CLI wrapper with JDK 17 pinned
    (bin/"cotor").write <<~EOS
      #!/bin/bash
      export JAVA_HOME="#{Formula["openjdk@17"].opt_prefix}"
      export COTOR_PROJECT_ROOT="#{prefix}"
      exec "${JAVA_HOME}/bin/java" -jar "#{libexec}/cotor.jar" "$@"
    EOS

    # Install desktop app build scripts for `cotor install` / `cotor update`
    prefix.install "shell"
    prefix.install "macos"
  end

  def post_install
    # Build and install macOS desktop app
    if OS.mac?
      ENV["JAVA_HOME"] = Formula["openjdk@17"].opt_prefix
      ENV["COTOR_PROJECT_ROOT"] = prefix.to_s
      system "bash", "#{prefix}/shell/install-desktop-app.sh"
    end
  end

  def caveats
    <<~EOS
      Cotor CLI is ready:
        cotor version
        cotor app-server

      Desktop app:
        open "/Applications/Cotor Desktop.app"

      Update:
        brew upgrade cotor
    EOS
  end

  test do
    assert_match "Cotor version", shell_output("#{bin}/cotor version")
  end
end
