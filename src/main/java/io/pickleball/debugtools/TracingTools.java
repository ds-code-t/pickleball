package io.pickleball.debugtools;

public class TracingTools {

    public static void printCallStack() {
        printCallStack(14);
    }

    public static void printCallStack(int traceCount) {
        StackWalker walker = StackWalker.getInstance();
        String trace = walker.walk(frames -> frames
                .skip(2)  // Skip this method
                .limit(traceCount)
                .map(StackWalker.StackFrame::toStackTraceElement)
                .collect(java.util.stream.Collectors.toList()) // Collect frames first
                .stream()
                .reduce(
                        new StringBuilder(),
                        (sb, frame) -> {
                            StringBuilder result = new StringBuilder();
                            if (!sb.isEmpty()) {
                                result.append(sb);
                            }

                            // Process current frame
                            String className = frame.getClassName();
                            className = className.substring(className.lastIndexOf('.') + 1);
                            String method = frame.getMethodName();

                            if (!result.isEmpty()) {
                                String lastContent = result.toString();
                                String lastClass = lastContent.substring(lastContent.lastIndexOf('\n') + 1);
                                if (lastClass.isEmpty()) {
                                    lastClass = lastContent;
                                }

                                // Add newline if class changes
                                if (!lastClass.startsWith(className + ".")) {
                                    result.append("\n").append(className).append(".");
                                }
                                result.append(method).append("->");
                            } else {
                                result.append(className).append(".").append(method).append("->");
                            }

                            return result;
                        },
                        StringBuilder::append
                ).toString());

        // Remove the trailing arrow and reverse the string
        if (trace.endsWith("->")) {
            trace = trace.substring(0, trace.length() - 2);
        }

        // Split by newlines first to preserve the class grouping
        String[] lines = trace.split("\n");
        StringBuilder reversed = new StringBuilder();

        // Process each line
        for (int i = lines.length - 1; i >= 0; i--) {
            if (i < lines.length - 1) {
                reversed.append("\n");
            }

            // Split and reverse the methods within each line
            String[] methods = lines[i].split("->");
            StringBuilder reversedLine = new StringBuilder();
            for (int j = methods.length - 1; j >= 0; j--) {
                if (j < methods.length - 1) {
                    reversedLine.append("->");
                }
                reversedLine.append(methods[j]);
            }

            reversed.append(reversedLine);
        }

        System.out.println(reversed);
    }
}