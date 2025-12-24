package trimble.jssi.android.catalystfacade;

/**
 * The datum transformation settings for the RTK
 *
 */
public enum TargetReferenceFrame {
    /**
     * Don't apply datum transformation to the position
     */
    Off,
    /**
     * Move Rtk to Rtx positions via time dependent datum transformation Source:
     * Local datum, auto detected using polygons Target: ITRF 2020, current epoch
     */
    ToGlobal,
    /**
     * Move Rtk and Rtx positions via time dependent datum transformation Source:
     * Local datum and ITRF 2020, current epoch Target: ITRF 2020, reference epoch:
     * 2015.0
     */
    ToFixGlobal,
    /**
     * Move Rtx to Rtk positions via time dependent datum transformation Source:
     * ITRF 2020, current epoch Target: Local datum
     */
    ToLocal,
    /**
     * Use Trimble Mobile Manager settings
     */
    UseLocalSettings,
    /**
     * Move Rtx to Rtk positions via time dependent datum transformation Source:
     * ITRF 2020, current epoch Target: Reference Frame input
     */
    ToLocalWithTargetReferenceFrame
}
