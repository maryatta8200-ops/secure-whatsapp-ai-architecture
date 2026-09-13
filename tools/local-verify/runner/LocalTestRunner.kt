/**
 * LOCAL SANDBOX TEST RUNNER - NOT PART OF THE SHIPPED APPLICATION.
 *
 * Discovers classes whose simple name ends with `Test` in a compiled output
 * directory, runs every method annotated with `org.junit.Test` and reports the
 * result. It exists so the project's real test sources are actually executed in
 * the sandbox, where the JUnit runtime cannot be downloaded.
 *
 * Behaviour mirrors what a JUnit runner guarantees for this project:
 *  - a fresh instance per test method,
 *  - every test method runs even when an earlier one fails,
 *  - a non-zero exit code when any test fails.
 */
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        System.err.println("usage: LocalTestRunner <compiled-classes-dir> [name-filter]")
        kotlin.system.exitProcess(2)
    }
    val root = File(args[0])
    if (!root.isDirectory) {
        System.err.println("not a directory: ${root.absolutePath}")
        kotlin.system.exitProcess(2)
    }
    val nameFilter = args.getOrNull(1)

    val loader = URLClassLoader(arrayOf(root.toURI().toURL()), LocalTestRunner::class.java.classLoader)
    val classFiles = root.walkTopDown()
        .filter { it.isFile && it.extension == "class" && !it.name.contains("$") }
        .filter { it.nameWithoutExtension.endsWith("Test") }
        .toList()

    if (classFiles.isEmpty()) {
        System.err.println("no test classes found under ${root.absolutePath}")
        kotlin.system.exitProcess(2)
    }

    var passed = 0
    val failures = mutableListOf<String>()

    for (file in classFiles.sortedBy { it.absolutePath }) {
        val className = file.relativeTo(root).path
            .removeSuffix(".class")
            .replace(File.separatorChar, '.')
        if (nameFilter != null && !className.contains(nameFilter)) continue

        val clazz = Class.forName(className, true, loader)
        val methods = clazz.declaredMethods
            .filter { it.isAnnotationPresent(org.junit.Test::class.java) }
            .sortedBy { it.name }

        if (methods.isEmpty()) continue
        println("── $className")

        for (method in methods) {
            val startedAt = System.nanoTime()
            try {
                val instance = clazz.getDeclaredConstructor().newInstance()
                method.isAccessible = true
                method.invoke(instance)
                val millis = (System.nanoTime() - startedAt) / 1_000_000
                passed++
                println("   PASS  ${method.name} (${millis}ms)")
            } catch (failure: InvocationTargetException) {
                val cause = failure.targetException
                val millis = (System.nanoTime() - startedAt) / 1_000_000
                when (cause) {
                    is AssertionError -> failures += "${clazz.simpleName}.${method.name}: ${cause.message}"
                    else -> failures += "${clazz.simpleName}.${method.name}: ${cause.javaClass.name}: ${cause.message}"
                }
                println("   FAIL  ${method.name} (${millis}ms)")
            } catch (error: Throwable) {
                failures += "${clazz.simpleName}.${method.name}: could not be run: ${error.message}"
                println("   FAIL  ${method.name}")
            }
        }
    }

    println()
    println("Tests run: ${passed + failures.size}, passed: $passed, failed: ${failures.size}")
    failures.forEach { println("  FAILED  $it") }

    kotlin.system.exitProcess(if (failures.isEmpty()) 0 else 1)
}

/** Named object so `main` above has a stable enclosing class. */
object LocalTestRunner
