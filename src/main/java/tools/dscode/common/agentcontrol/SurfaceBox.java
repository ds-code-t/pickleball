package tools.dscode.common.agentcontrol;

/**
 * One box for a surface. {@code width} and {@code height} are {@code -1} when
 * no size was stored. {@link PageSurfaces#rememberGeometry} keeps only a point;
 * a sized view comes from a resolved element box or from stored width and height.
 */
public final class SurfaceBox {
    private SurfaceBox() {
    }

    public record View(int x, int y, int width, int height, String source) {
        public boolean sized() {
            return width >= 0 && height >= 0;
        }
    }

    public static View choose(String handle, String geometryJson, Integer x, Integer y, Integer width, Integer height) {
        return PageSurfaces.chooseBox(handle, geometryJson, x, y, width, height);
    }
}
