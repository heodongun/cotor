import SwiftUI
import WebKit

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
