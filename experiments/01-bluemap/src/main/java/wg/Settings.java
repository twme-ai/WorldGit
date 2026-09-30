package wg;

import com.flowpowered.math.vector.Vector2i;
import de.bluecolored.bluemap.core.map.MapSettings;
import de.bluecolored.bluemap.core.map.mask.Mask;

/** 對應 BlueMap common 的 MapConfig 預設值（hires 32、lowres 500、lod 3 層 x5）。 */
public class Settings implements MapSettings {
    @Override public int getSorting() { return 0; }
    @Override public Vector2i getStartPos() { return Vector2i.ZERO; }
    @Override public String getSkyColor() { return "#7dabff"; }
    @Override public String getVoidColor() { return "#000000"; }
    @Override public long getMinInhabitedTime() { return 0; }
    @Override public int getMinInhabitedTimeRadius() { return 0; }
    @Override public int getHiresTileSize() { return 32; }
    @Override public int getLowresTileSize() { return 500; }
    @Override public int getLodCount() { return 3; }
    @Override public int getLodFactor() { return 5; }
    @Override public float getSkyLight() { return 1; }
    @Override public boolean isEnablePerspectiveView() { return true; }
    @Override public boolean isEnableFlatView() { return true; }
    @Override public boolean isEnableFreeFlightView() { return true; }
    @Override public boolean isEnableHires() { return true; }
    @Override public boolean isCheckForRemovedRegions() { return false; }
    @Override public int getRemoveCavesBelowY() { return 55; }
    @Override public int getCaveDetectionOceanFloor() { return 10000; }
    @Override public boolean isCaveDetectionUsesBlockLight() { return false; }
    @Override public float getAmbientLight() { return 0; }
    @Override public Mask getRenderMask() { return Mask.ALL; }
    @Override public boolean isIgnoreMissingLightData() { return true; }
}
