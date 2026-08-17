package io.github.hellices.agentframework.build.harness;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.lang.model.element.Modifier;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

final class PublicContractSurface {

  record UnexpectedRecord(Path source, String typeName) {}

  record RawMapSignature(Path source, String owner, String member, String signature) {}

  record Report(List<UnexpectedRecord> records, List<RawMapSignature> rawMapSignatures) {}

  private static final Set<String> PRIMARY_PUBLIC_PACKAGE_PREFIXES =
      Set.of(
          "io.github.hellices.agentframework.api",
          "io.github.hellices.agentframework.spi",
          "io.github.hellices.agentframework.mcp");

  private PublicContractSurface() {}

  static Report inspect(Path repository) throws IOException {
    List<Path> sources =
        SourcePackages.discover(repository).stream()
            .filter(PublicContractSurface::isJavaMainSource)
            .toList();
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) {
      throw new IllegalStateException("A JDK compiler is required to inspect public contracts");
    }
    List<UnexpectedRecord> records = new ArrayList<>();
    List<RawMapSignature> rawMapSignatures = new ArrayList<>();
    try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null)) {
      JavacTask task =
          (JavacTask)
              compiler.getTask(
                  null,
                  fileManager,
                  null,
                  List.of("-proc:none"),
                  null,
                  fileManager.getJavaFileObjectsFromPaths(sources));
      for (CompilationUnitTree unit : task.parse()) {
        String packageName = unit.getPackageName() == null ? "" : unit.getPackageName().toString();
        if (!isPrimaryPublicPackage(packageName)) {
          continue;
        }
        Path source = Path.of(unit.getSourceFile().toUri());
        for (Tree declaration : unit.getTypeDecls()) {
          if (declaration instanceof ClassTree type) {
            inspectType(source, type, packageName, true, records, rawMapSignatures);
          }
        }
      }
    }
    return new Report(List.copyOf(records), List.copyOf(rawMapSignatures));
  }

  private static void inspectType(
      Path source,
      ClassTree type,
      String ownerPrefix,
      boolean enclosingPublicInterface,
      List<UnexpectedRecord> records,
      List<RawMapSignature> rawMapSignatures) {
    boolean publiclyReachable =
        type.getModifiers().getFlags().contains(Modifier.PUBLIC) || enclosingPublicInterface;
    if (!publiclyReachable) {
      return;
    }
    String qualifiedName = ownerPrefix + "." + type.getSimpleName();
    if (type.getKind() == Tree.Kind.RECORD) {
      records.add(new UnexpectedRecord(source, qualifiedName));
    }
    boolean interfaceMember = type.getKind() == Tree.Kind.INTERFACE;
    for (Tree member : type.getMembers()) {
      if (member instanceof MethodTree method) {
        if (exposesRawMap(interfaceMember, method)) {
          rawMapSignatures.add(
              new RawMapSignature(
                  source, qualifiedName, methodName(type, method), methodSignature(method)));
        }
      } else if (member instanceof VariableTree field) {
        if (field.getModifiers().getFlags().contains(Modifier.PUBLIC)
            && containsRawObjectMap(field.getType())) {
          rawMapSignatures.add(
              new RawMapSignature(
                  source, qualifiedName, field.getName().toString(), normalized(field.toString())));
        }
      } else if (member instanceof ClassTree nested) {
        inspectType(
            source,
            nested,
            qualifiedName,
            publiclyReachable && type.getKind() == Tree.Kind.INTERFACE,
            records,
            rawMapSignatures);
      }
    }
  }

  private static boolean exposesRawMap(boolean interfaceMember, MethodTree method) {
    Set<Modifier> modifiers = method.getModifiers().getFlags();
    if (modifiers.contains(Modifier.PRIVATE)) {
      return false;
    }
    if (!interfaceMember && !modifiers.contains(Modifier.PUBLIC)) {
      return false;
    }
    if (containsRawObjectMap(method.getReturnType())) {
      return true;
    }
    for (VariableTree parameter : method.getParameters()) {
      if (containsRawObjectMap(parameter.getType())) {
        return true;
      }
    }
    return false;
  }

  private static boolean containsRawObjectMap(Tree type) {
    if (!(type instanceof ParameterizedTypeTree parameterized)) {
      return false;
    }
    if (!isMapType(parameterized.getType().toString())) {
      return false;
    }
    List<? extends Tree> arguments = parameterized.getTypeArguments();
    return arguments.size() == 2
        && isStringType(arguments.get(0).toString())
        && isObjectType(arguments.get(1).toString());
  }

  private static boolean isMapType(String typeName) {
    return "Map".equals(typeName) || "java.util.Map".equals(typeName);
  }

  private static boolean isStringType(String typeName) {
    return "String".equals(typeName) || "java.lang.String".equals(typeName);
  }

  private static boolean isObjectType(String typeName) {
    return "Object".equals(typeName) || "java.lang.Object".equals(typeName);
  }

  private static String methodName(ClassTree type, MethodTree method) {
    String name = method.getName().toString();
    return "<init>".equals(name) ? type.getSimpleName().toString() : name;
  }

  private static String methodSignature(MethodTree method) {
    return normalized(method.toString());
  }

  private static String normalized(String text) {
    return text.replaceAll("\\s+", " ").trim();
  }

  private static boolean isPrimaryPublicPackage(String packageName) {
    if (packageName.contains(".internal")) {
      return false;
    }
    for (String prefix : PRIMARY_PUBLIC_PACKAGE_PREFIXES) {
      if (packageName.equals(prefix) || packageName.startsWith(prefix + ".")) {
        return true;
      }
    }
    return false;
  }

  private static boolean isJavaMainSource(Path source) {
    String path = source.toString().replace(source.getFileSystem().getSeparator(), "/");
    return path.endsWith(".java") && path.contains("/src/main/java/");
  }
}
