package com.troxal.refactoringapplicator.Info;

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
    private List<DeclarationInfo<ConstructorDeclaration>> constructors;
    @Getter
    private List<DeclarationInfo<MethodDeclaration>> methods;

    public ClassInfo(ClassOrInterfaceDeclaration classOrInterface,
                     List<DeclarationInfo<ConstructorDeclaration>> constructors,
                     List<DeclarationInfo<MethodDeclaration>> methods){
        this.classOrInterface = classOrInterface;
        this.constructors = constructors;
        this.methods = methods;
    }
}
