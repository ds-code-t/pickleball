package tools.dscode.common.agentcontrol;

import java.nio.file.Path;

/** Child process for the cross-process surface-id test. Not a product entry point. */
public final class SurfaceIdChild {
    private SurfaceIdChild() {
    }

    public static void main(String[] args) throws Exception {
        System.out.println("ready");
        System.out.flush();
        Path file = Path.of(args[0]);
        String id = PageSurfaces.allocateSurfaceId(file);
        PageSurfaces.recordSurface(file, id, "sha-" + id, "summary-" + id);
        System.out.println("id=" + id);
        System.out.flush();
    }
}
