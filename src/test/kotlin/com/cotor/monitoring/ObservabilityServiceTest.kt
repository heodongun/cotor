package com.cotor.monitoring

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.slf4j.Logger

class ObservabilityServiceTest : FunSpec({

    test("child trace context preserves trace id and parent span") {
        val logger = CapturingLogger()
        val service = ObservabilityService(StructuredLogger(logger), MetricsCollector())

        val pipelineCtx = service.startPipelineTrace("pipe", "p1")
        val stageCtx = service.childContext(pipelineCtx.traceId, pipelineCtx.spanId)

        stageCtx.traceId shouldBe pipelineCtx.traceId
        stageCtx.parentSpanId shouldBe pipelineCtx.spanId
        stageCtx.spanId.length shouldBe 16
    }

    test("record agent execution writes metrics") {
        val logger = CapturingLogger()
        val metrics = MetricsCollector()
        val service = ObservabilityService(StructuredLogger(logger), metrics)

        val ctx = TraceContext("trace", "span", "parent")
        service.recordAgentExecution("agent-a", 25L, true, ctx)

        val m = metrics.getMetrics("agent-a")
        m.totalExecutions shouldBe 1
        m.successCount shouldBe 1
        logger.entries.any { it.contains("\"trace_id\":\"trace\"") } shouldBe true
    }
})

private class CapturingLogger : Logger by org.slf4j.helpers.NOPLogger.NOP_LOGGER {
    val entries = mutableListOf<String>()

    override fun info(msg: String?) {
        if (msg != null) entries += msg
    }

    override fun error(msg: String?) {
        if (msg != null) entries += msg
    }
}
