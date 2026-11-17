package tools.dscode.store;

import org.junit.jupiter.api.Test;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

class StorePackagingTest {
    @Test
    void publishedPomHasNoRemovedJdbcDriverAndTheJarHasNoBinary() throws Exception {
        Path pom = Path.of("build/publications/maven/pom-default.xml");
        if (!Files.isRegularFile(pom)) fail("missing " + pom + " (verifySqlitePackaging should have generated it)");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var document = factory.newDocumentBuilder().parse(pom.toFile());
        var nodes = document.getElementsByTagName("dependency");
        for (int index = 0; index < nodes.getLength(); index++) {
            var node = nodes.item(index);
            String group = child(node, "groupId");
            String artifact = child(node, "artifactId");
            String removedGroup = "org." + "maria" + "db.jdbc";
            String removedArtifact = "maria" + "db-java-client";
            assertFalse(removedGroup.equals(group) && removedArtifact.equals(artifact), group + ":" + artifact);
        }
        Path jar = Path.of("build/libs/pickleball-3.0.0.jar");
        if (!Files.isRegularFile(jar)) fail("missing " + jar);
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                assertFalse(entry.getName().startsWith("org/" + "maria" + "db/"), entry.getName());
                assertFalse(entry.getName().contains("maria" + "db-java-client"), entry.getName());
                assertFalse(entry.getName().contains("store-release.properties"), entry.getName());
                String lower = entry.getName().toLowerCase(java.util.Locale.ROOT);
                assertFalse(lower.endsWith("/store") || lower.endsWith("/store.exe"), entry.getName());
            }
        }
    }

    private static String child(org.w3c.dom.Node parent, String name) {
        var children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            var child = children.item(index);
            if (child.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE && name.equals(child.getNodeName())) {
                return child.getTextContent().trim();
            }
        }
        return "";
    }
}
