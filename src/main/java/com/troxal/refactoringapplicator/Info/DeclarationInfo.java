package com.troxal.refactoringapplicator.Info;

import com.github.javaparser.ast.body.CallableDeclaration;
import lombok.Getter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DeclarationInfo<T extends CallableDeclaration<?>> {
    @Getter
    private CallableDeclaration<T> declaration;
    @Getter
    private Map<String,String> fields = new HashMap<>();
    @Getter
    private List<String> methods = new ArrayList<>();

    public DeclarationInfo(CallableDeclaration<T> declaration){
        this.declaration = declaration;
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
