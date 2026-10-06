package com.example.diagramagent.scan;

import java.util.List;

public record EnumInfo(
    String name,
    String packageName,
    List<String> constants
) {}
