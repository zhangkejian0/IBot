package com.xbot.xbot.face;

/**
 * 3×3 gaze zone detector with boundary dead-zone hysteresis.
 *
 * <p>Ported from Flutter {@code lib/face/gaze_zone_detector.dart}.
 */
public class GazeZoneDetector {
    public static final int COLS = 3;
    public static final int ROWS = 3;
    private static final double DEAD_ZONE_RATIO = 0.05;

    private Integer currentCol;
    private Integer currentRow;
    private int changeCount;

    public Integer getCurrentCol() {
        return currentCol;
    }

    public Integer getCurrentRow() {
        return currentRow;
    }

    public int getChangeCount() {
        return changeCount;
    }

    /** Zone center in normalized -1..1 space (screen center is origin). */
    public float[] getCurrentZoneCenter() {
        if (currentCol == null || currentRow == null) {
            return null;
        }
        return zoneCenter(currentCol, currentRow);
    }

    /**
     * @param normalizedX face center X (-1..1)
     * @param normalizedY face center Y (-1..1)
     * @return true when the zone changed
     */
    public boolean update(double normalizedX, double normalizedY) {
        double x01 = (normalizedX + 1) / 2;
        double y01 = (normalizedY + 1) / 2;

        int col = calcZone(x01, COLS);
        int row = calcZone(y01, ROWS);

        if (currentCol == null || currentRow == null) {
            currentCol = col;
            currentRow = row;
            return true;
        }

        if (col == currentCol && row == currentRow) {
            return false;
        }

        if (isInBoundaryDeadZone(x01, currentCol, col, COLS)
                || isInBoundaryDeadZone(y01, currentRow, row, ROWS)) {
            return false;
        }

        currentCol = col;
        currentRow = row;
        changeCount++;
        return true;
    }

    public void reset() {
        currentCol = null;
        currentRow = null;
    }

    public String zoneName(int col, int row) {
        String[] colNames = {"左", "中", "右"};
        String[] rowNames = {"上", "中", "下"};
        return colNames[col] + rowNames[row];
    }

    public String getCurrentZoneName() {
        if (currentCol == null || currentRow == null) {
            return null;
        }
        return zoneName(currentCol, currentRow);
    }

    private static int calcZone(double value01, int divisions) {
        double clamped = Math.max(0.0, Math.min(1.0, value01));
        return Math.max(0, Math.min(divisions - 1, (int) Math.floor(clamped * divisions)));
    }

    private static boolean isInBoundaryDeadZone(
            double value01, int oldZone, int newZone, int totalZones) {
        if (Math.abs(newZone - oldZone) != 1) {
            return false;
        }
        double zoneWidth = 1.0 / totalZones;
        double deadZone = zoneWidth * DEAD_ZONE_RATIO;
        double boundary = newZone > oldZone
                ? (oldZone + 1) * zoneWidth
                : oldZone * zoneWidth;
        return Math.abs(value01 - boundary) < deadZone;
    }

    private static float[] zoneCenter(int col, int row) {
        double zoneWidth = 1.0 / COLS;
        double zoneHeight = 1.0 / ROWS;
        double cx01 = (col + 0.5) * zoneWidth;
        double cy01 = (row + 0.5) * zoneHeight;
        return new float[]{(float) (cx01 * 2 - 1), (float) (cy01 * 2 - 1)};
    }
}
