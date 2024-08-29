package com.troxal.refactoringapplicator;

import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.utils.SourceRoot;
import org.apache.tomcat.util.http.fileupload.FileUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.util.IO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.stream.Stream;

@RestController
public class Controller {
    Repository repo = null;
    String repoDirectory = "";

    @PostMapping("/api/repository")
    public Map<String, String> getRepository(@RequestParam(name = "projectUrl", required = true) String projectUrl) {
        Map<String, String> result = new HashMap<>();
        if(projectUrl.equals("demo")) {
            File demo = new File("repos/" + projectUrl);
            try{
                if (!demo.exists()) {
                    FileUtils.forceMkdir(demo);
                }else{
                    FileUtils.deleteDirectory(demo);
                }

                copyFolder(new File("src/main/resources/DemoExample").toPath(), demo.toPath());
            }catch(IOException e){
                result.put("status", "failed");
                result.put("message", e.getMessage());
            }
        }

        if (!new File("repos/" + projectUrl).exists()) {
            // Get clean name for Windows
            repoDirectory = "repos/" + projectUrl.replaceAll("[^a-zA-Z0-9\\.\\-]", "_");
            File localPath = new File(repoDirectory);
            if (localPath.exists()) {
                try {
                    FileUtils.deleteDirectory(localPath);
                } catch (IOException e) {
                    result.put("status", "failed");
                    result.put("message", e.getMessage());
                    return result;
                }
            }

            System.out.println("Cloning from " + projectUrl + " to " + localPath);
            try (Git repository = Git.cloneRepository()
                    .setURI(projectUrl)
                    .setDirectory(localPath)
                    .call()) {
                repo = repository.getRepository();
                result.put("status", "success");
                result.put("message", "Repository " + repo.getIdentifier() + " successfully cloned!");
            } catch (Exception e) {
                result.put("status", "failed");
                result.put("message", e.getMessage());
            }
        } else {
            repoDirectory = "repos/" + projectUrl.replaceAll("[^a-zA-Z0-9\\.\\-]", "_");
            File localPath = new File(repoDirectory);
            try (Git git = Git.open(localPath)) {
                repo = git.getRepository();
                result.put("status", "success");
                result.put("message", "Repository " + repo.getIdentifier() + " successfully cloned!");
            } catch (Exception e) {
                result.put("status", "failed");
                result.put("message", e.getMessage());
            }
        }

        return result;
    }

    @GetMapping("/api/classes")
    public Map<String, Object> getClasses() {
        Map<String, Object> result = new HashMap<>();
        ParserConfiguration configuration = new ParserConfiguration();

        // Parse all source files
        SourceRoot sourceRoot = new SourceRoot(Paths.get(repoDirectory));
        sourceRoot.setParserConfiguration(configuration);
        try {
            List<ParseResult<CompilationUnit>> parseResults = sourceRoot.tryToParse("");
            // Now get all compilation unitsList
            List<CompilationUnit> allCus = parseResults.stream()
                    .filter(ParseResult::isSuccessful)
                    .map(r -> r.getResult().get())
                    .toList();

            List<Map<Object, Object>> classInfo = new ArrayList<>();
            List<ClassOrInterfaceDeclaration> res = new ArrayList<>();
            allCus.forEach(cu -> res.addAll(cu.findAll(ClassOrInterfaceDeclaration.class)));

            res.stream()
                    .filter(c -> !c.isInterface())
                    .forEach(c -> {
                        Map<Object, Object> s = new HashMap<>();
                        List<Map<String, Object>> fieldInfo = new ArrayList<>();
                        for (FieldDeclaration ff : c.getFields()) {
                            Map<String, Object> t = new HashMap<>();
                            t.put("name", ff.getVariable(0).getName().getIdentifier());
                            t.put("type", ff.getVariable(0).getType().asString());
                            fieldInfo.add(t);
                        }

                        List<Map<String, String>> methodInfo = new ArrayList<>();
                        for (MethodDeclaration mm : c.getMethods()) {
                            Map<String, String> t = new HashMap<>();
                            t.put("name", mm.getSignature().asString());
                            t.put("access", mm.getAccessSpecifier().asString());
                            methodInfo.add(t);
                        }

                        s.put("name", c.getNameAsString());
                        if (c.getExtendedTypes().isNonEmpty())
                            s.put("parent", c.getExtendedTypes().get(0).getName().asString());
                        else
                            s.put("parent", "");
                        s.put("fields", fieldInfo);
                        s.put("methods", methodInfo);

                        classInfo.add(s);
                    });

            result.put("status", "success");
            result.put("message", classInfo);
        } catch (IOException e) {
            result.put("status", "failed");
            result.put("message", "Error with parsing: " + e);
        }
        return result;
    }

    @GetMapping("/api/delete")
    public Map<String, String> deleteProject() {
        Map<String, String> result = new HashMap<>();
        if (repo != null&&!repoDirectory.equals("src/main/resources/DemoExample")) {
            try {
                FileUtils.deleteDirectory(new File(repoDirectory));
            } catch (IOException e) {
                result.put("status", "failed");
                result.put("message", e.getMessage());
                return result;
            }
            repo.close();
            repo = null;
            result.put("status", "success");
            result.put("message", "Repository " + repoDirectory + " successfully deleted!");
        } else {
            result.put("status", "failed");
            result.put("message", "Failed to delete the repository because none have been cloned.");
        }
        return result;
    }

    @PostMapping("/api/move/field")
    public Map<String, String> moveAttribute(@RequestParam(name = "class", required = true) String classTwo,
                                             @RequestParam(name = "field", required = true) String classField,
                                             @RequestParam(name = "context", required = true) Boolean context) {
        Map<String, String> result = new HashMap<>();

        String field = classField.split("\\.")[1];
        String classOne = classField.split("\\.")[0];

        ParserConfiguration configuration = new ParserConfiguration();

        // Parse all source files
        SourceRoot sourceRoot = new SourceRoot(Paths.get(repoDirectory));
        sourceRoot.setParserConfiguration(configuration);
        try {
            List<ParseResult<CompilationUnit>> parseResults = sourceRoot.tryToParse("");
            // Now get all compilation unitsList
            List<CompilationUnit> allCus = parseResults.stream()
                    .filter(ParseResult::isSuccessful)
                    .map(r -> r.getResult().get())
                    .toList();

            // Get the compilation units for all classes found
            List<ClassOrInterfaceDeclaration> res = new ArrayList<>();
            allCus.forEach(cu -> res.addAll(cu.findAll(ClassOrInterfaceDeclaration.class)));

            // Get the original class where the field is (optional)
            Optional<ClassOrInterfaceDeclaration> cOneOpt = res.stream()
                    .filter(c -> !c.isInterface()
                            && c.getNameAsString().equals(classOne))
                    .findFirst();

            // Get the refactored to class (optional)
            Optional<ClassOrInterfaceDeclaration> cTwoOpt = res.stream()
                    .filter(c -> !c.isInterface()
                            && c.getNameAsString().equals(classTwo))
                    .findFirst();

            // Assuming both classes exist (they have to)
            if (cOneOpt.isPresent() && cTwoOpt.isPresent()) {
                // Get them
                ClassOrInterfaceDeclaration cOne = cOneOpt.get();
                ClassOrInterfaceDeclaration cTwo = cTwoOpt.get();

                if(cOne.getExtendedTypes().stream().anyMatch(e -> e.getNameAsString().equals(classTwo)) ||
                        cTwo.getExtendedTypes().stream().anyMatch(e -> e.getNameAsString().equals(classOne))){
                    result.put("status", "info");
                    result.put("message", "Please apply a Push up or Push Down refactoring instead, you can't perform" +
                            " a move field refactoring within the same branch.");
                    return result;
                }

                // Grab the field, so we know it's modifiers (optional)
                Optional<FieldDeclaration> cFieldOpt = cOne.getFieldByName(field);

                // Assuming the field exists in class one
                if (cFieldOpt.isPresent()) {
                    if (cTwo.getFieldByName(field).isEmpty()) {
                        // Get the field
                        FieldDeclaration cField = cFieldOpt.get();

                        // Assuming the field exists in class one
                        if (cOne.getFieldByName(field).isPresent()) {
                            Refactor refactoring = new Refactor(cOne,cTwo,field,res,allCus);
                            // If the user wants context-ful refactoring or not
                            if(context)
                                refactoring.findFieldAndRefactorContext();
                            else
                                refactoring.findFieldAndRefactorContextless(cField);
                        } else {
                            result.put("status", "error");
                            result.put("message", "Field to be moved does not exist, couldn't apply refactoring.");
                            return result;
                        }
                    } else {
                        result.put("status", "error");
                        result.put("message", "Field to be moved already exists in second class, couldn't apply refactoring.");
                        return result;
                    }

                } else {
                    result.put("status", "error");
                    result.put("message", "Field to be moved does not exist, couldn't apply refactoring.");
                    return result;
                }

                // Save all changes
                sourceRoot.saveAll();
            } else {
                result.put("status", "error");
                result.put("message", "One or both classes do not exist, couldn't apply refactoring.");
                return result;
            }

            result.put("status", "success");
            result.put("message", "Moving " + field + " from class " + classOne + " to " + classTwo + " was a success!");

        } catch (IOException e) {
            result.put("status", "error");
            result.put("message", "Error with parsing: " + e);
        }
        return result;
    }

    @PostMapping("/api/move/method")
    public Map<String, String> moveMethod(@RequestParam(name = "class", required = true) String classTwo,
                                          @RequestParam(name = "method", required = true) String classMethod) {
        Map<String, String> result = new HashMap<>();
        String method = classMethod.split("\\.")[1];
        String classOne = classMethod.split("\\.")[0];


        result.put("status", "success");
        result.put("message", "Moving " + method + " from class " + classOne + " to " + classTwo + " was a success!");
        return result;
    }

    private static void copyFolder(Path src, Path dest) throws IOException {
        try (Stream<Path> stream = Files.walk(src)) {
            stream.forEach(source -> copy(source, dest.resolve(src.relativize(source))));
        }
    }

    private static void copy(Path source, Path dest) {
        try {
            Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }
}

