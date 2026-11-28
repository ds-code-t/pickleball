package tools.dscode.launcher;

import dev.domengine.cli.CliResult;
import dev.domengine.cli.DomEngineMain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Value-level {@code @file} on ask, html-insert, and cdp, plus desktop type help. */
class DocGapsTest {
    @TempDir
    Path temp;

    @Test
    void askSayFileIsTheStoredPrompt() throws Exception {
        Files.writeString(temp.resolve("question.txt"), "What is the score?\n");
        Capture asked = run("ask", "--type=text", "--say=@question.txt", "--run-id=q1");
        assertEquals(0, asked.code, asked.err + asked.out);
        assertTrue(asked.out.contains("What is the score?"), asked.out);
        assertFalse(asked.out.contains("@question.txt"), asked.out);
    }

    @Test
    void askSayFileMissingFailsBeforeALivePage() throws Exception {
        Capture missing = run("ask", "--say=@missing-question.txt", "--run-id=q2");
        assertEquals(2, missing.code, missing.err + missing.out);
        String text = missing.out + missing.err;
        assertTrue(text.contains("file not found"), text);
        assertFalse(text.contains("needs a live browser"), text);
    }

    @Test
    void htmlInsertTextFileIsWrittenIntoThePage() throws Exception {
        Path page = temp.resolve("page.html");
        Files.writeString(page, "<button id=\"go\">Go</button>");
        Files.writeString(temp.resolve("snippet.html"), "<b>FROM-FILE</b>");
        Capture inserted = run("html-insert", "--text=@snippet.html", "--handle=go", "--where=after", "--html=page.html");
        assertEquals(0, inserted.code, inserted.err + inserted.out);
        String html = Files.readString(page);
        assertTrue(html.contains("FROM-FILE"), html);
        assertFalse(html.contains("@snippet.html"), html);
        assertTrue((inserted.out + inserted.err).contains("FROM-FILE"), inserted.out + inserted.err);
    }

    @Test
    void htmlInsertMissingFileFails() throws Exception {
        Files.writeString(temp.resolve("page.html"), "<button id=\"go\">Go</button>");
        Capture missing = run("html-insert", "--text=@no-such.html", "--handle=go", "--where=after", "--html=page.html");
        assertEquals(2, missing.code, missing.err + missing.out);
        assertTrue((missing.out + missing.err).contains("file not found"), missing.out + missing.err);
    }

    @Test
    void cdpParamsFileIsReadBeforeTheBrowserConnects() throws Exception {
        Capture missing = cdp("--params=@missing-params.json");
        assertEquals(1, missing.code, missing.err);
        assertTrue(missing.err.contains("params file not found"), missing.err);
        assertFalse(missing.err.contains("no CDP endpoint"), missing.err);

        Files.writeString(temp.resolve("bad.json"), "not-json");
        Capture bad = cdp("--params=@bad.json");
        assertEquals(1, bad.code, bad.err);
        assertTrue(bad.err.contains("must be a JSON object"), bad.err);
        assertFalse(bad.err.contains("no CDP endpoint"), bad.err);

        Files.writeString(temp.resolve("ok.json"), "{\"expression\":\"1\"}\n");
        Capture accepted = cdp("--params=@ok.json");
        assertEquals(1, accepted.code, accepted.err);
        assertTrue(accepted.err.contains("no CDP endpoint"), accepted.err);
        assertFalse(accepted.err.contains("must be a JSON object"), accepted.err);
        assertFalse(accepted.err.contains("params file not found"), accepted.err);
    }

    @Test
    void domengineHelpNamesDesktopTypeFlags() {
        CliResult result = DomEngineMain.run(new String[]{"help"});
        assertEquals(0, result.exit());
        String help = result.stdout();
        assertTrue(help.contains("version"), help);
        assertTrue(help.contains("type --id SESSION"), help);
        assertTrue(help.contains("--text CHARS"), help);
        assertTrue(help.contains("--role ROLE"), help);
        assertTrue(help.contains("--append=true"), help);
        assertTrue(help.contains("--query is not a type flag"), help);
    }

    private Capture cdp(String paramsFlag) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = PwCommand.run(
                new String[]{"cdp", "--method=Runtime.evaluate", paramsFlag},
                temp,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        return new Capture(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private Capture run(String... args) {
        String[] command = new String[args.length + 1];
        command[0] = args[0];
        command[1] = temp.toString();
        System.arraycopy(args, 1, command, 2, args.length - 1);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = WorkbenchAgentCommands.run(command,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        return new Capture(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private record Capture(int code, String out, String err) {
    }
}
