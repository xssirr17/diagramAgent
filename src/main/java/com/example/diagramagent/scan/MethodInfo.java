package com.example.diagramagent.scan;

import java.util.List;

public record MethodInfo(
    String name,
    String signature,
    String returnType,
    List<String> annotations,
    List<CallInfo> outgoingCalls,
    List<ControlFlowInfo> controlFlows,
    String methodSource,
    boolean isPrivate
) {}
