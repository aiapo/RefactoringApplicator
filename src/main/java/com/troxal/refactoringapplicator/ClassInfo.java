package com.troxal.refactoringapplicator;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

public class ClassInfo {
    @Getter
    private ClassOrInterfaceDeclaration classOrInterface;
    @Getter
    private List<ConstructorInfo> constructors = new ArrayList<>();
    @Getter
    private List<MethodInfo> methods = new ArrayList<>();

    public ClassInfo(ClassOrInterfaceDeclaration classOrInterface){
        this.classOrInterface = classOrInterface;
    }

    public void addConstructor(ConstructorInfo constructor){
        if(!constructors.contains(constructor))
            constructors.add(constructor);
    }

    public void addMethod(MethodInfo method){
        if(!methods.contains(method))
            methods.add(method);
    }
}
