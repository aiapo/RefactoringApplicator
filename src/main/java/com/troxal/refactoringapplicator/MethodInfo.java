package com.troxal.refactoringapplicator;

import com.github.javaparser.ast.body.MethodDeclaration;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MethodInfo {
    @Getter
    private MethodDeclaration method;
    @Getter
    private Map<String,String> fields = new HashMap<>();
    @Getter
    private List<String> methods = new ArrayList<>();

    public MethodInfo(MethodDeclaration method){
        this.method = method;
    }

    public void addField(String field, String source){
        if(!fields.containsKey(field))
            fields.put(field,source);
    }

    public void addMethod(String method){
        if(!methods.contains(method))
            methods.add(method);
    }
}
