class Cotor < Formula
  desc "AI company orchestration platform - CLI"
  homepage "https://github.com/bssm-oss/cotor"
  url "https://github.com/bssm-oss/cotor.git", tag: "v1.0.0"
  license "MIT"
  head "https://github.com/bssm-oss/cotor.git", branch: "master"

  depends_on "openjdk@17"

  def install
    ENV["JAVA_HOME"] = Formula["openjdk@17"].opt_prefix

    # Build the shadow JAR
    system "./gradlew", "shadowJar"

    # Install the JAR
    libexec.install "build/libs/cotor-1.0.0-all.jar" => "cotor.jar"

    # Create wrapper script
    (bin/"cotor").write <<~EOS
      #!/bin/bash
      export JAVA_HOME="#{Formula["openjdk@17"].opt_prefix}"
      exec "${JAVA_HOME}/bin/java" -jar "#{libexec}/cotor.jar" "$@"
    EOS
  end

  test do
    assert_match "Cotor version", shell_output("#{bin}/cotor version")
  end
end
