package tools.dscode.common.servicecalls;

import org.intellij.lang.annotations.Language;

public class ToJsonNode
{
    final @org.intellij.lang.annotations.Language("JSON") String json;
    public @org.intellij.lang.annotations.Language("JSON") String json2;
    public static @org.intellij.lang.annotations.Language("JSON") String json3;

    public ToJsonNode(@org.intellij.lang.annotations.Language("JSON") String json) {
      this.json = json;
      new JsonRecord("""
              {"a":  2}
              """);
    }

    public static void sjson(@org.intellij.lang.annotations.Language("JSON") String json) {

    }

    public record JsonRecord(
            @Language("JSON") String value
    ) {}
}
