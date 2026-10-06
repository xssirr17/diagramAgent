package com.example.diagramagent.scan;

import java.util.List;

public record ControlFlowInfo(
    ControlFlowKind kind,
    String conditionOrDetail,
    List<CallInfo> calls
) {}
