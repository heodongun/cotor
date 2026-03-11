import SwiftUI

/// Read-only settings surface for the current desktop milestone plus the small
/// amount of app-local preference state that does not belong in the backend.
///
/// Language switching is handled here because it is purely a shell concern and
/// should not require a round trip through the localhost app-server.
struct SettingsView: View {
    @EnvironmentObject private var store: DesktopStore

    var body: some View {
        ZStack {
            ShellCanvas()
                .ignoresSafeArea()

            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    hero

                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 280), spacing: 16)], spacing: 16) {
                        languageCard
                        localPathsCard
                        availableAgentsCard
                        shortcutsCard
                        installCard
                    }
                }
                .padding(24)
            }
        }
    }

    private var hero: some View {
        VStack(alignment: .leading, spacing: 14) {
            ShellSectionHeader(
                eyebrow: store.text(.desktopSettings),
                title: store.text(.runtimeOverview),
                subtitle: store.text(.runtimeOverviewSubtitle)
            )

            LazyVGrid(columns: [GridItem(.adaptive(minimum: 120), spacing: 10)], spacing: 10) {
                ShellStatChip(title: store.text(.agents), value: "\(store.dashboard.settings.availableAgents.count)")
                ShellStatChip(title: store.text(.keyboardShortcuts), value: "\(store.dashboard.settings.shortcuts.bindings.count)")
                ShellStatChip(title: store.text(.repos), value: "\(store.dashboard.repositories.count)")
            }
        }
        .shellCard(accent: ShellPalette.borderStrong)
    }

    private var languageCard: some View {
        settingsCard(
            eyebrow: store.text(.language),
            title: store.text(.language),
            subtitle: store.text(.languageSubtitle)
        ) {
            Picker(store.text(.language), selection: Binding(
                get: { store.language },
                set: { store.setLanguage($0) }
            )) {
                ForEach(AppLanguage.allCases) { language in
                    Text(language.displayName).tag(language)
                }
            }
            .pickerStyle(.segmented)
        }
    }

    private var localPathsCard: some View {
        settingsCard(
            eyebrow: store.text(.paths),
            title: store.text(.localPaths),
            subtitle: store.text(.localPathsSubtitle)
        ) {
            valueRow(store.text(.appHome), store.dashboard.settings.appHome)
            valueRow(store.text(.managedRepos), store.dashboard.settings.managedReposRoot)
        }
    }

    private var availableAgentsCard: some View {
        settingsCard(
            eyebrow: store.text(.catalog),
            title: store.text(.availableAgents),
            subtitle: store.text(.availableAgentsSubtitle)
        ) {
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 120), spacing: 10)], spacing: 10) {
                ForEach(store.dashboard.settings.availableAgents, id: \.self) { agent in
                    Text(agent)
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(ShellPalette.text)
                        .frame(maxWidth: .infinity)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 10)
                        .background(
                            RoundedRectangle(cornerRadius: ShellMetrics.radiusSmall, style: .continuous)
                                .fill(ShellPalette.panelAlt)
                        )
                        .overlay(
                            RoundedRectangle(cornerRadius: ShellMetrics.radiusSmall, style: .continuous)
                                .stroke(ShellPalette.border)
                        )
                }
            }
        }
    }

    private var shortcutsCard: some View {
        settingsCard(
            eyebrow: store.text(.input),
            title: store.text(.keyboardShortcuts),
            subtitle: store.text(.keyboardShortcutsSubtitle)
        ) {
            VStack(spacing: 10) {
                ForEach(store.dashboard.settings.shortcuts.bindings) { binding in
                    HStack(spacing: 12) {
                        Text(store.shortcutTitle(binding))
                            .font(.system(size: 13, weight: .medium))
                            .foregroundStyle(ShellPalette.text)
                        Spacer()
                        Text(binding.shortcut)
                            .font(.system(size: 12, weight: .semibold, design: .monospaced))
                            .foregroundStyle(ShellPalette.text)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 6)
                            .background(ShellPalette.panelAlt)
                            .clipShape(Capsule())
                    }
                }
            }
        }
    }

    private var installCard: some View {
        settingsCard(
            eyebrow: store.text(.install),
            title: store.text(.desktopInstaller),
            subtitle: store.text(.desktopInstallerSubtitle)
        ) {
            valueRow(store.text(.installer), "./shell/install-desktop-app.sh")
            valueRow(store.text(.appLocation), "/Applications/Cotor Desktop.app or ~/Applications/Cotor Desktop.app")
            valueRow(store.text(.downloadArchive), "~/Downloads/Cotor-Desktop-macOS.zip")
        }
    }

    @ViewBuilder
    private func settingsCard<Content: View>(
        eyebrow: String,
        title: String,
        subtitle: String,
        @ViewBuilder content: () -> Content
    ) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            ShellSectionHeader(eyebrow: eyebrow, title: title, subtitle: subtitle)
            content()
        }
        .shellCard()
    }

    @ViewBuilder
    private func valueRow(_ label: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label.uppercased())
                .font(.system(size: 10, weight: .bold, design: .rounded))
                .foregroundStyle(ShellPalette.muted)
            Text(value)
                .font(.system(size: 12, design: .monospaced))
                .foregroundStyle(ShellPalette.text)
                .textSelection(.enabled)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(
            RoundedRectangle(cornerRadius: ShellMetrics.radiusSmall, style: .continuous)
                .fill(ShellPalette.panelAlt)
        )
        .overlay(
            RoundedRectangle(cornerRadius: ShellMetrics.radiusSmall, style: .continuous)
                .stroke(ShellPalette.border)
        )
    }
}
