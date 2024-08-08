package com.troxal.refactoringapplicator;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.github.javaparser.ast.Modifier.Keyword.PUBLIC;
import static com.github.javaparser.ast.expr.UnaryExpr.Operator.*;

public class Refactor {
    Map<String,String> context = new HashMap<>();
    public Refactor(){
    }

    public void findFieldAndRefactorContext(ClassOrInterfaceDeclaration cOne, ClassOrInterfaceDeclaration cTwo,
                                            String field) {
        findContextRefactoring(findCalledFieldsAndMethods(cOne),field);
        for (Map.Entry<String, String> refactorThis : context.entrySet()) {
            if(refactorThis.getValue().equals("field")){
                if(cOne.getFieldByName(refactorThis.getKey()).isPresent()){
                    cTwo.addMember(cOne.getFieldByName(refactorThis.getKey()).get());
                    cOne.remove(cOne.getFieldByName(refactorThis.getKey()).get());
                }
            }else if (refactorThis.getValue().equals("method")){
                 if(!cOne.getMethodsByName(refactorThis.getKey()).isEmpty()){
                     cOne.getMethodsByName(refactorThis.getKey()).forEach(m -> {
                         cTwo.addMember(m);
                         cOne.remove(m);
                     });
                 }
            }
        }
    }

    public void findFieldAndRefactorContextless(String field,
                                                ClassOrInterfaceDeclaration cTemp, ClassOrInterfaceDeclaration cOne,
                                                ClassOrInterfaceDeclaration cTwo, FieldDeclaration cField,
                                                List<CompilationUnit> allCus) {
        String classOne = cOne.getNameAsString();
        String classTwo = cTwo.getNameAsString();

        // Add the field to class two (and make sure we clone, so it doesn't update later)
        cTwo.addMember(cField.clone());

        // Keep track of if getters/setters are needed (if the field isn't public)
        Boolean fieldModification = false;

        // Assuming the field isn't public
        if (!cField.isPublic()) {
            // Create the getter and add it to class two if it doesn't already exist
            String getterMethodName = "get" + field.substring(0, 1).toUpperCase() + field.substring(1).toLowerCase();
            if (cTwo.getMethodsByName(getterMethodName).isEmpty()) {
                cTwo.addMethod(getterMethodName, PUBLIC)
                        .setBody(new BlockStmt().addStatement(new ReturnStmt(field)))
                        .setType(cField.getElementType());
            }

            // Create the setter and add it to class two if it doesn't already exist
            String setterMethodName = "set" + field.substring(0, 1).toUpperCase() + field.substring(1).toLowerCase();
            if (cTwo.getMethodsByName(setterMethodName).isEmpty()) {
                cTwo.addMethod(setterMethodName, PUBLIC)
                        .setBody(new BlockStmt().addStatement("this." + field + "=" + field + ";"))
                        .addParameter(cField.getElementType(), field);
            }

            // Set that we need getters and setters
            fieldModification = true;
        }
        // Update the field in the original class
        cTemp.addField(classTwo,"i"+classTwo).getVariable(0).setInitializer("new "+ classTwo+"()");
        cField.replace(cTemp.getFieldByName("i"+classTwo).get());

        Boolean finalFieldModification = fieldModification;

        cOne.findAll(MethodDeclaration.class).forEach(methodDeclaration -> {

            // Find all instances of declaring a field (i.e. `String a = b;`)
            methodDeclaration.findAll(VariableDeclarationExpr.class).forEach(variableDeclaration ->
                    variableDeclaration.findAll(VariableDeclarator.class).forEach(fieldAccessExpr -> {
                        // We only care about fields that have initializers because that's the only
                        // way a refactored field can be assigned (i.e. `String a = refactoredField;`)
                        if (fieldAccessExpr.getInitializer().isPresent()) {
                            // Only find the refactored field
                            if (fieldAccessExpr.getInitializer().get().toString().equals(field)) {
                                // If field isn't public
                                if (finalFieldModification) {
                                    // Create the getter call
                                    MethodCallExpr mc = new MethodCallExpr("i" + classTwo + ".get" +
                                            field.substring(0, 1).toUpperCase() +
                                            field.substring(1).toLowerCase()
                                    );

                                    // Reassign the initializer to the new call
                                    fieldAccessExpr.setInitializer(mc);
                                } else {
                                    // Reassign the initializer to just direct access for public field
                                    fieldAccessExpr.setInitializer(classTwo + "." + field);
                                }
                            }
                        }
                    }));

            // Find all instances of assigning a field (i.e. `refactoredField = 0`)
            methodDeclaration.findAll(AssignExpr.class).forEach(assignExpr -> {
                assignExpr.findAll(FieldAccessExpr.class).forEach(fieldAccessExpr -> {
                    // If the field's value is the refactored field (i.e. `b = refactoredField;`)
                    if (assignExpr.getValue().toString().equals(field)) {
                        replaceField(classTwo, assignExpr, finalFieldModification, field);
                        // If the field's name is the refactored field (i.e. `refactoredField = b;`)
                    } else if (fieldAccessExpr.getNameAsString().equals(field)) {
                        if (!replaceField(finalFieldModification, classTwo, assignExpr, field)) {
                            // Reassign the name for direct access for public field
                            fieldAccessExpr.setName(classTwo + "." + field);
                        }
                    }
                });
                assignExpr.findAll(NameExpr.class).forEach(nameExpr -> {
                    // If the field's value is the refactored field (i.e. `b = refactoredField;`)
                    if (assignExpr.getValue().toString().equals(field)) {
                        // If field isn't public
                        replaceField(classTwo, assignExpr, finalFieldModification, field);
                        // If the field's name is the refactored field (i.e. `refactoredField = b;`)
                    } else if (nameExpr.getNameAsString().equals(field)) {
                        if (!replaceField(finalFieldModification, classTwo, assignExpr, field)) {
                            // Reassign the name for direct access for public field
                            nameExpr.setName(classTwo + "." + field);
                        }
                    }
                });
            });

            // Find all instances of using a field within a method (i.e. `a(refactoredField);`)
            methodDeclaration.findAll(MethodCallExpr.class).forEach(methodCallExpr -> methodCallExpr.getArguments().forEach(arg -> {
                arg.findAll(FieldAccessExpr.class).forEach(fieldAccessExpr -> {
                    // Only get refactored field
                    if (fieldAccessExpr.getNameAsString().equals(field)) {
                        replaceField(classTwo, fieldAccessExpr, finalFieldModification, field);
                    }
                });
                arg.findAll(NameExpr.class).forEach(nameExpr -> {
                    // Only get refactored field
                    if (nameExpr.getNameAsString().equals(field)) {
                        // If field isn't public
                        if (!replaceField(finalFieldModification, classTwo, nameExpr, field)) {
                            // Reassign the name for direct access for public field
                            nameExpr.setName(classTwo + "." + field);
                        }
                    }
                });
            }));

            // Find all instances of returning the field (i.e. `return refactoredField;`)
            methodDeclaration.findAll(ReturnStmt.class).forEach(returnStatement -> {
                returnStatement.findAll(FieldAccessExpr.class).forEach(fieldAccessExpr -> {
                    // Only get refactored field
                    if (fieldAccessExpr.getNameAsString().equals(field)) {
                        // If field isn't public
                        if (finalFieldModification) {
                            MethodCallExpr mc;

                            // Check to see if unary expression is used (i.e.
                            // `return refactoredField++;`)
                            if (returnStatement.toString().contains("++") || returnStatement.toString().contains("--")) {
                                replaceField(classTwo, returnStatement, cTwo, field);
                            } else {
                                // Create the getter call
                                mc = new MethodCallExpr("i" + classTwo + ".get" +
                                        field.substring(0, 1).toUpperCase() +
                                        field.substring(1).toLowerCase()
                                );

                                // Replace the access with the method call
                                fieldAccessExpr.replace(mc);
                            }
                        } else {
                            // Reassign the name for direct access for public field
                            fieldAccessExpr.setName(classTwo + "." + field);
                        }
                    }
                });
                returnStatement.findAll(NameExpr.class).forEach(nameExpr -> {
                    if (nameExpr.getNameAsString().equals(field)) {
                        // If field isn't public
                        if (finalFieldModification) {
                            MethodCallExpr mc;

                            // Check to see if unary expression is used (i.e.
                            // `return refactoredField++;`)
                            if (returnStatement.toString().contains("++") || returnStatement.toString().contains("--")) {
                                replaceField(classTwo, returnStatement, cTwo, field);
                            } else {
                                // Create the getter call
                                mc = new MethodCallExpr("i" + classTwo + ".get" +
                                        field.substring(0, 1).toUpperCase() +
                                        field.substring(1).toLowerCase()
                                );

                                // Replace the access with the method call
                                nameExpr.replace(mc);
                            }
                        } else {
                            // Reassign the name for direct access for public field
                            nameExpr.setName(classTwo + "." + field);
                        }
                    }
                });
            });

            // Find all instances of unary expressions (i.e. `refactoredField++;)
            methodDeclaration.findAll(UnaryExpr.class).forEach(unaryStatement -> {
                unaryStatement.findAll(FieldAccessExpr.class).forEach(fieldAccessExpr -> {
                    // Only get refactored field
                    if (fieldAccessExpr.getNameAsString().equals(field)) {
                        // If field isn't public
                        if (!replaceField(finalFieldModification, classTwo, unaryStatement, cTwo,
                                field)) {
                            // Reassign the name for direct access for public field
                            fieldAccessExpr.setName(classTwo + "." + field);
                        }
                    }
                });
                unaryStatement.findAll(NameExpr.class).forEach(nameExpr -> {
                    if (nameExpr.getNameAsString().equals(field)) {
                        // Only get refactored field
                        if (!replaceField(finalFieldModification, classTwo, unaryStatement, cTwo,
                                field)) {
                            // Reassign the name for direct access for public field
                            nameExpr.setName(classTwo + "." + field);
                        }
                    }
                });
            });
        });

        // Remove the field in the original class
        cOne.remove(cField);

        // Add the import (find's the CU and adds it)
        for (CompilationUnit cu : allCus) {
            // If the class is the correct class (class one)
            if (cu.getClassByName(classOne).isPresent()) {
                cTwo.getFullyQualifiedName().ifPresent(name -> {
                    cu.addImport(new ImportDeclaration(name, false, false));
                });
                break;
            }
        }
    }

    private int methodAlreadyAdded(List<MethodInfo> methods, MethodDeclaration method){
        int count = 0;
        for (MethodInfo m : methods){
            if(m.getMethod().equals(method))
                return count;
            count++;
        }
        return -1;
    }

    private List<MethodInfo> findCalledFieldsAndMethods(ClassOrInterfaceDeclaration cOne){
        List<MethodInfo> methods = new ArrayList<>();

        // For each method in class one
        cOne.findAll(MethodDeclaration.class).forEach(methodDeclaration -> {
            // Find all instances of declaring a field (i.e. `String a = b;`)
            methodDeclaration.findAll(VariableDeclarationExpr.class).forEach(variableDeclaration -> {
                MethodInfo m = new MethodInfo(methodDeclaration);
                variableDeclaration.findAll(VariableDeclarator.class).forEach(fieldAccessExpr -> {
                    if (fieldAccessExpr.getInitializer().isPresent()) {
                        int methodIndex = methodAlreadyAdded(methods,methodDeclaration);
                        if(methodIndex!=-1)
                            methods.get(methodIndex).addField(fieldAccessExpr.getNameAsString()+"="+
                                            fieldAccessExpr.getInitializer().get(),
                                    "variableDeclarationExpr");
                        else
                            m.addField(fieldAccessExpr.getNameAsString()+"="+
                                    fieldAccessExpr.getInitializer().get(),"variableDeclarationExpr");
                    }
                });
                if(!m.getFields().isEmpty())
                    methods.add(m);
            });

            // Find all instances of assigning a field (i.e. `refactoredField = 0`)
            methodDeclaration.findAll(AssignExpr.class).forEach(assignExpr -> {
                MethodInfo m = new MethodInfo(methodDeclaration);
                assignExpr.findAll(FieldAccessExpr.class).forEach(fieldAccessExpr -> {
                    int methodIndex = methodAlreadyAdded(methods,methodDeclaration);
                    if(methodIndex!=-1){
                        if (assignExpr.getValue().toString().contains("\"")) {
                            methods.get(methodIndex).addField(fieldAccessExpr.getName().toString(),"assignExpr-fAE");
                        } else {
                            if(assignExpr.getValue().toString().split(" ").length>1)
                                methods.get(methodIndex).addField(assignExpr.getValue().toString().split(" ")[1],"assignExpr-FAEaE");
                            else
                                methods.get(methodIndex).addField(assignExpr.getValue().toString(),"assignExpr-FAEaE");
                        }
                    }else{
                        if (assignExpr.getValue().toString().contains("\"")) {
                            m.addField(fieldAccessExpr.getName().toString(),"assignExpr-fAE");
                        } else {
                            if(assignExpr.getValue().toString().split(" ").length>1)
                                m.addField(assignExpr.getValue().toString().split(" ")[1],
                                        "assignExpr-FAEaE");
                            else
                                m.addField(assignExpr.getValue().toString(),"assignExpr-FAEaE");
                        }
                    }
                });
                assignExpr.findAll(NameExpr.class).forEach(nameExpr -> {
                    int methodIndex = methodAlreadyAdded(methods,methodDeclaration);
                    if(methodIndex!=-1){
                        if (assignExpr.getValue().toString().contains("\"")) {
                            methods.get(methodIndex).addField(nameExpr.getNameAsString(),"assignExpr-nE");
                        } else {
                            if(assignExpr.getValue().toString().split(" ").length>1)
                                methods.get(methodIndex).addField(assignExpr.getValue().toString().split(" ")[1],"assignExpr-NEaE");
                            else
                                methods.get(methodIndex).addField(assignExpr.getValue().toString(),"assignExpr-NEaE");
                        }
                    }else{
                        if (assignExpr.getValue().toString().contains("\"")) {
                            m.addField(nameExpr.getNameAsString(),"assignExpr-nE");
                        } else {
                            if(assignExpr.getValue().toString().split(" ").length>1)
                                m.addField(assignExpr.getValue().toString().split(" ")[1],"assignExpr-NEaE");
                            else
                                m.addField(assignExpr.getValue().toString(),"assignExpr-NEaE");
                        }
                    }
                });
                if(!m.getFields().isEmpty())
                    methods.add(m);
            });

            // Find all instances of using a field within a method (i.e. `a(refactoredField);`)
            methodDeclaration.findAll(MethodCallExpr.class).forEach(methodCallExpr -> {
                MethodInfo m = new MethodInfo(methodDeclaration);
                methodCallExpr.getArguments().forEach(arg -> {
                    arg.findAll(FieldAccessExpr.class).forEach(fieldAccessExpr -> {
                        int methodIndex = methodAlreadyAdded(methods,methodDeclaration);
                        if(methodIndex!=-1)
                            methods.get(methodIndex).addField(fieldAccessExpr.getNameAsString(),"methodCallExpr");
                        else
                            m.addField(fieldAccessExpr.getNameAsString(),"methodCallExpr");
                    });
                    arg.findAll(NameExpr.class).forEach(nameExpr -> {
                        int methodIndex = methodAlreadyAdded(methods,methodDeclaration);
                        if(methodIndex!=-1)
                            methods.get(methodIndex).addField(nameExpr.getNameAsString(),"methodCallExpr");
                        else
                            m.addField(nameExpr.getNameAsString(),"methodCallExpr");
                    });

                });
                if(methodCallExpr.getScope().isEmpty()){
                    int methodIndex = methodAlreadyAdded(methods,methodDeclaration);
                    if(methodIndex!=-1)
                        methods.get(methodIndex).addMethod(methodCallExpr.getNameAsString());
                    else
                        m.addMethod(methodCallExpr.getNameAsString());
                }
                if(!m.getFields().isEmpty()||!m.getMethods().isEmpty())
                    methods.add(m);
            });

            // Find all instances of returning the field (i.e. `return refactoredField;`)
            methodDeclaration.findAll(ReturnStmt.class).forEach(returnStatement -> {
                MethodInfo m = new MethodInfo(methodDeclaration);
                returnStatement.findAll(FieldAccessExpr.class).forEach(fieldAccessExpr -> {
                    int methodIndex = methodAlreadyAdded(methods,methodDeclaration);
                    if(methodIndex!=-1)
                        methods.get(methodIndex).addField(fieldAccessExpr.getNameAsString(),"returnStatement");
                    else
                        m.addField(fieldAccessExpr.getNameAsString(),"returnStatement");
                });
                returnStatement.findAll(NameExpr.class).forEach(nameExpr -> {
                    int methodIndex = methodAlreadyAdded(methods,methodDeclaration);
                    if(methodIndex!=-1)
                        methods.get(methodIndex).addField(nameExpr.getNameAsString(),"returnStatement");
                    else
                        m.addField(nameExpr.getNameAsString(),"returnStatement");
                });
                if(!m.getFields().isEmpty())
                    methods.add(m);
            });

            // Find all instances of unary expressions (i.e. `refactoredField++;)
            methodDeclaration.findAll(UnaryExpr.class).forEach(unaryStatement -> {
                MethodInfo m = new MethodInfo(methodDeclaration);
                unaryStatement.findAll(FieldAccessExpr.class).forEach(fieldAccessExpr -> {
                    m.addField(fieldAccessExpr.getNameAsString(),"UnaryExpr");
                });
                unaryStatement.findAll(NameExpr.class).forEach(nameExpr -> {
                    m.addField(nameExpr.getNameAsString(),"UnaryExpr");
                });
                if(!m.getFields().isEmpty())
                    methods.add(m);
            });
        });

        return methods;
    }

    private void findContextRefactoring(List<MethodInfo> methods, String call){
        for (MethodInfo method : methods){
            if(method.getFields().containsKey(call)||method.getMethods().contains(call)){
                for (Map.Entry<String, String> f : method.getFields().entrySet()) {
                    if(!context.containsKey(f.getKey())){
                        context.put(f.getKey(),"field");
                        findContextRefactoring(methods,f.getKey());
                    }
                }
                for (String m : method.getMethods()) {
                    if(!context.containsKey(m)){
                        context.put(m,"method");
                        findContextRefactoring(methods,m);
                    }
                }
                if(!context.containsKey(method.getMethod().getNameAsString())){
                    context.put(method.getMethod().getNameAsString(),"method");
                    findContextRefactoring(methods,method.getMethod().getNameAsString());
                }
            }

            if(method.getFields().containsValue("variableDeclarationExpr")){
                for (Map.Entry<String, String> f : method.getFields().entrySet()) {
                    if(f.getValue().equals("variableDeclarationExpr")){
                        if(f.getKey().split("=")[1].equals(call)){
                            context.put(f.getKey().split("=")[0],"field");
                        }
                    }
                }
            }
        }
    }

    // For Unary case
    private Boolean replaceField(Boolean finalFieldModification, String classTwo, UnaryExpr unaryStatement,
                                 ClassOrInterfaceDeclaration cTwo, String field) {
        // If field isn't public
        if (finalFieldModification) {
            // Check if the unary method exists
            if (cTwo.getMethodsByName(unaryStatement.getOperator().toString().toLowerCase() +
                    field.substring(0, 1).toUpperCase() +
                    field.substring(1).toLowerCase()).isEmpty()) {
                // If it doesn't add it
                cTwo.addMethod(unaryStatement.getOperator().toString().toLowerCase() +
                                field.substring(0, 1).toUpperCase() +
                                field.substring(1).toLowerCase(), PUBLIC)
                        .setBody(new BlockStmt().addStatement(unaryStatement.clone()));
            }

            // Create a new method call to the new unary method
            MethodCallExpr mc = new MethodCallExpr("i" + classTwo + "." +
                    unaryStatement.getOperator().toString().toLowerCase() +
                    field.substring(0, 1).toUpperCase() +
                    field.substring(1).toLowerCase());

            // Replace the return statement to return the new method
            unaryStatement.replace(mc);
            return true;
        }
        return false;
    }

    // For return case with UE
    private void replaceField(String classTwo, ReturnStmt returnStatement, ClassOrInterfaceDeclaration cTwo, String field) {
        // Get the UnaryExpr from the statement
        UnaryExpr newUE = solveUnaryExpression(returnStatement.toString());
        if (newUE != null) {
            MethodCallExpr mc;
            // Check if the unary method exists
            if (cTwo.getMethodsByName(newUE.getOperator().toString().toLowerCase() +
                    field.substring(0, 1).toUpperCase() +
                    field.substring(1).toLowerCase()).isEmpty()) {
                // If it doesn't add it
                cTwo.addMethod(newUE.getOperator().toString().toLowerCase() +
                                field.substring(0, 1).toUpperCase() +
                                field.substring(1).toLowerCase(), PUBLIC)
                        .setBody(new BlockStmt().addStatement(newUE));
            }

            // Create a new method call to the new unary method
            mc = new MethodCallExpr("i" + classTwo + "." +
                    newUE.getOperator().toString().toLowerCase() +
                    field.substring(0, 1).toUpperCase() +
                    field.substring(1).toLowerCase());

            // Replace the return statement to return the new method
            returnStatement.replace(new ReturnStmt(mc));
        }
    }

    // For Access case with getter
    private Boolean replaceField(Boolean finalFieldModification, String classTwo, NameExpr nameExpr, String field) {
        // If field isn't public
        if (finalFieldModification) {
            // Create the getter call
            MethodCallExpr mc = new MethodCallExpr("i" + classTwo + ".get" +
                    field.substring(0, 1).toUpperCase() +
                    field.substring(1).toLowerCase()
            );

            // Replace the access with the method call
            nameExpr.replace(mc);
            return true;
        }
        return false;
    }

    // For Access case with getter
    private void replaceField(String classTwo, FieldAccessExpr fieldAccessExpr, Boolean finalFieldModification, String field) {
        // If field isn't public
        if (finalFieldModification) {
            // Create the getter call
            MethodCallExpr mc = new MethodCallExpr("i" + classTwo + ".get" +
                    field.substring(0, 1).toUpperCase() +
                    field.substring(1).toLowerCase()
            );

            // Replace the access with the method call
            fieldAccessExpr.replace(mc);
        } else {
            // Reassign the name for direct access for public field
            fieldAccessExpr.setName(classTwo + "." + field);
        }
    }

    // For assignment case replace with setter
    private Boolean replaceField(Boolean finalFieldModification, String classTwo, AssignExpr assignExpr, String field) {
        // If field isn't public
        if (finalFieldModification) {
            // Create the setter call and add argument of the value (i.e. `b`)
            MethodCallExpr mc = new MethodCallExpr("i" + classTwo + ".set" +
                    field.substring(0, 1).toUpperCase() +
                    field.substring(1).toLowerCase()
            ).addArgument(assignExpr.getValue());

            // Replace the assignment with the method call
            assignExpr.replace(mc);
            return true;
        }
        return false;
    }

    // For assignment case replace with getter
    private void replaceField(String classTwo, AssignExpr assignExpr, Boolean finalFieldModification, String field) {
        // If field isn't public
        if (finalFieldModification) {
            // Create the getter call
            MethodCallExpr mc = new MethodCallExpr("i" + classTwo + ".get" +
                    field.substring(0, 1).toUpperCase() +
                    field.substring(1).toLowerCase()
            );

            // Replace the assignment with the method call
            assignExpr.replace(mc);
        } else {
            // Reassign the value for direct access for public field
            assignExpr.setValue(new FieldAccessExpr(null, classTwo + "." + field));
        }
    }

    private UnaryExpr solveUnaryExpression(String expression) {
        expression = expression.replace("return ", "").replace(";", "");
        UnaryExpr.Operator operator;
        String exp;

        if (expression.startsWith("++")) {
            operator = PREFIX_INCREMENT;
            exp = expression.replace("++", "");
        } else if (expression.startsWith("--")) {
            operator = PREFIX_DECREMENT;
            exp = expression.replace("--", "");
        } else if (expression.endsWith("++")) {
            operator = POSTFIX_INCREMENT;
            exp = expression.replace("++", "");
        } else if (expression.endsWith("--")) {
            operator = POSTFIX_DECREMENT;
            exp = expression.replace("--", "");
        } else {
            return null;
        }

        return new UnaryExpr().setExpression(exp).setOperator(operator);
    }
}
