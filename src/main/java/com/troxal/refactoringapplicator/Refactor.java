package com.troxal.refactoringapplicator;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.metamodel.NodeMetaModel;
import com.troxal.refactoringapplicator.Info.ClassInfo;
import com.troxal.refactoringapplicator.Info.DeclarationInfo;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static com.github.javaparser.ast.Modifier.Keyword.PUBLIC;
import static com.github.javaparser.ast.expr.UnaryExpr.Operator.*;

public class Refactor {
    Map<String,String> context = new HashMap<>();
    ClassOrInterfaceDeclaration cOne;
    ClassOrInterfaceDeclaration cTwo;
    String call;
    List<ClassOrInterfaceDeclaration> res;
    List<CompilationUnit> allCus;

    public Refactor(ClassOrInterfaceDeclaration cOne, ClassOrInterfaceDeclaration cTwo,
                    String call, List<ClassOrInterfaceDeclaration> res,List<CompilationUnit> allCus){
        this.cOne=cOne;
        this.cTwo=cTwo;
        this.call = call;
        this.res = res;
        this.allCus = allCus;
    }

    public void findFieldAndRefactorContext() {
        findContextRefactoring(findCalledFieldsAndMethods(cOne),call);
        for (Map.Entry<String, String> refactorThis : context.entrySet()) {
            String type = refactorThis.getValue();
            String toRefactor = refactorThis.getKey();

            if(type.equals("field")){
                if(cOne.getFieldByName(toRefactor).isPresent()){
                    cTwo.addMember(cOne.getFieldByName(toRefactor).get());
                    cOne.remove(cOne.getFieldByName(toRefactor).get());
                }
            }else if (type.equals("method")){
                 if(!cOne.getMethodsByName(toRefactor).isEmpty()){
                     cOne.getMethodsByName(toRefactor).forEach(m -> {
                         cTwo.addMember(m);
                         cOne.remove(m);
                     });
                 }
            }else if(type.equals("constructor")){
                Optional<ConstructorDeclaration> constructorOneOpt = cOne.getConstructors().stream().filter(
                        c -> c.getParameters().toString().equals(toRefactor)
                ).findFirst();

                if(constructorOneOpt.isPresent()){
                    ConstructorDeclaration constructorOne = constructorOneOpt.get();
                    ConstructorDeclaration constructorTwo = new ConstructorDeclaration(cTwo.getNameAsString());

                    BlockStmt bS = new BlockStmt();
                    NodeList<Parameter> parameters = new NodeList<>();
                    constructorOne.findAll(Expression.class).forEach(expression -> {
                        if(expression.getParentNode().isPresent()){
                            for(Map.Entry<String, String> con : context.entrySet()){
                                if(con.getValue().equals("field") || con.getValue().equals("method")){
                                    if(expression.toString().equals(con.getKey())){
                                        for(Parameter para : constructorOne.getParameters()){
                                            if(expression.toString()
                                                    .equals(para.getName().toString()) &&!parameters.contains(para)){
                                                parameters.add(para);
                                            }
                                        }
                                        constructorOne.getParameters().removeIf(parameters::contains);
                                        bS.addStatement(expression.getParentNode().get()+";");
                                        expression.getParentNode().get().removeForced();
                                    }
                                }
                            }
                        }
                    });

                    constructorTwo.setBody(bS);
                    constructorTwo.setPublic(true);
                    constructorTwo.setParameters(parameters);
                    cTwo.addMember(constructorTwo);
                }
            }
        }

        findAndUpdateInstancesOfClassOne(cOne.getNameAsString(),cTwo.getNameAsString(),res);
    }

    public void findFieldAndRefactorContextless(FieldDeclaration cField) {
        String classOneName = cOne.getNameAsString();
        String classTwoName = cTwo.getNameAsString();

        // Temp class so we can do manipulations
        ClassOrInterfaceDeclaration cTemp = new ClassOrInterfaceDeclaration();

        // Add the field to class two (and make sure we clone, so it doesn't update later)
        cTwo.addMember(cField.clone());

        // Keep track of if getters/setters are needed (if the field isn't public)
        boolean isGetterSetterNeeded = false;

        // Assuming the field isn't public
        if (!cField.isPublic()) {
            // Create the getter and add it to class two if it doesn't already exist
            String getterMethodName = "get" + call.substring(0, 1).toUpperCase() + call.substring(1).toLowerCase();
            if (cTwo.getMethodsByName(getterMethodName).isEmpty()) {
                cTwo.addMethod(getterMethodName, PUBLIC)
                        .setBody(new BlockStmt().addStatement(new ReturnStmt(call)))
                        .setType(cField.getElementType());
            }

            // Create the setter and add it to class two if it doesn't already exist
            String setterMethodName = "set" + call.substring(0, 1).toUpperCase() + call.substring(1).toLowerCase();
            if (cTwo.getMethodsByName(setterMethodName).isEmpty()) {
                cTwo.addMethod(setterMethodName, PUBLIC)
                        .setBody(new BlockStmt().addStatement("this." + call + "=" + call + ";"))
                        .addParameter(cField.getElementType(), call);
            }

            // Set that we need getters and setters
            isGetterSetterNeeded = true;
        }

        if (cOne.getFieldByName(classTwoName.toLowerCase()).isEmpty()) {
            // Update the field in the original class
            cTemp.addField(classTwoName,classTwoName.toLowerCase()).getVariable(0).setInitializer("new "+ classTwoName+"()");
            cTemp.getFieldByName(classTwoName.toLowerCase()).ifPresent(cField::replace);
        }

        Boolean finalIsGetterSetterNeeded = isGetterSetterNeeded;

        cOne.findAll(Expression.class).forEach(expression -> {
            if(expression.getParentNode().isPresent()){
                Node parent = expression.getParentNode().get();
                NodeMetaModel metaModel = expression.getMetaModel();
                NodeMetaModel parentMetaModel = parent.getMetaModel();

                String methodAccessName = classTwoName.toLowerCase() + ".get" + call.substring(0, 1).toUpperCase() +
                        call.substring(1).toLowerCase();

                // Fix to resolve BinaryExpr to it's ultimate parent
                if(parentMetaModel.is(BinaryExpr.class)){
                    while(parentMetaModel.is(BinaryExpr.class) && parent.getParentNode().isPresent()){
                        parent = parent.getParentNode().get();
                        parentMetaModel = parent.getMetaModel();
                    }
                }

                if(metaModel.is(FieldAccessExpr.class)){
                    FieldAccessExpr fieldAccessExpr = expression.asFieldAccessExpr();

                    if(parentMetaModel.is(AssignExpr.class)){
                        // Find all instances of assigning a field (i.e. `refactoredField = 0`)
                        AssignExpr assignExpr = (AssignExpr) parent;

                        // If the field's value is the refactored field (i.e. `b = refactoredField;`)
                        if (assignExpr.getValue().toString().equals(call)) {
                            replaceField(classTwoName, assignExpr, finalIsGetterSetterNeeded, call);
                            // If the field's name is the refactored field (i.e. `refactoredField = b;`)
                        } else if (fieldAccessExpr.getNameAsString().equals(call)) {
                            if (!replaceField(finalIsGetterSetterNeeded, classTwoName, assignExpr, call)) {
                                // Reassign the name for direct access for public field
                                fieldAccessExpr.setName(classTwoName.toLowerCase() + "." + call);
                            }
                        }
                    }else if(parentMetaModel.is(MethodCallExpr.class)){
                        // Find all instances of using a field within a method (i.e. `a(refactoredField);`)
                        // Only get refactored field
                        if (fieldAccessExpr.getNameAsString().equals(call)) {
                            replaceField(classTwoName, fieldAccessExpr, finalIsGetterSetterNeeded, call);
                        }
                    }else if(parentMetaModel.is(ReturnStmt.class)){
                        // Find all instances of returning the field (i.e. `return refactoredField;`)
                        ReturnStmt returnStmt = (ReturnStmt) parent;

                        // Only get refactored field
                        if (fieldAccessExpr.getNameAsString().equals(call)) {
                            // If field isn't public
                            if (finalIsGetterSetterNeeded) {
                                MethodCallExpr mc;

                                // Check to see if unary expression is used (i.e.
                                // `return refactoredField++;`)
                                if (returnStmt.toString().contains("++") || returnStmt.toString().contains("--")) {
                                    replaceField(classTwoName, returnStmt, cTwo, call);
                                } else {
                                    // Create the getter call
                                    mc = new MethodCallExpr(methodAccessName);

                                    // Replace the access with the method call
                                    fieldAccessExpr.replace(mc);
                                }
                            } else {
                                // Reassign the name for direct access for public field
                                fieldAccessExpr.setName(classTwoName.toLowerCase() + "." + call);
                            }
                        }
                    }else if(parentMetaModel.is(UnaryExpr.class)){
                        // Find all instances of unary expressions (i.e. `refactoredField++;)
                        UnaryExpr unaryExpr = (UnaryExpr) parent;

                        // Only get refactored field
                        if (fieldAccessExpr.getNameAsString().equals(call)) {
                            // If field isn't public
                            if (!replaceField(finalIsGetterSetterNeeded, classTwoName, unaryExpr, cTwo,
                                    call)) {
                                // Reassign the name for direct access for public field
                                fieldAccessExpr.setName(classTwoName.toLowerCase() + "." + call);
                            }
                        }
                    }
                }else if(metaModel.is(NameExpr.class)){
                    NameExpr nameExpr = expression.asNameExpr();

                    if(parentMetaModel.is(AssignExpr.class)){
                        // Find all instances of assigning a field (i.e. `refactoredField = 0`)
                        AssignExpr assignExpr = (AssignExpr) parent;

                        // If the field's value is the refactored field (i.e. `b = refactoredField;`)
                        if (assignExpr.getValue().toString().equals(call)) {
                            // If field isn't public
                            replaceField(classTwoName, assignExpr, finalIsGetterSetterNeeded, call);
                            // If the field's name is the refactored field (i.e. `refactoredField = b;`)
                        } else if (nameExpr.getNameAsString().equals(call)) {
                            if (!replaceField(finalIsGetterSetterNeeded, classTwoName, assignExpr, call)) {
                                // Reassign the name for direct access for public field
                                nameExpr.setName(classTwoName.toLowerCase() + "." + call);
                            }
                        }
                    }else if(parentMetaModel.is(MethodCallExpr.class)){
                        // Find all instances of using a field within a method (i.e. `a(refactoredField);`)
                        // Only get refactored field
                        if (nameExpr.getNameAsString().equals(call)) {
                            // If field isn't public
                            if (!replaceField(finalIsGetterSetterNeeded, classTwoName, nameExpr, call)) {
                                // Reassign the name for direct access for public field
                                nameExpr.setName(classTwoName.toLowerCase() + "." + call);
                            }
                        }
                    }else if(parentMetaModel.is(ReturnStmt.class)){
                        // Find all instances of returning the field (i.e. `return refactoredField;`)
                        ReturnStmt returnStmt = (ReturnStmt) parent;

                        if (nameExpr.getNameAsString().equals(call)) {
                            // If field isn't public
                            if (finalIsGetterSetterNeeded) {
                                MethodCallExpr mc;

                                // Check to see if unary expression is used (i.e.
                                // `return refactoredField++;`)
                                if (returnStmt.toString().contains("++") || returnStmt.toString().contains("--")) {
                                    replaceField(classTwoName, returnStmt, cTwo, call);
                                } else {
                                    // Create the getter call
                                    mc = new MethodCallExpr(methodAccessName);

                                    // Replace the access with the method call
                                    nameExpr.replace(mc);
                                }
                            } else {
                                // Reassign the name for direct access for public field
                                nameExpr.setName(classTwoName.toLowerCase() + "." + call);
                            }
                        }
                    }else if(parentMetaModel.is(UnaryExpr.class)){
                        // Find all instances of unary expressions (i.e. `refactoredField++;)
                        UnaryExpr unaryExpr = (UnaryExpr) parent;

                        if (nameExpr.getNameAsString().equals(call)) {
                            // Only get refactored field
                            if (!replaceField(finalIsGetterSetterNeeded, classTwoName, unaryExpr, cTwo,
                                    call)) {
                                // Reassign the name for direct access for public field
                                nameExpr.setName(classTwoName.toLowerCase() + "." + call);
                            }
                        }
                    }
                }else if(metaModel.is(VariableDeclarationExpr.class)){
                    VariableDeclarationExpr variableDeclarationExpr = expression.asVariableDeclarationExpr();
                    // Find all instances of declaring a field (i.e. `String a = b;`)
                    variableDeclarationExpr.getVariables().forEach(variableDeclarator -> {
                        // We only care about fields that have initializers because that's the only
                        // way a refactored field can be assigned (i.e. `String a = refactoredField;`)
                        if (variableDeclarator.getInitializer().isPresent()) {
                            // Only find the refactored field
                            if (variableDeclarator.getInitializer().get().toString().equals(call)) {
                                // If field isn't public
                                if (finalIsGetterSetterNeeded) {
                                    // Create the getter call
                                    MethodCallExpr mc = new MethodCallExpr(methodAccessName
                                    );

                                    // Reassign the initializer to the new call
                                    variableDeclarator.setInitializer(mc);
                                } else {
                                    // Reassign the initializer to just direct access for public field
                                    variableDeclarator.setInitializer(classTwoName.toLowerCase() + "." + call);
                                }
                            }
                        }
                    });
                }
            }
        });

        // Remove the field in the original class
        cOne.remove(cField);

        // Add the import (find's the CU and adds it)
        for (CompilationUnit cu : allCus) {
            // If the class is the correct class (class one)
            if (cu.getClassByName(classOneName).isPresent()) {
                cTwo.getFullyQualifiedName().ifPresent(name -> {
                    cu.addImport(new ImportDeclaration(name, false, false));
                });
                break;
            }
        }

        findAndUpdateInstancesOfClassOne(cOne.getNameAsString(),cTwo.getNameAsString(),res);
    }

    private void findAndUpdateInstancesOfClassOne(String one, String two, List<ClassOrInterfaceDeclaration> res){
        res.stream().filter(c ->
                !c.isInterface() && !c.getNameAsString().equals(one) && !c.getNameAsString().equals(two)
        ).forEach(c -> {
            c.findAll(ObjectCreationExpr.class).forEach(objectCreationExpr -> {
                if(objectCreationExpr.getType().toString().equals(one)) {
                    objectCreationExpr.getParentNode().ifPresent(parent -> {
                        NodeMetaModel metaModel = parent.getMetaModel();

                        if(metaModel.is(VariableDeclarator.class)){
                            VariableDeclarator variableDeclarator = (VariableDeclarator) parent;
                            String arg = objectCreationExpr.getArguments().toString();

                            variableDeclarator.setType(two)
                                    .setInitializer("new "+two+"("+arg.substring(1, arg.length() - 1)+")");
                        }else if(metaModel.is(AssignExpr.class)){
                            AssignExpr assignExpr = (AssignExpr) parent;

                            c.findAll(VariableDeclarator.class, v ->
                                    v.getNameAsString().equals(assignExpr.getTarget().toString())
                            ).forEach(v -> {
                                v.setType(two);
                                assignExpr.setValue(
                                        new ObjectCreationExpr()
                                                .setType(two)
                                                .setArguments(objectCreationExpr.getArguments())
                                );
                            });
                        }else if(metaModel.is(MethodCallExpr.class)){
                            objectCreationExpr.setType(two);
                        }
                    });
                }
            });
        });

        // update fields that use a field from the parent class
        res.stream().filter(c ->
                !c.isInterface() && !c.getNameAsString().equals(one) && !c.getNameAsString().equals(two) &&
                        c.getExtendedTypes().isNonEmpty() && c.getExtendedTypes().get(0).getNameAsString().equals(one)
        ).forEach(c -> {
            if(c.findAll(VariableDeclarator.class, v -> v.getNameAsString().equals(call)).isEmpty()){
                c.findAll(FieldAccessExpr.class, f -> f.getNameAsString().equals(call)).forEach(f -> {

                });
                c.findAll(NameExpr.class, f -> f.getNameAsString().equals(call)).forEach(f -> {

                });
            }
        });
    }

    private <T extends CallableDeclaration<?>> int alreadyAdded(List<DeclarationInfo<T>> declarations,
                                                                CallableDeclaration<T> declaration){
        int count = 0;
        for (DeclarationInfo<T> c : declarations){
            if(c.getDeclaration().equals(declaration))
                return count;
            count++;
        }
        return -1;
    }

    private <T extends CallableDeclaration<?>> void addFieldToDeclarations(List<DeclarationInfo<T>> declarations,
                                                                           Expression expression,
                                                                           CallableDeclaration<T> declaration, Node parent,
                                                                           String name) {
        AtomicReference<String> value = new AtomicReference<>(name);
        NodeMetaModel parentMetaModel = parent.getMetaModel();

        if(parentMetaModel.is(VariableDeclarator.class)){
            expression.findAncestor(VariableDeclarationExpr.class).ifPresent(c -> {
                VariableDeclarator vD = c.getVariable(0);
                NodeMetaModel vDMetaModel = vD.getMetaModel();

                if (vDMetaModel.is(NameExpr.class) || vDMetaModel.is(FieldAccessExpr.class)) {
                    if(vD.getInitializer().isPresent()){
                        value.set(vD.getName()+"="+vD.getInitializer().get());
                    }
                }
            });
        }else if(parentMetaModel.is(AssignExpr.class)){
            expression.findAncestor(AssignExpr.class).ifPresent(c -> {
                Expression cValue = c.getValue();
                NodeMetaModel cValueMetaModel = cValue.getMetaModel();

                if (cValueMetaModel.is(NameExpr.class) || cValueMetaModel.is(FieldAccessExpr.class)) {
                    value.set(c.getTarget()+"="+cValue);
                }
            });
        }

        DeclarationInfo<T> d = new DeclarationInfo<>(declaration);
        int declarationIndex = alreadyAdded(declarations,declaration);
        if(declarationIndex!=-1)
            declarations.get(declarationIndex).addField(value.get(), parent.getMetaModel().toString());
        else
            d.addField(value.get(), parent.getMetaModel().toString());

        if(!d.getFields().isEmpty())
            declarations.add(d);
    }

    private <T extends CallableDeclaration<?>> void addMethodToDeclarations(CallableDeclaration<T> declaration,
                                                                            List<DeclarationInfo<T>> declarations,
                                                                            String name) {
        DeclarationInfo<T> d = new DeclarationInfo<>(declaration);
        int declarationIndex = alreadyAdded(declarations, declaration);

        if(declarationIndex!=-1)
            declarations.get(declarationIndex).addMethod(name);
        else
            d.addMethod(name);

        if(!d.getMethods().isEmpty())
            declarations.add(d);
    }

    private ClassInfo findCalledFieldsAndMethods(ClassOrInterfaceDeclaration classOrInterfaceDeclaration){
        List<DeclarationInfo<ConstructorDeclaration>> constructors = new ArrayList<>();
        List<DeclarationInfo<MethodDeclaration>> methods = new ArrayList<>();

        classOrInterfaceDeclaration.findAll(Expression.class).forEach(expression -> {
            Node parent = expression.getParentNode().get();
            NodeMetaModel metaModel = expression.getMetaModel();
            NodeMetaModel parentMetaModel = parent.getMetaModel();
            MethodDeclaration method = null;
            ConstructorDeclaration constructor = null;

            if(expression.findAncestor(MethodDeclaration.class).isPresent())
                method = expression.findAncestor(MethodDeclaration.class).get();
            else if(expression.findAncestor(ConstructorDeclaration.class).isPresent())
                constructor = expression.findAncestor(ConstructorDeclaration.class).get();

            if(metaModel.is(FieldAccessExpr.class)){
                FieldAccessExpr fieldAccessExpr = expression.asFieldAccessExpr();

                // In this case only this is relevant for now (in class)
                if(fieldAccessExpr.getScope().isThisExpr()){
                    if(method!=null)
                        addFieldToDeclarations(methods,expression,method,parent,fieldAccessExpr.getNameAsString());
                    else if(constructor!=null)
                        addFieldToDeclarations(constructors, expression, constructor, parent,
                                fieldAccessExpr.getNameAsString());
                }
            }else if(metaModel.is(NameExpr.class)){
                if(!parentMetaModel.is(FieldAccessExpr.class)){
                    NameExpr nameExpr = expression.asNameExpr();

                    if(method!=null)
                        addFieldToDeclarations(methods, expression, method, parent, nameExpr.getNameAsString());
                    else if(constructor!=null)
                        addFieldToDeclarations(constructors, expression, constructor, parent,
                                nameExpr.getNameAsString());
                }
            }else if(metaModel.is(MethodCallExpr.class)){
                MethodCallExpr methodCallExpr = expression.asMethodCallExpr();

                if(methodCallExpr.getScope().isEmpty()){
                    if(method!=null)
                        addMethodToDeclarations(method, methods, methodCallExpr.getNameAsString());
                    else if(constructor!=null)
                        addMethodToDeclarations(constructor, constructors, methodCallExpr.getNameAsString());
                }
            }
        });

        return new ClassInfo(classOrInterfaceDeclaration,constructors,methods);
    }

    private void findContextRefactoring(ClassInfo cI, String call){
        for (DeclarationInfo<MethodDeclaration> method : cI.getMethods()){
            if(method.getFields().containsKey(call) || method.getMethods().contains(call)){
                for (Map.Entry<String, String> f : method.getFields().entrySet()) {
                    if(!context.containsKey(f.getKey())){
                        context.put(f.getKey(),"field");
                        findContextRefactoring(cI,f.getKey());
                    }
                }
                for (String m : method.getMethods()) {
                    if(!context.containsKey(m)){
                        context.put(m,"method");
                        findContextRefactoring(cI,m);
                    }
                }
                if(!context.containsKey(method.getDeclaration().getNameAsString())){
                    context.put(method.getDeclaration().getNameAsString(),"method");
                    findContextRefactoring(cI,method.getDeclaration().getNameAsString());
                }
            }

            if(method.getFields().containsValue("VariableDeclarator")||method.getFields().containsValue("AssignExpr")){
                for (Map.Entry<String, String> f : method.getFields().entrySet()) {
                    if(f.getValue().equals("VariableDeclarator")||f.getValue().equals("AssignExpr")){
                        if(f.getKey().contains("=")){
                            String[] a = f.getKey().split("=");
                            if(a[1].equals(call)){
                                context.put(a[0],"field");
                                findContextRefactoring(cI,a[0]);
                            }
                        }
                    }
                }
            }
        }

        for(DeclarationInfo<ConstructorDeclaration> constructor : cI.getConstructors()){
            if(!context.containsKey(constructor.getDeclaration().getParameters().toString())){
                if(constructor.getMethods().contains(call)||constructor.getFields().containsKey(call))
                    context.put(constructor.getDeclaration().getParameters().toString(),"constructor");
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
            MethodCallExpr mc = new MethodCallExpr(classTwo.toLowerCase() + "." +
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
    private void replaceField(String classTwo, ReturnStmt returnStatement, ClassOrInterfaceDeclaration cTwo,
                              String field) {
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
            mc = new MethodCallExpr(classTwo.toLowerCase() + "." +
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
            MethodCallExpr mc = new MethodCallExpr(classTwo.toLowerCase() + ".get" +
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
            MethodCallExpr mc = new MethodCallExpr(classTwo.toLowerCase() + ".get" +
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
            MethodCallExpr mc = new MethodCallExpr(classTwo.toLowerCase() + ".set" +
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
            MethodCallExpr mc = new MethodCallExpr(classTwo.toLowerCase() + ".get" +
                    field.substring(0, 1).toUpperCase() +
                    field.substring(1).toLowerCase()
            );

            // Replace the assignment with the method call
            assignExpr.replace(mc);
        } else {
            // Reassign the value for direct access for public field
            assignExpr.setValue(new FieldAccessExpr(null, classTwo.toLowerCase() + "." + field));
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