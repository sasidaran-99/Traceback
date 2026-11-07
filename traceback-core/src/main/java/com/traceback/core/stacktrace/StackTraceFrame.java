package com.traceback.core.stacktrace;

/**
 * Individual frame in a Java stack trace representing an execution point.
 */
public record StackTraceFrame(
        String declaringClass,
        String methodName,
        String fileName,
        int lineNumber,
        String packageName,
        boolean isApplicationFrame
) {
    public static StackTraceFrame of(String declaringClass, String methodName, String fileName, int lineNumber) {
        String pkg = "";
        int lastDot = declaringClass.lastIndexOf('.');
        if (lastDot > 0) {
            pkg = declaringClass.substring(0, lastDot);
        }

        boolean isApp = !pkg.startsWith("java.")
                && !pkg.startsWith("javax.")
                && !pkg.startsWith("jakarta.")
                && !pkg.startsWith("jdk.")
                && !pkg.startsWith("sun.")
                && !pkg.startsWith("org.springframework.")
                && !pkg.startsWith("org.apache.tomcat.")
                && !pkg.startsWith("org.apache.catalina.")
                && !pkg.startsWith("org.hibernate.")
                && !pkg.startsWith("com.zaxxer.hikari.")
                && !pkg.startsWith("org.postgresql.");

        return new StackTraceFrame(declaringClass, methodName, fileName, lineNumber, pkg, isApp);
    }
}
