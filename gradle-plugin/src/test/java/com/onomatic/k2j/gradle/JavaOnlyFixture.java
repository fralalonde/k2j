package com.onomatic.k2j.gradle;

/**
 * A Java-only class present in the test source set.
 *
 * Its only job is to give the unit tests a *second*, real compiled-classes directory that carries
 * no {@code kotlin/Metadata} — exactly the shape Gradle produces for {@code build/classes/java/main}
 * in a mixed Java+Kotlin module, and the shape that used to make {@code k2j} report a silent
 * "converted 0 class(es)" success. See {@code K2jTaskRunTest.mixedRoots()}.
 */
public class JavaOnlyFixture {

    public String tag() {
        return "java";
    }
}
