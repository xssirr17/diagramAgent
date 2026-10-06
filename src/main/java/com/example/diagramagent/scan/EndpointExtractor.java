package com.example.diagramagent.scan;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class EndpointExtractor {

    public List<EndpointInfo> extractEndpoints(ClassOrInterfaceDeclaration classDecl) {
        List<EndpointInfo> endpoints = new ArrayList<>();
        String className = classDecl.getNameAsString();

        // Check if class has @RestController or @Controller or @RequestMapping
        boolean isController = classDecl.getAnnotations().stream().anyMatch(a -> {
            String name = a.getNameAsString();
            return name.equals("RestController") || name.equals("Controller") ||
                name.endsWith(".RestController") || name.endsWith(".Controller") ||
                name.equals("RequestMapping") || name.endsWith(".RequestMapping");
        });

        if (!isController) {
            return endpoints;
        }

        List<String> classPaths = extractPathsFromAnnotations(classDecl.getAnnotations());
        if (classPaths.isEmpty()) {
            classPaths = List.of("");
        }

        for (MethodDeclaration method : classDecl.getMethods()) {
            for (AnnotationExpr annotation : method.getAnnotations()) {
                String annotationName = annotation.getNameAsString();
                String simpleName = annotationName.contains(".") ?
                    annotationName.substring(annotationName.lastIndexOf('.') + 1) : annotationName;

                String httpMethod = switch (simpleName) {
                    case "GetMapping" -> "GET";
                    case "PostMapping" -> "POST";
                    case "PutMapping" -> "PUT";
                    case "DeleteMapping" -> "DELETE";
                    case "PatchMapping" -> "PATCH";
                    case "RequestMapping" -> extractHttpMethodFromRequestMapping(annotation);
                    default -> null;
                };

                if (httpMethod != null) {
                    List<String> methodPaths = extractPathsFromAnnotation(annotation);
                    if (methodPaths.isEmpty()) {
                        methodPaths = List.of("");
                    }

                    for (String classPath : classPaths) {
                        for (String methodPath : methodPaths) {
                            String combinedPath = combinePaths(classPath, methodPath);
                            endpoints.add(new EndpointInfo(
                                httpMethod,
                                combinedPath,
                                className,
                                method.getNameAsString()
                            ));
                        }
                    }
                }
            }
        }

        return endpoints;
    }

    private List<String> extractPathsFromAnnotations(List<AnnotationExpr> annotations) {
        for (AnnotationExpr a : annotations) {
            String name = a.getNameAsString();
            if (name.equals("RequestMapping") || name.endsWith(".RequestMapping")) {
                List<String> paths = extractPathsFromAnnotation(a);
                if (!paths.isEmpty()) {
                    return paths;
                }
            }
        }
        return List.of();
    }

    private List<String> extractPathsFromAnnotation(AnnotationExpr annotation) {
        List<String> paths = new ArrayList<>();

        if (annotation instanceof SingleMemberAnnotationExpr single) {
            extractPathFromExpression(single.getMemberValue(), paths);
        } else if (annotation instanceof NormalAnnotationExpr normal) {
            for (MemberValuePair pair : normal.getPairs()) {
                String pairName = pair.getNameAsString();
                if (pairName.equals("value") || pairName.equals("path")) {
                    extractPathFromExpression(pair.getValue(), paths);
                }
            }
        }

        return paths;
    }

    private void extractPathFromExpression(Expression expr, List<String> paths) {
        if (expr instanceof StringLiteralExpr str) {
            paths.add(str.getValue());
        } else if (expr instanceof ArrayInitializerExpr array) {
            for (Expression item : array.getValues()) {
                if (item instanceof StringLiteralExpr str) {
                    paths.add(str.getValue());
                }
            }
        }
    }

    private String extractHttpMethodFromRequestMapping(AnnotationExpr annotation) {
        if (annotation instanceof NormalAnnotationExpr normal) {
            for (MemberValuePair pair : normal.getPairs()) {
                if (pair.getNameAsString().equals("method")) {
                    String methodText = pair.getValue().toString();
                    if (methodText.contains("GET")) return "GET";
                    if (methodText.contains("POST")) return "POST";
                    if (methodText.contains("PUT")) return "PUT";
                    if (methodText.contains("DELETE")) return "DELETE";
                    if (methodText.contains("PATCH")) return "PATCH";
                }
            }
        }
        return "GET"; // Default for RequestMapping if unspecified
    }

    private String combinePaths(String classPath, String methodPath) {
        String cp = classPath != null ? classPath.trim() : "";
        String mp = methodPath != null ? methodPath.trim() : "";

        if (!cp.startsWith("/") && !cp.isEmpty()) cp = "/" + cp;
        if (!mp.startsWith("/") && !mp.isEmpty()) mp = "/" + mp;

        if (cp.endsWith("/") && mp.startsWith("/")) {
            cp = cp.substring(0, cp.length() - 1);
        }

        String combined = cp + mp;
        if (combined.isEmpty()) return "/";
        if (!combined.startsWith("/")) combined = "/" + combined;
        return combined.replaceAll("//+", "/");
    }
}
