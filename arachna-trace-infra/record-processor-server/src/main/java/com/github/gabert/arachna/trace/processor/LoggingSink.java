package com.github.gabert.arachna.trace.processor;

import com.github.gabert.arachna.trace.codec.AgentRun;
import com.github.gabert.arachna.trace.recorder.record.TraceRecord;
import com.github.gabert.arachna.trace.renderer.RecordHashEnricher;
import com.github.gabert.arachna.trace.renderer.RecordRenderer;

import java.util.List;

public class LoggingSink implements RecordSink {

    @Override
    public void accept(List<TraceRecord> records, AgentRun agentRun) {
        System.out.println("[agent_run] " + agentRun.agentRunId()
                + " host=" + agentRun.hostname()
                + " env=" + agentRun.env());
        RecordRenderer.Result rendered = RecordHashEnricher.enrich(RecordRenderer.render(records));
        for (String line : rendered.lines()) {
            System.out.println(line);
        }
    }

    @Override
    public void close() {}
}
