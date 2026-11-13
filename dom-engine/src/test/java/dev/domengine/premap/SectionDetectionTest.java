package dev.domengine.premap;

import com.fasterxml.jackson.databind.JsonNode;
import dev.domengine.desktop.DesktopTree;
import dev.domengine.json.Jsons;
import dev.domengine.query.HtmlTree;
import dev.domengine.tree.DomNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SectionDetectionTest {
    @Test
    void nestedLandmarks() {
        List<Section> sections = detect("""
                <html><body>
                  <header><nav>Home</nav></header>
                  <main><section aria-label="Intro"><p>Hi</p></section></main>
                </body></html>
                """);
        Section root = sections.get(0);
        assertEquals("document", root.kind());
        Section header = child(sections, root, "landmark", "");
        Section nav = child(sections, header, "landmark", "");
        assertEquals(header.id(), nav.parentId());
        Section main = sections.stream()
                .filter(section -> root.id().equals(section.parentId()) && "landmark".equals(section.kind()) && !section.id().equals(header.id()))
                .findFirst()
                .orElseThrow();
        Section intro = child(sections, main, "landmark", "Intro");
        assertEquals("Intro", intro.title());
        assertTrue(nav.depth() > header.depth());
    }

    @Test
    void headingOutline() {
        List<Section> sections = detect("""
                <html><body>
                  <h1>Title</h1><p>a</p>
                  <h2>Sub</h2><p>b</p>
                  <h2>Other</h2><h3>Deep</h3>
                  <h1>Next</h1>
                </body></html>
                """);
        Section root = sections.get(0);
        Section title = child(sections, root, "heading", "Title");
        Section sub = child(sections, title, "heading", "Sub");
        Section other = child(sections, title, "heading", "Other");
        Section deep = child(sections, other, "heading", "Deep");
        Section next = child(sections, root, "heading", "Next");
        assertEquals("Sub", sub.title());
        assertEquals("Deep", deep.title());
        assertEquals("Next", next.title());
        assertEquals(root.id(), next.parentId());
    }

    @Test
    void fieldsetInFormInDialog() {
        List<Section> sections = detect("""
                <html><body>
                  <dialog aria-label="Edit">
                    <form aria-label="Person">
                      <fieldset><legend>Name</legend><input></fieldset>
                    </form>
                  </dialog>
                </body></html>
                """);
        Section root = sections.get(0);
        Section dialog = child(sections, root, "dialog", "Edit");
        Section form = child(sections, dialog, "form", "Person");
        Section fieldset = child(sections, form, "fieldset", "Name");
        assertEquals("Name", fieldset.title());
    }

    @Test
    void tableCaption() {
        List<Section> sections = detect("""
                <html><body>
                  <table><caption>People</caption><tr><th>Name</th></tr></table>
                </body></html>
                """);
        Section table = child(sections, sections.get(0), "table", "People");
        assertEquals("People", table.title());
    }

    @Test
    void iframeNestsItsDocument() {
        List<Section> sections = detect("""
                <html><body>
                  <iframe id="child" srcdoc="<html><body><main>Inner</main></body></html>"></iframe>
                </body></html>
                """);
        Section frame = child(sections, sections.get(0), "iframe", "");
        Section main = child(sections, frame, "landmark", "");
        assertEquals(frame.id(), main.parentId());
    }

    @Test
    void shadowHostNestsTheInnerSection() {
        List<Section> sections = detect("""
                <html><body>
                  <div id="host"><template shadowrootmode="open">
                    <section aria-label="Shadow">Hi</section>
                  </template></div>
                </body></html>
                """);
        Section shadow = child(sections, sections.get(0), "landmark", "Shadow");
        assertEquals("Shadow", shadow.title());
        assertEquals(sections.get(0).id(), shadow.parentId());
    }

    @Test
    void desktopWindowPaneAndDialog() throws Exception {
        JsonNode json = Jsons.MAPPER.readTree("""
                {"role":"window","name":"Editor","children":[
                  {"role":"pane","name":"Body","children":[
                    {"role":"group","name":"Fields","children":[{"role":"button","name":"Save"}]}
                  ]},
                  {"role":"dialog","name":"Confirm"}
                ]}
                """);
        DomNode root = DesktopTree.project(json);
        root.linkParents();
        List<Section> sections = SectionDetector.detect(root, Platform.DESKTOP);
        Section window = sections.get(0);
        assertEquals("window", window.kind());
        assertEquals("Editor", window.title());
        Section pane = child(sections, window, "pane", "Body");
        Section group = child(sections, pane, "group", "Fields");
        Section dialog = child(sections, window, "dialog", "Confirm");
        assertEquals("Fields", group.title());
        assertEquals(window.id(), dialog.parentId());
        assertTrue(root.sectionPath.startsWith(window.id()));
    }

    private static List<Section> detect(String html) {
        return SectionDetector.detect(HtmlTree.parse(html), Platform.BROWSER);
    }

    private static Section child(List<Section> sections, Section parent, String kind, String title) {
        for (Section section : sections) {
            if (!parent.id().equals(section.parentId())) continue;
            if (!kind.equals(section.kind())) continue;
            if (!title.isEmpty() && !title.equals(section.title())) continue;
            return section;
        }
        throw new AssertionError("missing " + kind + " '" + title + "' under " + parent.id() + " in " + sections);
    }
}
