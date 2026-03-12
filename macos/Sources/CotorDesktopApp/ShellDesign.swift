import AppKit
import SwiftUI

/// Shared visual tokens for the desktop shell.
///
/// The direction is a dense neutral admin shell: restrained contrast, compact
/// borders, and a TUI-first center stage without third-party branding cues.
enum ShellPalette {
    private static func dynamic(_ light: NSColor, _ dark: NSColor) -> Color {
        Color(nsColor: NSColor(name: nil) { appearance in
            let match = appearance.bestMatch(from: [.aqua, .darkAqua])
            return match == .darkAqua ? dark : light
        })
    }

    static let canvasTop = dynamic(
        NSColor(red: 0.95, green: 0.97, blue: 0.99, alpha: 1),
        NSColor(red: 0.05, green: 0.07, blue: 0.10, alpha: 1)
    )
    static let canvasBottom = dynamic(
        NSColor(red: 0.91, green: 0.94, blue: 0.97, alpha: 1),
        NSColor(red: 0.03, green: 0.04, blue: 0.06, alpha: 1)
    )
    static let panel = dynamic(
        NSColor(red: 0.98, green: 0.99, blue: 1.00, alpha: 0.98),
        NSColor(red: 0.08, green: 0.10, blue: 0.13, alpha: 0.97)
    )
    static let panelAlt = dynamic(
        NSColor(red: 0.95, green: 0.97, blue: 0.99, alpha: 0.99),
        NSColor(red: 0.10, green: 0.12, blue: 0.16, alpha: 0.99)
    )
    static let panelRaised = dynamic(
        NSColor(red: 0.90, green: 0.93, blue: 0.97, alpha: 1),
        NSColor(red: 0.13, green: 0.16, blue: 0.21, alpha: 1)
    )
    static let panelDeeper = dynamic(
        NSColor(red: 0.92, green: 0.95, blue: 0.98, alpha: 1),
        NSColor(red: 0.05, green: 0.06, blue: 0.09, alpha: 1)
    )
    static let line = dynamic(
        NSColor.black.withAlphaComponent(0.07),
        NSColor.white.withAlphaComponent(0.065)
    )
    static let lineStrong = dynamic(
        NSColor.black.withAlphaComponent(0.14),
        NSColor.white.withAlphaComponent(0.13)
    )
    static let border = line
    static let borderStrong = lineStrong
    static let text = dynamic(
        NSColor.black.withAlphaComponent(0.86),
        NSColor.white.withAlphaComponent(0.94)
    )
    static let muted = dynamic(
        NSColor.black.withAlphaComponent(0.58),
        NSColor.white.withAlphaComponent(0.56)
    )
    static let faint = dynamic(
        NSColor.black.withAlphaComponent(0.36),
        NSColor.white.withAlphaComponent(0.34)
    )
    static let accent = Color(nsColor: NSColor(red: 0.26, green: 0.56, blue: 0.93, alpha: 1))
    static let accentWarm = Color(nsColor: NSColor(red: 0.10, green: 0.72, blue: 0.66, alpha: 1))
    static let accentSoft = dynamic(
        NSColor(red: 0.86, green: 0.92, blue: 0.99, alpha: 1),
        NSColor(red: 0.12, green: 0.19, blue: 0.28, alpha: 1)
    )
    static let success = Color(nsColor: NSColor(red: 0.36, green: 0.79, blue: 0.52, alpha: 1))
    static let warning = Color(nsColor: NSColor(red: 0.98, green: 0.72, blue: 0.31, alpha: 1))
    static let danger = Color(nsColor: NSColor(red: 0.93, green: 0.38, blue: 0.40, alpha: 1))
}

enum ShellMetrics {
    static let baseSpacing: CGFloat = 8
    static let radiusLarge: CGFloat = 16
    static let radiusMedium: CGFloat = 12
    static let radiusSmall: CGFloat = 8
    static let sidebarMinWidth: CGFloat = 272
    static let sidebarIdealWidth: CGFloat = 304
    static let contentMinWidth: CGFloat = 620
    static let inspectorMinWidth: CGFloat = 348
}

enum ShellMotion {
    static let spring = Animation.spring(response: 0.28, dampingFraction: 0.86)
}

/// Full-window admin-shell background used behind the split view columns.
struct ShellCanvas: View {
    var body: some View {
        ZStack {
            LinearGradient(
                colors: [ShellPalette.canvasTop, ShellPalette.canvasBottom],
                startPoint: .top,
                endPoint: .bottom
            )

            Rectangle()
                .fill(
                    LinearGradient(
                        colors: [
                            Color.white.opacity(0.03),
                            Color.clear,
                            Color.clear,
                        ],
                        startPoint: .top,
                        endPoint: .bottom
                    )
                )
                .frame(maxWidth: .infinity, maxHeight: .infinity)

            Rectangle()
                .stroke(ShellPalette.line.opacity(0.35), lineWidth: 1)
                .blur(radius: 0.2)
                .padding(24)

            Circle()
                .fill(ShellPalette.accent.opacity(0.10))
                .frame(width: 320, height: 320)
                .blur(radius: 120)
                .offset(x: -420, y: -220)
        }
    }
}

/// Shared pane chrome used by the main shell columns and cards.
struct ShellCardModifier: ViewModifier {
    let accent: Color?

    func body(content: Content) -> some View {
        content
            .padding(16)
            .background(
                RoundedRectangle(cornerRadius: ShellMetrics.radiusMedium, style: .continuous)
                    .fill(ShellPalette.panel)
            )
            .overlay(
                RoundedRectangle(cornerRadius: ShellMetrics.radiusMedium, style: .continuous)
                    .stroke(accent ?? ShellPalette.line, lineWidth: accent == nil ? 1 : 1.15)
            )
            .shadow(color: Color.black.opacity(0.10), radius: 12, x: 0, y: 6)
    }
}

/// Inset surface style used for code-like panels inside a larger pane.
struct ShellInsetModifier: ViewModifier {
    func body(content: Content) -> some View {
        content
            .background(
                RoundedRectangle(cornerRadius: ShellMetrics.radiusMedium, style: .continuous)
                    .fill(ShellPalette.panelDeeper)
            )
            .overlay(
                RoundedRectangle(cornerRadius: ShellMetrics.radiusMedium, style: .continuous)
                    .stroke(ShellPalette.line, lineWidth: 1)
            )
    }
}

extension View {
    func shellCard(accent: Color? = nil) -> some View {
        modifier(ShellCardModifier(accent: accent))
    }

    func shellInset() -> some View {
        modifier(ShellInsetModifier())
    }
}

struct ShellSectionHeader: View {
    let eyebrow: String
    let title: String
    let subtitle: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(eyebrow.uppercased())
                .font(.system(size: 10, weight: .bold, design: .rounded))
                .tracking(0.7)
                .foregroundStyle(ShellPalette.faint)
            Text(title)
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(ShellPalette.text)
            if let subtitle, !subtitle.isEmpty {
                Text(subtitle)
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(ShellPalette.muted)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}

struct ShellStatChip: View {
    let title: String
    let value: String

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(title.uppercased())
                .font(.system(size: 9, weight: .bold))
                .tracking(0.8)
                .foregroundStyle(ShellPalette.faint)
            Text(value)
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(ShellPalette.text)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
        .background(
            RoundedRectangle(cornerRadius: ShellMetrics.radiusSmall, style: .continuous)
                .fill(ShellPalette.panelAlt)
        )
        .overlay(
            RoundedRectangle(cornerRadius: ShellMetrics.radiusSmall, style: .continuous)
                .stroke(ShellPalette.line, lineWidth: 1)
        )
    }
}

struct ShellStatusPill: View {
    let text: String
    let tint: Color

    var body: some View {
        HStack(spacing: 7) {
            Circle()
                .fill(tint)
                .frame(width: 7, height: 7)
            Text(text)
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(ShellPalette.text)
        }
            .padding(.horizontal, 10)
            .padding(.vertical, 5)
        .background(tint.opacity(0.14))
        .overlay(
            Capsule()
                .stroke(tint.opacity(0.42), lineWidth: 1)
        )
        .clipShape(Capsule())
    }
}

struct ShellTag: View {
    let text: String
    let tint: Color

    var body: some View {
        Text(text)
            .font(.system(size: 10, weight: .semibold))
            .foregroundStyle(ShellPalette.text)
            .padding(.horizontal, 8)
            .padding(.vertical, 5)
            .background(tint.opacity(0.16))
            .overlay(
                Capsule()
                    .stroke(tint.opacity(0.35), lineWidth: 1)
            )
            .clipShape(Capsule())
    }
}

struct ShellTopBarButtonStyle: ButtonStyle {
    let prominent: Bool

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 12, weight: .semibold))
            .foregroundStyle(ShellPalette.text)
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .fill(prominent ? ShellPalette.panelRaised : ShellPalette.panelAlt)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .stroke(prominent ? ShellPalette.lineStrong : ShellPalette.line, lineWidth: 1)
            )
            .opacity(configuration.isPressed ? 0.82 : 1)
    }
}
