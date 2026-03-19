import Foundation
import SwiftUI
import WebKit


// MARK: - File Overview
// WebView belongs to the native macOS client layer for the Cotor desktop application.
// It collects declarations centered on web view so the native shell code stays easier to navigate.
// Start with this file when tracing how the desktop client presents, stores, or moves state in this area.

/// Minimal WebKit wrapper for local preview ports exposed by agent runs.
struct WebView: NSViewRepresentable {
    let url: URL

    /// Create the reusable WebKit view once for SwiftUI.
    func makeNSView(context: Context) -> WKWebView {
        WKWebView()
    }

    /// Only reload when the destination changes so inspector tab switches do not
    /// unnecessarily reset an already-loaded local preview page.
    func updateNSView(_ webView: WKWebView, context: Context) {
        if webView.url != url {
            webView.load(URLRequest(url: url))
        }
    }
}

/// Embeds a proper terminal emulator for the center-pane TUI.
///
/// The HTML/JS side runs xterm.js locally from bundled assets and talks to the
/// localhost app-server directly for terminal deltas and raw keyboard input.
struct TerminalWebView: NSViewRepresentable {
    let sessionId: String
    let baseURL: URL
    let token: String?

    func makeCoordinator() -> Coordinator {
        Coordinator()
    }

    func makeNSView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.defaultWebpagePreferences.allowsContentJavaScript = true

        let webView = WKWebView(frame: .zero, configuration: configuration)
        webView.setValue(false, forKey: "drawsBackground")
        webView.navigationDelegate = context.coordinator
        context.coordinator.webView = webView

        guard let htmlURL = Bundle.module.url(forResource: "terminal", withExtension: "html", subdirectory: "Terminal") else {
            webView.loadHTMLString(
                """
                <html><body style="background:#0b0f14;color:#f5f7fb;font-family:Menlo,monospace;padding:24px;">Missing terminal bundle resources.</body></html>
                """,
                baseURL: nil
            )
            return webView
        }

        webView.loadFileURL(htmlURL, allowingReadAccessTo: htmlURL.deletingLastPathComponent())
        return webView
    }

    func updateNSView(_ webView: WKWebView, context: Context) {
        context.coordinator.pendingConfig = Coordinator.SessionConfig(
            sessionId: sessionId,
            baseURL: baseURL.absoluteString.trimmingCharacters(in: CharacterSet(charactersIn: "/")),
            token: token
        )
        context.coordinator.pushConfigIfReady()
    }

    final class Coordinator: NSObject, WKNavigationDelegate {
        struct SessionConfig: Codable {
            let sessionId: String
            let baseURL: String
            let token: String?
        }

        weak var webView: WKWebView?
        var pendingConfig: SessionConfig?
        private var didFinishNavigation = false

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            didFinishNavigation = true
            pushConfigIfReady()
        }

        func pushConfigIfReady() {
            guard
                didFinishNavigation,
                let webView,
                let pendingConfig,
                let data = try? JSONEncoder().encode(pendingConfig),
                let json = String(data: data, encoding: .utf8)
            else {
                return
            }

            let script = "window.cotorTerminal && window.cotorTerminal.setSession(\(json));"
            webView.evaluateJavaScript(script)
            webView.evaluateJavaScript("window.cotorTerminal && window.cotorTerminal.focus();")
        }
    }
}
