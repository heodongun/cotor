import SwiftUI

extension MeetingRoomOfficeZone: Identifiable {
    var id: String { rawValue }
}

struct MeetingRoomRenderPlan: Hashable {
    enum Mode: String, Hashable {
        case full
        case simplified
        case grouped

        var label: String {
            switch self {
            case .full:
                return "FULL"
            case .simplified:
                return "LOW"
            case .grouped:
                return "GROUPED"
            }
        }
    }

    let mode: Mode
    let visibleAgents: [MeetingRoomProjectionAgent]
    let hiddenAgentCount: Int
    let visibleFlows: [MeetingRoomFlowItem]
    let shouldAnimate: Bool

    var animationKey: String {
        [
            mode.rawValue,
            visibleAgents.map { "\($0.id):\($0.visualState.rawValue):\($0.currentIssueId ?? "-")" }.joined(separator: "|"),
            visibleFlows.map { "\($0.id):\($0.kind.rawValue):\($0.progress)" }.joined(separator: "|"),
        ].joined(separator: "::")
    }

    static func build(
        projection: MeetingRoomProjection,
        isCompact: Bool,
        reduceMotion: Bool,
        lowResourceMode: Bool,
        isSceneActive: Bool
    ) -> MeetingRoomRenderPlan {
        let agentCount = projection.agents.count
        let mode: Mode
        if agentCount >= 50 {
            mode = .grouped
        } else if agentCount >= 20 || lowResourceMode || reduceMotion || isCompact {
            mode = .simplified
        } else {
            mode = .full
        }

        let maxAgents: Int
        let maxFlows: Int
        switch mode {
        case .full:
            maxAgents = agentCount
            maxFlows = 10
        case .simplified:
            maxAgents = min(agentCount, isCompact ? 10 : 18)
            maxFlows = 6
        case .grouped:
            maxAgents = min(agentCount, 12)
            maxFlows = 4
        }

        let prioritizedAgents = projection.agents.sorted { lhs, rhs in
            if lhs.visualState.renderPriority != rhs.visualState.renderPriority {
                return lhs.visualState.renderPriority > rhs.visualState.renderPriority
            }
            return lhs.id < rhs.id
        }
        let visibleAgents = Array(prioritizedAgents.prefix(maxAgents))
        let visibleFlows = Array(projection.flows.prefix(maxFlows))
        let hasActiveWork = visibleAgents.contains { $0.visualState == .running || $0.visualState == .review }
        let shouldAnimate = isSceneActive &&
            !reduceMotion &&
            !lowResourceMode &&
            mode == .full &&
            agentCount <= 24 &&
            (!visibleFlows.isEmpty || hasActiveWork || !visibleAgents.isEmpty)

        return MeetingRoomRenderPlan(
            mode: mode,
            visibleAgents: visibleAgents,
            hiddenAgentCount: max(0, agentCount - visibleAgents.count),
            visibleFlows: visibleFlows,
            shouldAnimate: shouldAnimate
        )
    }
}

struct MeetingRoomView: View {
    let projection: MeetingRoomProjection
    let language: AppLanguage
    let inboxCount: Int
    let isCompact: Bool

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var scenePhase
    @AppStorage("meetingRoomLowResourceMode") private var lowResourceMode = false
    @State private var selectedAgent: MeetingRoomProjectionAgent?
    @State private var selectedFlow: MeetingRoomFlowItem?
    @State private var selectedZone: MeetingRoomOfficeZone?

    private var renderPlan: MeetingRoomRenderPlan {
        MeetingRoomRenderPlan.build(
            projection: projection,
            isCompact: isCompact,
            reduceMotion: reduceMotion,
            lowResourceMode: lowResourceMode,
            isSceneActive: scenePhase == .active
        )
    }

    var body: some View {
        let plan = renderPlan
        return VStack(alignment: .leading, spacing: 10) {
            header(plan: plan)

            GeometryReader { geometry in
                if plan.shouldAnimate {
                    TimelineView(.periodic(from: .now, by: 1.1)) { timeline in
                        officeStage(size: geometry.size, plan: plan, phase: timeline.date.timeIntervalSinceReferenceDate)
                    }
                } else {
                    officeStage(size: geometry.size, plan: plan, phase: 0)
                }
            }
            .frame(height: isCompact ? 360 : 500)
        }
        .sheet(item: $selectedAgent) { agent in
            MeetingRoomProjectionAgentSheet(agent: agent, language: language)
        }
        .sheet(item: $selectedFlow) { flow in
            MeetingRoomProjectionFlowSheet(
                flow: flow,
                issue: projection.issues.first { $0.id == flow.issueId },
                language: language
            )
        }
        .sheet(item: $selectedZone) { zone in
            MeetingRoomProjectionZoneSheet(
                zone: zone,
                projection: projection,
                language: language
            )
        }
    }

    private func officeStage(size: CGSize, plan: MeetingRoomRenderPlan, phase: TimeInterval) -> some View {
        ZStack {
            officeBackdrop(size: size)
            handoffLines(size: size, plan: plan, phase: phase)

            ForEach(zoneButtons(size: size), id: \.zone) { item in
                zoneButton(item)
                    .position(item.point)
            }

            ForEach(plan.visibleFlows) { flow in
                workCard(flow, size: size, phase: phase, animated: plan.shouldAnimate)
            }

            ForEach(Array(plan.visibleAgents.enumerated()), id: \.element.id) { index, agent in
                agentButton(agent, index: index, size: size, plan: plan, phase: phase)
            }

            hiddenAgentsBadge(size: size, plan: plan)
            inboxBoard(size: size)
        }
        .animation(plan.shouldAnimate ? .easeInOut(duration: 0.35) : nil, value: plan.animationKey)
    }

    private func header(plan: MeetingRoomRenderPlan) -> some View {
        HStack(spacing: 8) {
            ShellTag(text: "\(language("Agents", "에이전트")) \(projection.agents.count)", tint: ShellPalette.accent)
            ShellTag(text: "\(language("Running", "작업 중")) \(projection.agents.filter { $0.visualState == .running }.count)", tint: ShellPalette.success)
            ShellTag(text: "\(language("Review", "리뷰")) \(projection.reviewCount)", tint: ShellPalette.warning)
            ShellTag(text: "\(language("Signals", "신호")) \(projection.activityCount)", tint: ShellPalette.panelRaised)
            if plan.mode != .full {
                ShellTag(text: plan.mode.label, tint: ShellPalette.warning)
            }
            Spacer(minLength: 0)
            Button {
                lowResourceMode.toggle()
            } label: {
                Text(lowResourceMode ? language("Low on", "저전력 켬") : language("Low off", "저전력 끔"))
                    .font(.system(size: 9, weight: .heavy, design: .monospaced))
                    .foregroundStyle(lowResourceMode ? ShellPalette.warning : ShellPalette.muted)
                    .padding(.horizontal, 7)
                    .padding(.vertical, 4)
                    .background(ShellPalette.panelAlt)
                    .clipShape(RoundedRectangle(cornerRadius: 2, style: .continuous))
            }
            .buttonStyle(.plain)
            .accessibilityLabel(language("Toggle Meeting Room low resource mode", "미팅룸 저자원 모드 전환"))
            Text(runtimeLabel)
                .font(.system(size: 10, weight: .heavy, design: .monospaced))
                .foregroundStyle(ShellPalette.muted)
                .lineLimit(1)
        }
        .accessibilityElement(children: .combine)
    }

    private var runtimeLabel: String {
        "\(projection.runtimeStatus) · \(projection.runtimeBackendHealth)"
    }

    private func officeBackdrop(size: CGSize) -> some View {
        ZStack {
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .fill(Color(red: 0.10, green: 0.09, blue: 0.12))
                .overlay(
                    RoundedRectangle(cornerRadius: 10, style: .continuous)
                        .stroke(ShellPalette.line, lineWidth: 1)
                )

            HStack(spacing: 0) {
                Rectangle().fill(Color(red: 0.22, green: 0.15, blue: 0.09).opacity(0.74))
                Rectangle().fill(Color(red: 0.10, green: 0.20, blue: 0.25).opacity(0.70))
            }
            .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))

            Rectangle()
                .fill(Color(red: 0.50, green: 0.42, blue: 0.32).opacity(0.30))
                .frame(height: size.height * 0.22)
                .position(x: size.width / 2, y: size.height * 0.11)

            floorGrid(size: size)

            pixelShelf(size: size, x: 0.18, y: 0.14)
            pixelShelf(size: size, x: 0.70, y: 0.14)
            pixelDesk(size: size, x: 0.21, y: 0.72, tint: Color(red: 0.54, green: 0.33, blue: 0.16))
            pixelDesk(size: size, x: 0.78, y: 0.72, tint: Color(red: 0.54, green: 0.33, blue: 0.16))
            pixelDesk(size: size, x: 0.76, y: 0.34, tint: Color(red: 0.54, green: 0.33, blue: 0.16))
            pixelDesk(size: size, x: 0.46, y: 0.52, tint: Color(red: 0.54, green: 0.33, blue: 0.16))
            pixelPlant(size: size, x: 0.07, y: 0.84)
            pixelPlant(size: size, x: 0.92, y: 0.84)
            pixelPlant(size: size, x: 0.54, y: 0.24)

            Text("✦ COTOR AGENT OFFICE ✦")
                .font(.system(size: 12, weight: .heavy, design: .monospaced))
                .tracking(1)
                .foregroundStyle(ShellPalette.text)
                .padding(.horizontal, 14)
                .padding(.vertical, 7)
                .background(Color(red: 0.15, green: 0.12, blue: 0.10).opacity(0.92))
                .overlay(
                    RoundedRectangle(cornerRadius: 3, style: .continuous)
                        .stroke(Color(red: 0.75, green: 0.54, blue: 0.30).opacity(0.34), lineWidth: 1)
                )
                .clipShape(RoundedRectangle(cornerRadius: 3, style: .continuous))
                .position(x: size.width * 0.50, y: size.height * 0.06)

            VStack(alignment: .leading, spacing: 3) {
                Text(language("LIVE COMPANY", "회사 라이브"))
                    .font(.system(size: 8, weight: .heavy, design: .monospaced))
                    .foregroundStyle(ShellPalette.accent)
                Text("\(projection.activeIssueCount) \(language("active", "활성")) · \(projection.blockedIssueCount) \(language("blocked", "차단"))")
                    .font(.system(size: 10, weight: .bold, design: .monospaced))
                    .foregroundStyle(ShellPalette.text)
            }
            .padding(8)
            .frame(width: min(size.width * 0.25, 160), alignment: .leading)
            .background(ShellPalette.panelRaised.opacity(0.72))
            .clipShape(RoundedRectangle(cornerRadius: 4, style: .continuous))
            .position(x: size.width * 0.50, y: size.height * 0.14)
        }
        .accessibilityHidden(true)
    }

    private func floorGrid(size: CGSize) -> some View {
        ZStack {
            ForEach(1..<7, id: \.self) { index in
                Rectangle()
                    .fill(ShellPalette.line.opacity(0.10))
                    .frame(width: 1, height: size.height)
                    .position(x: size.width * CGFloat(index) / 7, y: size.height / 2)
                Rectangle()
                    .fill(ShellPalette.line.opacity(0.10))
                    .frame(width: size.width, height: 1)
                    .position(x: size.width / 2, y: size.height * CGFloat(index) / 7)
            }
        }
        .accessibilityHidden(true)
    }

    private func zoneButtons(size: CGSize) -> [MeetingRoomZoneButton] {
        [
            MeetingRoomZoneButton(zone: .planningBoard, title: language("Planning", "기획"), count: projection.activeIssueCount, point: zonePoint(.planningBoard, size: size)),
            MeetingRoomZoneButton(zone: .reviewDesk, title: language("Review", "리뷰"), count: projection.reviewCount, point: zonePoint(.reviewDesk, size: size)),
            MeetingRoomZoneButton(zone: .blockerZone, title: language("Blocked", "차단"), count: projection.blockedIssueCount, point: zonePoint(.blockerZone, size: size)),
            MeetingRoomZoneButton(zone: .costPanel, title: language("Cost", "비용"), count: projection.todaySpentCents, point: zonePoint(.costPanel, size: size)),
            MeetingRoomZoneButton(zone: .activityWall, title: language("Activity", "활동"), count: projection.activityCount, point: zonePoint(.activityWall, size: size)),
        ]
    }

    private func zoneButton(_ item: MeetingRoomZoneButton) -> some View {
        Button {
            selectedZone = item.zone
        } label: {
            HStack(spacing: 5) {
                Text(item.title)
                Text("\(item.count)")
                    .foregroundStyle(zoneTint(item.zone))
            }
            .font(.system(size: 8, weight: .heavy, design: .monospaced))
            .padding(.horizontal, 7)
            .padding(.vertical, 4)
            .background(ShellPalette.panel.opacity(0.82))
            .overlay(
                RoundedRectangle(cornerRadius: 2, style: .continuous)
                    .stroke(zoneTint(item.zone).opacity(0.38), lineWidth: 1)
            )
            .clipShape(RoundedRectangle(cornerRadius: 2, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(item.title), \(item.count)")
    }

    private func handoffLines(size: CGSize, plan: MeetingRoomRenderPlan, phase: TimeInterval) -> some View {
        ZStack {
            ForEach(Array(plan.visibleAgents.enumerated()), id: \.element.id) { index, agent in
                if agent.messageCount > 0 || agent.visualState == .review || agent.visualState == .running {
                    let from = agentPosition(agent, index: index, size: size, plan: plan, phase: phase)
                    let to = zonePoint(agent.visualState == .review ? .reviewDesk : .activityWall, size: size)
                    Path { path in
                        path.move(to: from)
                        path.addLine(to: to)
                    }
                    .stroke(
                        stateTint(agent.visualState).opacity(0.24),
                        style: StrokeStyle(lineWidth: 2, lineCap: .round, dash: [5, 8], dashPhase: CGFloat(phase * 8))
                    )

                    Circle()
                        .fill(stateTint(agent.visualState))
                        .frame(width: 6, height: 6)
                        .position(
                            x: from.x + (to.x - from.x) * CGFloat((sin(phase + Double(index)) + 1) / 2),
                            y: from.y + (to.y - from.y) * CGFloat((sin(phase + Double(index)) + 1) / 2)
                        )
                }
            }
        }
        .accessibilityHidden(true)
    }

    private func workCard(_ flow: MeetingRoomFlowItem, size: CGSize, phase: TimeInterval, animated: Bool) -> some View {
        let from = zonePoint(flow.from, size: size)
        let to = zonePoint(flow.to, size: size)
        let baseProgress = CGFloat(min(max(flow.progress, 0), 1))
        let pulse = animated ? CGFloat((sin(phase * 0.9 + Double(abs(flow.id.hashValue % 11))) + 1) / 2) * 0.10 : 0
        let progress = min(1, max(0, baseProgress + pulse))
        let point = CGPoint(
            x: from.x + (to.x - from.x) * progress,
            y: from.y + (to.y - from.y) * progress
        )

        return Button {
            selectedFlow = flow
        } label: {
            HStack(spacing: 5) {
                Image(systemName: flowIcon(flow.kind))
                    .font(.system(size: 9, weight: .black))
                VStack(alignment: .leading, spacing: 2) {
                    Text(flowLabel(flow.kind))
                        .font(.system(size: 8, weight: .heavy, design: .monospaced))
                    Text(roomLine(flow.title, limit: 22))
                        .font(.system(size: 9, weight: .bold, design: .monospaced))
                        .lineLimit(1)
                }
            }
            .foregroundStyle(ShellPalette.text)
            .padding(.horizontal, 8)
            .padding(.vertical, 5)
            .frame(width: min(size.width * 0.15, 136), alignment: .leading)
            .background(flowTint(flow.kind).opacity(0.88))
            .overlay(
                RoundedRectangle(cornerRadius: 6, style: .continuous)
                    .stroke(ShellPalette.text.opacity(0.18), lineWidth: 1)
            )
            .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
        }
        .buttonStyle(.plain)
        .position(point)
        .accessibilityLabel("\(flowLabel(flow.kind)): \(flow.title)")
    }

    private func agentButton(_ agent: MeetingRoomProjectionAgent, index: Int, size: CGSize, plan: MeetingRoomRenderPlan, phase: TimeInterval) -> some View {
        Button {
            selectedAgent = agent
        } label: {
            PixelAgentSprite(agent: agent, language: language, phase: phase, isAnimated: plan.shouldAnimate)
                .frame(width: agentWidth, height: 98)
                .accessibilityHidden(true)
        }
        .buttonStyle(.plain)
        .position(agentPosition(agent, index: index, size: size, plan: plan, phase: phase))
        .accessibilityLabel("\(agent.role), \(agent.status), \(agent.actionLine)")
    }

    private var agentWidth: CGFloat {
        if isCompact {
            return projection.agents.count > 8 ? 58 : 66
        }
        return projection.agents.count > 10 ? 62 : 70
    }

    private func agentPosition(_ agent: MeetingRoomProjectionAgent, index: Int, size: CGSize, plan: MeetingRoomRenderPlan, phase: TimeInterval = 0) -> CGPoint {
        let seat = seatPoint(index: index, count: plan.visibleAgents.count, size: size)
        let target = zonePoint(agent.zone, size: size)
        let weight: CGFloat
        switch agent.visualState {
        case .idle, .done:
            weight = 0.0
        case .running:
            weight = 0.28
        case .review, .blocked, .failed, .costBlocked:
            weight = 0.86
        }
        let seed = Double(index) * 1.31
        let walk = plan.shouldAnimate ? CGFloat(sin(phase * 0.8 + seed)) : 0
        let bob = plan.shouldAnimate ? CGFloat(cos(phase * 0.65 + seed)) : 0
        let activityScale: CGFloat = agent.visualState == .idle ? 2 : 7
        return CGPoint(
            x: seat.x + (target.x - seat.x) * weight + walk * activityScale,
            y: seat.y + (target.y - seat.y) * weight + bob * (activityScale * 0.55)
        )
    }

    private func seatPoint(index: Int, count: Int, size: CGSize) -> CGPoint {
        let points: [(CGFloat, CGFloat)] = [
            (0.20, 0.64),
            (0.36, 0.40),
            (0.64, 0.40),
            (0.80, 0.64),
            (0.32, 0.80),
            (0.68, 0.80),
            (0.50, 0.61),
            (0.19, 0.39),
            (0.81, 0.39),
            (0.50, 0.82),
        ]
        if count <= points.count {
            let point = points[index % points.count]
            return CGPoint(x: size.width * point.0, y: size.height * point.1)
        }

        let columns = min(5, max(3, Int(ceil(sqrt(Double(count))))))
        let row = index / columns
        let column = index % columns
        let x = 0.15 + 0.70 * CGFloat(column) / CGFloat(max(1, columns - 1))
        let y = 0.32 + 0.44 * CGFloat(row) / CGFloat(max(1, Int(ceil(Double(count) / Double(columns))) - 1))
        return CGPoint(x: size.width * x, y: size.height * y)
    }

    @ViewBuilder
    private func hiddenAgentsBadge(size: CGSize, plan: MeetingRoomRenderPlan) -> some View {
        if plan.hiddenAgentCount > 0 {
            Button {
                selectedZone = .agentDesk
            } label: {
                VStack(spacing: 3) {
                    Text("+\(plan.hiddenAgentCount)")
                        .font(.system(size: 15, weight: .heavy, design: .monospaced))
                    Text(language("grouped", "그룹"))
                        .font(.system(size: 8, weight: .heavy, design: .monospaced))
                }
                .foregroundStyle(ShellPalette.text)
                .padding(.horizontal, 11)
                .padding(.vertical, 8)
                .background(ShellPalette.panelRaised.opacity(0.88))
                .overlay(
                    RoundedRectangle(cornerRadius: 2, style: .continuous)
                        .stroke(ShellPalette.warning.opacity(0.38), lineWidth: 1)
                )
                .clipShape(RoundedRectangle(cornerRadius: 2, style: .continuous))
            }
            .buttonStyle(.plain)
            .position(x: size.width * 0.50, y: size.height * 0.83)
            .accessibilityLabel(language("\(plan.hiddenAgentCount) grouped agents", "\(plan.hiddenAgentCount)명 에이전트 그룹 표시"))
        }
    }

    private func zonePoint(_ zone: MeetingRoomOfficeZone, size: CGSize) -> CGPoint {
        let point: (CGFloat, CGFloat)
        switch zone {
        case .agentDesk:
            point = (0.52, 0.54)
        case .planningBoard:
            point = (0.50, 0.16)
        case .reviewDesk:
            point = (0.78, 0.40)
        case .blockerZone:
            point = (0.16, 0.48)
        case .costPanel:
            point = (0.15, 0.82)
        case .activityWall:
            point = (0.84, 0.18)
        case .mergeLane:
            point = (0.84, 0.80)
        }
        return CGPoint(x: size.width * point.0, y: size.height * point.1)
    }

    private func stateTint(_ state: MeetingRoomVisualState) -> Color {
        switch state {
        case .idle:
            return ShellPalette.faint
        case .running:
            return ShellPalette.success
        case .review:
            return ShellPalette.warning
        case .blocked, .failed:
            return ShellPalette.danger
        case .done:
            return ShellPalette.success
        case .costBlocked:
            return ShellPalette.warning
        }
    }

    private func inboxBoard(size: CGSize) -> some View {
        VStack(spacing: 3) {
            Text(language("YOUR INBOX", "YOUR INBOX"))
            Text("\(inboxCount)")
                .font(.system(size: 14, weight: .heavy, design: .monospaced))
        }
        .font(.system(size: 10, weight: .bold, design: .monospaced))
        .foregroundStyle(ShellPalette.text)
        .tracking(0.6)
        .padding(.horizontal, 18)
        .padding(.vertical, 12)
        .background(ShellPalette.panelRaised.opacity(0.62))
        .overlay(
            RoundedRectangle(cornerRadius: 2, style: .continuous)
                .stroke(ShellPalette.accentWarm.opacity(0.26), lineWidth: 2)
        )
        .clipShape(RoundedRectangle(cornerRadius: 2, style: .continuous))
        .position(x: size.width * 0.52, y: size.height * 0.91)
        .accessibilityHidden(true)
    }

    private func pixelShelf(size: CGSize, x: CGFloat, y: CGFloat) -> some View {
        VStack(spacing: 3) {
            ForEach(0..<2, id: \.self) { row in
                HStack(spacing: 3) {
                    ForEach(0..<5, id: \.self) { column in
                        RoundedRectangle(cornerRadius: 1, style: .continuous)
                            .fill([ShellPalette.accent, ShellPalette.accentWarm, ShellPalette.success, ShellPalette.warning, ShellPalette.faint][(row + column) % 5].opacity(0.72))
                            .frame(width: 6, height: 16)
                    }
                }
                .padding(.horizontal, 7)
                .frame(width: min(size.width * 0.21, 112), height: 22)
                .background(ShellPalette.panel.opacity(0.62))
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 2, style: .continuous))
        .position(x: size.width * x, y: size.height * y)
        .accessibilityHidden(true)
    }

    private func pixelDesk(size: CGSize, x: CGFloat, y: CGFloat, tint: Color) -> some View {
        ZStack(alignment: .top) {
            RoundedRectangle(cornerRadius: 2, style: .continuous)
                .fill(tint.opacity(0.40))
                .frame(width: 96, height: 38)
                .overlay(
                    RoundedRectangle(cornerRadius: 2, style: .continuous)
                        .stroke(ShellPalette.lineStrong.opacity(0.55), lineWidth: 1)
                )
            RoundedRectangle(cornerRadius: 2, style: .continuous)
                .fill(ShellPalette.panelRaised)
                .frame(width: 30, height: 18)
                .offset(y: -12)
            Circle()
                .fill(ShellPalette.accentWarm)
                .frame(width: 9, height: 9)
                .offset(x: 34, y: -5)
        }
        .frame(width: 100, height: 52)
        .position(x: size.width * x, y: size.height * y)
        .accessibilityHidden(true)
    }

    private func pixelPlant(size: CGSize, x: CGFloat, y: CGFloat) -> some View {
        VStack(spacing: 0) {
            HStack(spacing: 0) {
                Triangle().fill(ShellPalette.success).frame(width: 12, height: 18).rotationEffect(.degrees(-18))
                Triangle().fill(ShellPalette.success.opacity(0.82)).frame(width: 13, height: 21)
                Triangle().fill(ShellPalette.success).frame(width: 12, height: 18).rotationEffect(.degrees(18))
            }
            RoundedRectangle(cornerRadius: 2, style: .continuous)
                .fill(ShellPalette.accentWarm.opacity(0.62))
                .frame(width: 23, height: 17)
        }
        .frame(width: 40, height: 42)
        .position(x: size.width * x, y: size.height * y)
        .accessibilityHidden(true)
    }

    private func roomLine(_ value: String, limit: Int) -> String {
        let trimmed = value.replacingOccurrences(of: "\n", with: " ").trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.count <= limit {
            return trimmed
        }
        return String(trimmed.prefix(limit - 1)) + "…"
    }

    private func zoneTint(_ zone: MeetingRoomOfficeZone) -> Color {
        switch zone {
        case .agentDesk, .activityWall:
            return ShellPalette.accent
        case .planningBoard:
            return ShellPalette.accentWarm
        case .reviewDesk, .costPanel:
            return ShellPalette.warning
        case .blockerZone:
            return ShellPalette.danger
        case .mergeLane:
            return ShellPalette.success
        }
    }

    private func flowTint(_ kind: MeetingRoomFlowKind) -> Color {
        switch kind {
        case .goalToIssue, .issueToAgent, .a2aMessage:
            return ShellPalette.accentWarm
        case .agentWorking, .reviewToMerge:
            return ShellPalette.success
        case .agentToReview, .costBlocked:
            return ShellPalette.warning
        case .blocked:
            return ShellPalette.danger
        }
    }

    private func flowIcon(_ kind: MeetingRoomFlowKind) -> String {
        switch kind {
        case .goalToIssue:
            return "point.topleft.down.curvedto.point.bottomright.up"
        case .issueToAgent:
            return "tray.and.arrow.down.fill"
        case .agentWorking:
            return "keyboard.fill"
        case .a2aMessage:
            return "bubble.left.and.bubble.right.fill"
        case .agentToReview:
            return "checklist"
        case .reviewToMerge:
            return "arrow.triangle.merge"
        case .blocked:
            return "exclamationmark.triangle.fill"
        case .costBlocked:
            return "pause.circle.fill"
        }
    }

    private func flowLabel(_ kind: MeetingRoomFlowKind) -> String {
        switch kind {
        case .goalToIssue:
            return language("goal→issue", "목표→이슈")
        case .issueToAgent:
            return language("dispatch", "배정")
        case .agentWorking:
            return language("working", "작업")
        case .a2aMessage:
            return "A2A"
        case .agentToReview:
            return language("review", "리뷰")
        case .reviewToMerge:
            return language("merge", "머지")
        case .blocked:
            return language("blocked", "차단")
        case .costBlocked:
            return language("cost", "비용")
        }
    }
}

private struct Triangle: Shape {
    func path(in rect: CGRect) -> Path {
        var path = Path()
        path.move(to: CGPoint(x: rect.midX, y: rect.minY))
        path.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY))
        path.addLine(to: CGPoint(x: rect.minX, y: rect.maxY))
        path.closeSubpath()
        return path
    }
}

private struct MeetingRoomZoneButton {
    let zone: MeetingRoomOfficeZone
    let title: String
    let count: Int
    let point: CGPoint
}

private struct PixelAgentSprite: View {
    let agent: MeetingRoomProjectionAgent
    let language: AppLanguage
    let phase: TimeInterval
    let isAnimated: Bool

    var body: some View {
        VStack(spacing: 5) {
            if showsSpeechBubble {
                speechBubble
                    .transition(.opacity)
            } else {
                Color.clear.frame(height: 18)
            }

            ZStack(alignment: .topTrailing) {
                spriteBody
                stateBadge
                    .offset(x: 8, y: -3)
            }

            VStack(spacing: 2) {
                Text(shortRole)
                    .font(.system(size: 9, weight: .heavy, design: .monospaced))
                    .foregroundStyle(ShellPalette.text)
                    .lineLimit(1)
                Text(shortStatus)
                    .font(.system(size: 8, weight: .bold, design: .monospaced))
                    .foregroundStyle(ShellPalette.muted)
                    .lineLimit(1)
            }
        }
    }

    private var spriteBody: some View {
        let bob = isAnimated ? CGFloat(sin(phase * 1.3 + Double(abs(agent.id.hashValue % 7)))) * bobAmount : 0
        let step = isAnimated ? CGFloat(sin(phase * 2.2 + Double(abs(agent.id.hashValue % 5)))) : 0

        return ZStack(alignment: .bottom) {
            if agent.visualState == .idle || agent.visualState == .running || agent.visualState == .done {
                miniDesk
                    .offset(y: 19)
            }

            VStack(spacing: 0) {
                compactHead

                HStack(spacing: 0) {
                    Rectangle()
                        .fill(skinTint.opacity(0.96))
                        .frame(width: 5, height: 15)
                        .offset(x: -1, y: 3)
                        .rotationEffect(.degrees(Double(step * 8)))

                    Rectangle()
                        .fill(outfitTint)
                        .frame(width: 23, height: 27)
                        .overlay(
                            Image(systemName: roleIcon)
                                .font(.system(size: 8, weight: .black))
                                .foregroundStyle(ShellPalette.text.opacity(0.86))
                        )

                    Rectangle()
                        .fill(skinTint.opacity(0.96))
                        .frame(width: 5, height: 15)
                        .offset(x: 1, y: 3)
                        .rotationEffect(.degrees(Double(step * -8)))
                }

                HStack(spacing: 4) {
                    Rectangle()
                        .fill(ShellPalette.panelDeeper)
                        .frame(width: 8, height: 8)
                        .offset(y: isAnimated ? max(0, step) * 2 : 0)
                    Rectangle()
                        .fill(ShellPalette.panelDeeper)
                        .frame(width: 8, height: 8)
                        .offset(y: isAnimated ? max(0, -step) * 2 : 0)
                }
            }
            .offset(y: bob)
            .rotationEffect(.degrees(agent.visualState == .running && isAnimated ? Double(step * 1.2) : 0))

            if agent.visualState == .running {
                deskKeyboard
                    .offset(y: 14)
            }
        }
        .frame(width: 58, height: 66)
    }

    private var compactHead: some View {
        ZStack(alignment: .top) {
            Rectangle()
                .fill(skinTint)
                .frame(width: 25, height: 20)
                .overlay(
                    Rectangle()
                        .stroke(Color.black.opacity(0.18), lineWidth: 1)
                )

            Rectangle()
                .fill(hairTint)
                .frame(width: 27, height: 7)
                .offset(y: -3)

            HStack(spacing: 7) {
                eye(left: true)
                    .frame(width: 4, height: 4)
                eye(left: false)
                    .frame(width: 4, height: 4)
            }
            .scaleEffect(0.62)
            .offset(y: 6)

            mouth
                .scaleEffect(0.48)
                .offset(y: 13)

            compactHeadwear
        }
        .frame(width: 30, height: 23)
    }

    private var deskKeyboard: some View {
        HStack(spacing: 2) {
            ForEach(0..<5, id: \.self) { index in
                RoundedRectangle(cornerRadius: 1, style: .continuous)
                    .fill(index % 2 == 0 && agent.visualState == .running && isAnimated ? stateTint : ShellPalette.lineStrong)
                    .frame(width: 5, height: 3)
            }
        }
        .padding(4)
        .background(ShellPalette.panelDeeper)
        .clipShape(RoundedRectangle(cornerRadius: 1, style: .continuous))
        .offset(y: -2)
    }

    private var miniDesk: some View {
        VStack(spacing: 0) {
            Rectangle()
                .fill(Color(red: 0.45, green: 0.27, blue: 0.12))
                .frame(width: 54, height: 15)
                .overlay(
                    HStack(spacing: 3) {
                        Rectangle().fill(ShellPalette.panelRaised).frame(width: 12, height: 8)
                        Rectangle().fill(ShellPalette.accentWarm.opacity(0.85)).frame(width: 4, height: 4)
                    }
                )
            HStack(spacing: 32) {
                Rectangle().fill(Color.black.opacity(0.42)).frame(width: 5, height: 10)
                Rectangle().fill(Color.black.opacity(0.42)).frame(width: 5, height: 10)
            }
        }
        .accessibilityHidden(true)
    }

    private var stateBadge: some View {
        Image(systemName: stateIcon)
            .font(.system(size: 8, weight: .black))
            .foregroundStyle(ShellPalette.text)
            .padding(3)
            .background(stateTint.opacity(0.82))
            .clipShape(RoundedRectangle(cornerRadius: 2, style: .continuous))
    }

    private var speechBubble: some View {
        Text(roomLine(agent.actionLine, limit: 18))
            .font(.system(size: 8, weight: .heavy, design: .monospaced))
            .foregroundStyle(ShellPalette.text)
            .padding(.horizontal, 6)
            .padding(.vertical, 3)
            .background(ShellPalette.panelRaised.opacity(0.95))
            .overlay(
                RoundedRectangle(cornerRadius: 5, style: .continuous)
                    .stroke(stateTint.opacity(0.28), lineWidth: 1)
            )
            .clipShape(RoundedRectangle(cornerRadius: 5, style: .continuous))
    }

    private var showsSpeechBubble: Bool {
        switch agent.visualState {
        case .idle:
            return agent.messageCount > 0
        case .done:
            return agent.messageCount > 0
        case .running, .review, .blocked, .failed, .costBlocked:
            return true
        }
    }

    private var bobAmount: CGFloat {
        switch agent.visualState {
        case .idle:
            return 1.5
        case .running, .review:
            return 4
        case .blocked, .failed, .costBlocked:
            return 2
        case .done:
            return 2.5
        }
    }

    private var hair: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 5, style: .continuous)
                .fill(hairTint)
                .frame(width: 42, height: 13)
            if lowerRole.contains("ceo") || lowerRole.contains("lead") {
                Color.clear.frame(width: 42, height: 13)
            } else {
                HStack(spacing: 2) {
                    ForEach(0..<3, id: \.self) { index in
                        RoundedRectangle(cornerRadius: 1, style: .continuous)
                            .fill(hairTint.opacity(0.92))
                            .frame(width: 7, height: index == 1 ? 11 : 8)
                    }
                }
                .offset(y: 5)
            }
        }
        .offset(y: 7)
        .zIndex(2)
    }

    private func eye(left: Bool) -> some View {
        Group {
            switch agent.expression {
            case .focused:
                RoundedRectangle(cornerRadius: 1, style: .continuous)
                    .frame(width: 4, height: 7)
            case .happy:
                RoundedRectangle(cornerRadius: 1, style: .continuous)
                    .frame(width: 7, height: 3)
                    .rotationEffect(.degrees(left ? 12 : -12))
            case .confused:
                Circle()
                    .frame(width: left ? 4 : 6, height: left ? 4 : 6)
            case .sad:
                Capsule()
                    .frame(width: 7, height: 2)
                    .rotationEffect(.degrees(left ? -10 : 10))
            case .warning:
                RoundedRectangle(cornerRadius: 1, style: .continuous)
                    .frame(width: 7, height: 7)
            case .idle:
                RoundedRectangle(cornerRadius: 1, style: .continuous)
                    .frame(width: 5, height: 5)
            }
        }
        .foregroundStyle(ShellPalette.text)
    }

    @ViewBuilder
    private var mouth: some View {
        switch agent.expression {
        case .happy:
            Capsule().fill(stateTint).frame(width: 18, height: 5)
        case .confused, .warning:
            Text("o").font(.system(size: 12, weight: .bold, design: .monospaced)).foregroundStyle(stateTint)
        case .sad:
            Capsule().fill(stateTint.opacity(0.7)).frame(width: 14, height: 3).rotationEffect(.degrees(180))
        case .focused:
            RoundedRectangle(cornerRadius: 1, style: .continuous).fill(stateTint).frame(width: 14, height: 4)
        case .idle:
            RoundedRectangle(cornerRadius: 1, style: .continuous).fill(stateTint.opacity(0.75)).frame(width: 12, height: 3)
        }
    }

    @ViewBuilder
    private var headwear: some View {
        if lowerRole.contains("ceo") || lowerRole.contains("lead") {
            HStack(spacing: 1) {
                Triangle().fill(ShellPalette.warning).frame(width: 7, height: 7)
                Triangle().fill(ShellPalette.warning).frame(width: 9, height: 9)
                Triangle().fill(ShellPalette.warning).frame(width: 7, height: 7)
            }
            .offset(y: -25)
        } else if lowerRole.contains("builder") || lowerRole.contains("engineer") || lowerRole.contains("backend") {
            RoundedRectangle(cornerRadius: 1, style: .continuous)
                .fill(ShellPalette.panelRaised)
                .frame(width: 24, height: 7)
                .offset(y: -24)
        } else if lowerRole.contains("ux") || lowerRole.contains("design") || lowerRole.contains("ui") {
            Capsule()
                .fill(ShellPalette.accentWarm)
                .frame(width: 25, height: 6)
                .rotationEffect(.degrees(-8))
                .offset(y: -24)
        }
    }

    @ViewBuilder
    private var compactHeadwear: some View {
        if lowerRole.contains("ceo") || lowerRole.contains("lead") {
            HStack(spacing: 0) {
                Triangle().fill(ShellPalette.warning).frame(width: 5, height: 5)
                Triangle().fill(ShellPalette.warning).frame(width: 6, height: 6)
                Triangle().fill(ShellPalette.warning).frame(width: 5, height: 5)
            }
            .offset(y: -10)
        } else if lowerRole.contains("builder") || lowerRole.contains("engineer") || lowerRole.contains("backend") {
            Rectangle()
                .fill(ShellPalette.panelRaised)
                .frame(width: 19, height: 4)
                .offset(y: -7)
        } else if lowerRole.contains("ux") || lowerRole.contains("design") || lowerRole.contains("ui") {
            Rectangle()
                .fill(ShellPalette.accentWarm)
                .frame(width: 18, height: 4)
                .rotationEffect(.degrees(-8))
                .offset(y: -8)
        }
    }

    private var shortRole: String {
        let trimmed = agent.role.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty {
            return language("Agent", "에이전트")
        }
        if lowerRole.contains("product") {
            return language("Planner", "기획자")
        }
        if lowerRole.contains("ux") || lowerRole.contains("ui") || lowerRole.contains("design") {
            return "UX"
        }
        if lowerRole.contains("backend") {
            return "Backend"
        }
        if lowerRole.contains("builder") {
            return "Builder"
        }
        return String(trimmed.prefix(12))
    }

    private var shortStatus: String {
        switch agent.visualState {
        case .idle:
            return language("IDLE", "대기")
        case .running:
            return language("RUN", "작업")
        case .review:
            return language("REVIEW", "리뷰")
        case .blocked:
            return language("BLOCK", "차단")
        case .failed:
            return language("FAIL", "실패")
        case .done:
            return language("DONE", "완료")
        case .costBlocked:
            return language("COST", "비용")
        }
    }

    private var lowerRole: String {
        agent.role.lowercased()
    }

    private var stateTint: Color {
        switch agent.visualState {
        case .idle:
            return ShellPalette.faint
        case .running:
            return ShellPalette.success
        case .review:
            return ShellPalette.warning
        case .blocked, .failed:
            return ShellPalette.danger
        case .done:
            return ShellPalette.success
        case .costBlocked:
            return ShellPalette.warning
        }
    }

    private var skinTint: Color {
        if lowerRole.contains("ceo") || lowerRole.contains("lead") {
            return Color(red: 0.93, green: 0.70, blue: 0.48)
        }
        if lowerRole.contains("qa") || lowerRole.contains("review") {
            return Color(red: 0.82, green: 0.65, blue: 0.48)
        }
        if lowerRole.contains("product") {
            return Color(red: 0.95, green: 0.74, blue: 0.55)
        }
        return Color(red: 0.88, green: 0.67, blue: 0.50)
    }

    private var hairTint: Color {
        if lowerRole.contains("ceo") || lowerRole.contains("lead") {
            return Color(red: 0.38, green: 0.24, blue: 0.13)
        }
        if lowerRole.contains("ux") || lowerRole.contains("design") || lowerRole.contains("ui") {
            return Color(red: 0.72, green: 0.49, blue: 0.26)
        }
        if lowerRole.contains("product") {
            return Color(red: 0.18, green: 0.16, blue: 0.20)
        }
        return Color(red: 0.25, green: 0.17, blue: 0.12)
    }

    private var outfitTint: Color {
        if lowerRole.contains("ceo") || lowerRole.contains("lead") {
            return Color(red: 0.52, green: 0.25, blue: 0.24)
        }
        if lowerRole.contains("product") {
            return Color(red: 0.22, green: 0.38, blue: 0.66)
        }
        if lowerRole.contains("builder") || lowerRole.contains("backend") || lowerRole.contains("engineer") {
            return Color(red: 0.18, green: 0.48, blue: 0.48)
        }
        if lowerRole.contains("ux") || lowerRole.contains("design") || lowerRole.contains("ui") {
            return Color(red: 0.38, green: 0.62, blue: 0.38)
        }
        if lowerRole.contains("qa") || lowerRole.contains("review") {
            return Color(red: 0.35, green: 0.53, blue: 0.78)
        }
        return Color(red: 0.40, green: 0.52, blue: 0.38)
    }

    private var roleIcon: String {
        if lowerRole.contains("ceo") || lowerRole.contains("lead") {
            return "crown.fill"
        }
        if lowerRole.contains("product") {
            return "doc.text.fill"
        }
        if lowerRole.contains("ux") || lowerRole.contains("design") || lowerRole.contains("ui") {
            return "paintpalette.fill"
        }
        return "terminal.fill"
    }

    private var stateIcon: String {
        switch agent.visualState {
        case .idle:
            return "chair"
        case .running:
            return "keyboard.fill"
        case .review:
            return "checklist"
        case .blocked:
            return "exclamationmark.triangle.fill"
        case .failed:
            return "xmark.octagon.fill"
        case .done:
            return "checkmark.seal.fill"
        case .costBlocked:
            return "pause.circle.fill"
        }
    }

    private func roomLine(_ value: String, limit: Int) -> String {
        let trimmed = value.replacingOccurrences(of: "\n", with: " ").trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.count <= limit {
            return trimmed
        }
        return String(trimmed.prefix(limit - 1)) + "…"
    }
}

private struct MeetingRoomProjectionAgentSheet: View {
    let agent: MeetingRoomProjectionAgent
    let language: AppLanguage

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(agent.role)
                .font(.system(size: 18, weight: .bold, design: .monospaced))
                .foregroundStyle(ShellPalette.text)
            HStack(spacing: 8) {
                ShellTag(text: agent.visualState.rawValue.uppercased(), tint: ShellPalette.accent)
                ShellTag(text: agent.zone.rawValue, tint: ShellPalette.warning)
                if let pullRequestState = agent.pullRequestState {
                    ShellTag(text: "PR \(pullRequestState)", tint: ShellPalette.success)
                }
            }
            detail(language("Current work", "현재 작업"), agent.currentIssueTitle ?? agent.detailLine)
            detail(language("Runtime state", "런타임 상태"), agent.status)
            detail(language("Log summary", "로그 요약"), agent.detailLine)
            ProgressView(value: agent.progress)
                .tint(ShellPalette.success)
            Spacer(minLength: 0)
        }
        .padding(22)
        .frame(width: 460, height: 340)
        .background(ShellPalette.panel)
    }

    private func detail(_ title: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title.uppercased())
                .font(.system(size: 9, weight: .heavy, design: .monospaced))
                .foregroundStyle(ShellPalette.faint)
            Text(value)
                .font(.system(size: 12, weight: .medium, design: .monospaced))
                .foregroundStyle(ShellPalette.text)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(10)
        .background(ShellPalette.panelAlt)
        .clipShape(RoundedRectangle(cornerRadius: 4, style: .continuous))
    }
}

private struct MeetingRoomProjectionFlowSheet: View {
    let flow: MeetingRoomFlowItem
    let issue: MeetingRoomIssueSummary?
    let language: AppLanguage

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(issue?.title ?? flow.title)
                .font(.system(size: 17, weight: .bold, design: .monospaced))
                .foregroundStyle(ShellPalette.text)
            HStack(spacing: 8) {
                ShellTag(text: flow.kind.rawValue, tint: ShellPalette.accentWarm)
                if let issue {
                    ShellTag(text: issue.status, tint: issue.status.uppercased().contains("BLOCK") ? ShellPalette.danger : ShellPalette.accent)
                }
                if let pullRequestState = issue?.pullRequestState {
                    ShellTag(text: "PR \(pullRequestState)", tint: ShellPalette.success)
                }
            }
            detail(language("Issue", "이슈"), issueSummary)
            detail(language("Movement", "이동"), "\(flow.from.rawValue) → \(flow.to.rawValue)")
            detail(language("Detail", "상세"), flow.detail)
            if let pullRequest = pullRequestSummary {
                detail(language("Pull request", "PR"), pullRequest)
            }
            ProgressView(value: flow.progress)
                .tint(ShellPalette.accentWarm)
            Spacer(minLength: 0)
        }
        .padding(22)
        .frame(width: 500, height: 380)
        .background(ShellPalette.panel)
    }

    private var issueSummary: String {
        guard let issue else {
            return flow.issueId ?? "-"
        }
        return [
            issue.kind,
            issue.id,
            issue.assigneeProfileId.map { "assignee=\($0)" },
            issue.transitionReason.map { "reason=\($0)" },
        ]
        .compactMap { $0 }
        .joined(separator: " · ")
    }

    private var pullRequestSummary: String? {
        guard let issue, issue.pullRequestNumber != nil || issue.pullRequestUrl != nil || issue.pullRequestState != nil else {
            return nil
        }
        return [
            issue.pullRequestNumber.map { "#\($0)" },
            issue.pullRequestState,
            issue.pullRequestUrl,
        ]
        .compactMap { $0 }
        .joined(separator: " · ")
    }

    private func detail(_ title: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title.uppercased())
                .font(.system(size: 9, weight: .heavy, design: .monospaced))
                .foregroundStyle(ShellPalette.faint)
            Text(value)
                .font(.system(size: 12, weight: .medium, design: .monospaced))
                .foregroundStyle(ShellPalette.text)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(10)
        .background(ShellPalette.panelAlt)
        .clipShape(RoundedRectangle(cornerRadius: 4, style: .continuous))
    }
}

private struct MeetingRoomProjectionZoneSheet: View {
    let zone: MeetingRoomOfficeZone
    let projection: MeetingRoomProjection
    let language: AppLanguage

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(title)
                .font(.system(size: 18, weight: .bold, design: .monospaced))
                .foregroundStyle(ShellPalette.text)
            Text(summary)
                .font(.system(size: 12, weight: .medium, design: .monospaced))
                .foregroundStyle(ShellPalette.muted)
                .fixedSize(horizontal: false, vertical: true)
            Divider()
            ForEach(lines, id: \.self) { line in
                Text(line)
                    .font(.system(size: 11, weight: .medium, design: .monospaced))
                    .foregroundStyle(ShellPalette.text)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(8)
                    .background(ShellPalette.panelAlt)
                    .clipShape(RoundedRectangle(cornerRadius: 3, style: .continuous))
            }
            Spacer(minLength: 0)
        }
        .padding(22)
        .frame(width: 480, height: 360)
        .background(ShellPalette.panel)
    }

    private var title: String {
        switch zone {
        case .agentDesk:
            return language("Agent desks", "에이전트 자리")
        case .planningBoard:
            return language("Planning board", "기획 보드")
        case .reviewDesk:
            return language("Review desk", "리뷰 데스크")
        case .blockerZone:
            return language("Blocker zone", "차단 구역")
        case .costPanel:
            return language("Cost panel", "비용 패널")
        case .activityWall:
            return language("Activity wall", "활동 월")
        case .mergeLane:
            return language("Merge lane", "머지 레인")
        }
    }

    private var summary: String {
        switch zone {
        case .costPanel:
            return language(
                "Today \(projection.todaySpentCents)c, month \(projection.monthSpentCents)c, paused \(projection.isCostBlocked).",
                "오늘 \(projection.todaySpentCents)c, 월 \(projection.monthSpentCents)c, 일시정지 \(projection.isCostBlocked)."
            )
        case .reviewDesk:
            return language("\(projection.reviewCount) review item(s) are waiting.", "\(projection.reviewCount)개 리뷰 항목이 대기 중입니다.")
        case .blockerZone:
            return language("\(projection.blockedIssueCount) blocked issue(s).", "\(projection.blockedIssueCount)개 이슈가 차단되었습니다.")
        default:
            return language("This zone is rendered from the latest app-server company snapshot.", "이 영역은 최신 app-server 회사 snapshot에서 렌더링됩니다.")
        }
    }

    private var lines: [String] {
        switch zone {
        case .costPanel:
            return [
                "runtime=\(projection.runtimeStatus)",
                "backend=\(projection.runtimeBackendHealth)",
                "costBlocked=\(projection.isCostBlocked)",
            ]
        case .reviewDesk:
            let reviews = projection.reviews.prefix(6).map { review in
                [
                    "issue=\(review.issueId)",
                    "status=\(review.status)",
                    review.pullRequestNumber.map { "PR #\($0)" },
                    review.pullRequestState,
                    review.checksSummary.map { "checks=\($0)" },
                    review.mergeability.map { "merge=\($0)" },
                ]
                .compactMap { $0 }
                .joined(separator: " · ")
            }
            if !reviews.isEmpty {
                return reviews
            }
            return Array(projection.flows.filter { $0.to == .reviewDesk }.map { $0.title }.prefix(6))
        case .blockerZone:
            return Array(
                projection.issues
                    .filter { issue in
                        let status = issue.status.uppercased()
                        return status.contains("BLOCK") || status.contains("FAIL")
                    }
                    .map { issue in
                        [issue.title, issue.status, issue.transitionReason]
                            .compactMap { $0 }
                            .joined(separator: " · ")
                    }
                    .prefix(6)
            )
        case .activityWall:
            return ["activityCount=\(projection.activityCount)", "prStates=\(projection.pullRequestStates.joined(separator: ", "))"]
        case .agentDesk, .planningBoard, .mergeLane:
            return Array(projection.agents.map { "\($0.role): \($0.status)" }.prefix(8))
        }
    }
}

private extension MeetingRoomVisualState {
    var renderPriority: Int {
        switch self {
        case .running:
            return 700
        case .review:
            return 600
        case .blocked, .failed, .costBlocked:
            return 500
        case .done:
            return 300
        case .idle:
            return 100
        }
    }
}
