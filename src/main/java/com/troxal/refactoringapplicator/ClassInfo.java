package com.troxal.refactoringapplicator;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

public class ClassInfo {
    @Getter
    private ClassOrInterfaceDeclaration classOrInterface;
    @Getter
    private List<DeclarationInfo<ConstructorDeclaration>> constructors = new ArrayList<>();
    @Getter
    private List<DeclarationInfo<MethodDeclaration>> methods = new ArrayList<>();

    public ClassInfo(ClassOrInterfaceDeclaration classOrInterface){
        this.classOrInterface = classOrInterface;
    }

    public void addConstructor(DeclarationInfo<ConstructorDeclaration> constructor){
        if(!constructors.contains(constructor))
            constructors.add(constructor);
    }

    public void addMethod(DeclarationInfo<MethodDeclaration> method){
        if(!methods.contains(method))
            methods.add(method);
    }
}
