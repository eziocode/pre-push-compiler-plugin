package com.github.prepushchecker;

import java.util.List;
import java.util.regex.Pattern;

/** Full-line build-tool scaffolding permitted around source diagnostics. These expressions
 * are also embedded in the POSIX hook, so use syntax shared by Java and POSIX ERE. */
final class CompilerOutputRules {
    private CompilerOutputRules() {}

    static final String INFRASTRUCTURE =
        "internal (compiler )?error|exception in thread|stackoverflowerror|stack overflow|outofmemoryerror"
        + "|annotation processor.*failed|compiler plugin|timed out|was aborted|build script"
        + "|^(java: *)?(java|javax|kotlin|org[.]jetbrains)[.].*(exception|error)(:| |$)";
    static final Pattern INFRASTRUCTURE_PATTERN = Pattern.compile(INFRASTRUCTURE);

    static final String MAVEN_GOAL = "Failed to execute goal org[.]apache[.]maven[.]plugins:maven-compiler-plugin:[^ ]+:(compile|testCompile) "
            + "\\([^)]+\\) on project [^ ]+: Compilation failure(: Compilation failure)?";
    static final Pattern INLINE_GOAL = Pattern.compile("^(?:\\[ERROR] *)?" + MAVEN_GOAL + ": +");

    private static final List<String> WRAPPERS = List.of(
        "COMPILATION ERROR *:?", "[-]+", "BUILD FAILURE", "-> *\\[Help 1\\]",
        MAVEN_GOAL + ":?",
        "To see the full stack trace of the errors, re-run Maven with the -e switch[.]",
        "Re-run Maven using the -X switch to enable full debug logging[.]",
        "For more information about the errors and possible solutions, please read the following articles:",
        "\\[Help 1\\] https?://[^ ]+/MojoFailureException",
        "After correcting the problems, you can resume the build with the command",
        "mvn <args> -rf :[^ ]+",
        "FAILURE: Build failed with an exception[.]", "[*] (What went wrong|Try):",
        "Execution failed for task '(:[^: ']+)*:compile(Test)?(Java|Kotlin|Scala|Groovy)'[.]",
        "> Compilation failed; see the compiler (error )?output ((below |above )?for details|below|above)[.]",
        "> A failure occurred while executing org[.]jetbrains[.]kotlin[.]compilerRunner[.]GradleCompilerRunnerWithWorkers[$]GradleKotlinCompilerWorkAction",
        "> Compilation error[.] See log for more details",
        "> Run with --(info|debug|stacktrace|scan)( or --debug)? option to get (more log output|the stack trace|full insights)[.]",
        "> Check your code and dependencies to fix the compilation error\\(s\\)[.]",
        "> Get more help at https://help[.]gradle[.]org[.]",
        "BUILD FAILED in [0-9hms .]+",
        "\\[Incubating\\] Problems report is available at: file:[^ ]+"
    );

    static final String WRAPPER = "^(" + String.join("|", WRAPPERS) + ")$";
    static final Pattern WRAPPER_PATTERN = Pattern.compile(WRAPPER);
}
