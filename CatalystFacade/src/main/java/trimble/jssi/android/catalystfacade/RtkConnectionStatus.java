package trimble.jssi.android.catalystfacade;

public enum RtkConnectionStatus {
    
    /**
     * Starting NTRIP.
     */
    StartingNTRIP,

    /**
     * Waiting for correction data.
     */
    WaitingCorrectiondata,
    
    /**
     * NTRIP is running.
     */
    NTRIPRunning,
   
    /**
     * Starting RTX Via IP.
    */  
    StartingRTXViaIP,

    /**
     * RTX Via IP is running.
    */    
    RTXViaIPRunning,

    /**
     * Starting RTX via satellite.
    */    
    StartingRTXViaSatellite,

    /**
     * RTX via satellite is running.
    */    
    RTXViaSatelliteRunning
}
