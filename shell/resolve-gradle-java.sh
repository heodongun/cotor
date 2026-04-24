#!/bin/bash

# Select a Gradle runtime JDK that is compatible with the Kotlin DSL used by
# this repo. Newer JDKs such as 25 can break Gradle script compilation even
# though the project toolchain itself targets Java 17.

set -euo pipefail

java_major_version() {
    local raw_version="$1"
    awk -F. '{ if ($1 == 1) print $2; else print $1 }' <<<"$raw_version"
}

current_java_version() {
    java -version 2>&1 | awk -F '"' '/version/ {print $2; exit}'
}

configure_gradle_java() {
    local selected_home=""
    local selected_label=""

    if command -v /usr/libexec/java_home >/dev/null 2>&1; then
        for version in 17 21; do
            if selected_home="$(/usr/libexec/java_home -v "$version" 2>/dev/null)"; then
                selected_label="$version"
                break
            fi
        done
    fi

    if [[ -n "$selected_home" ]]; then
        export JAVA_HOME="$selected_home"
        export PATH="$JAVA_HOME/bin:$PATH"
        echo "☕ Using JDK $selected_label for Gradle builds ($JAVA_HOME)"
        return 0
    fi

    if ! command -v java >/dev/null 2>&1; then
        echo "❌ Error: Java is not installed"
        echo "Please install JDK 17 or 21."
        return 1
    fi

    local current_version
    current_version="$(current_java_version)"
    local current_major
    current_major="$(java_major_version "$current_version")"

    if [[ "$current_major" -ge 17 && "$current_major" -le 24 ]]; then
        echo "☕ Using current JDK $current_version for Gradle builds"
        return 0
    fi

    echo "❌ Error: Could not locate a Gradle-compatible JDK."
    echo "Found current JVM: $current_version"
    echo "Install JDK 17, 21, or 24, or set JAVA_HOME to one of them before running this command."
    return 1
}
