package tools.dscode.launcher;

import java.util.ArrayList;
import java.util.List;

/**
 * PowerShell single-quoted literals for agent command lines.
 * ProcessBuilder argument arrays do not need this. A string that PowerShell
 * will parse, including {@code -Dexec.args}, does.
 */
public final class WorkbenchShellQuoting {
    private WorkbenchShellQuoting() {
    }

    /**
     * PowerShell single-quoted literal. The only escape is a doubled quote.
     * Backslashes, spaces, and non-ASCII characters stay literal.
     */
    public static String powershellLiteral(String value) {
        String text = value == null ? "" : value;
        return "'" + text.replace("'", "''") + "'";
    }

    /** One PowerShell command that prints each argument on its own line. */
    public static List<String> powershellEcho(List<String> arguments) {
        StringBuilder script = new StringBuilder();
        script.append("& { ");
        if (arguments != null) {
            for (String argument : arguments) {
                script.append("Write-Output ").append(powershellLiteral(argument)).append("; ");
            }
        }
        script.append("}");
        List<String> command = new ArrayList<>();
        command.add("pwsh");
        command.add("-NoProfile");
        command.add("-NonInteractive");
        command.add("-Command");
        command.add(script.toString());
        return List.copyOf(command);
    }

    /** Inverse of {@link #powershellLiteral(String)} for a single quoted token. */
    public static String parsePowershellLiteral(String quoted) {
        if (quoted == null || quoted.length() < 2 || quoted.charAt(0) != '\'' || quoted.charAt(quoted.length() - 1) != '\'') {
            throw new IllegalArgumentException("Not a PowerShell single-quoted literal: " + quoted);
        }
        String body = quoted.substring(1, quoted.length() - 1);
        StringBuilder out = new StringBuilder();
        for (int index = 0; index < body.length(); index++) {
            char ch = body.charAt(index);
            if (ch == '\'') {
                if (index + 1 >= body.length() || body.charAt(index + 1) != '\'') {
                    throw new IllegalArgumentException("Unpaired quote in PowerShell literal: " + quoted);
                }
                out.append('\'');
                index++;
                continue;
            }
            out.append(ch);
        }
        return out.toString();
    }
}
