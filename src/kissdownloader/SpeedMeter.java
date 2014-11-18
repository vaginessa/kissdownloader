package kissdownloader;

import java.util.concurrent.TimeUnit;

public class SpeedMeter implements Runnable
{
    private long progress;
    private final Downloader down;
    private volatile boolean exit;
    
    public static final int SLEEP = 3000;
    public static final int NO_DATA_SLEEP = 250;
  
    SpeedMeter(Downloader down)
    {
        this.down = down;
        this.progress = down.getProg();
        this.exit = false;
    }
    
    public void setExit(boolean value)
    {
        this.exit = value;
    }
    
    @Override
    public void run()
    { 
        long p, sp;
        int no_data_count;
                
        MiscTools.swingSetText(this.down.getPanel().speed, "------ KB/s", false);
        MiscTools.swingSetText(this.down.getPanel().rem_time, "--d --:--:--", false);
        MiscTools.swingSetVisible(this.down.getPanel().speed, true, false);
        MiscTools.swingSetVisible(this.down.getPanel().rem_time, true, false);
        
        while(!this.exit)
        {
            try
            {
                Thread.sleep(SpeedMeter.SLEEP);

                if(!this.exit)
                {
                    no_data_count=0;

                    do
                    {
                        p = this.down.getProg();

                        sp = (p - this.progress)/(1024*((SpeedMeter.SLEEP/1000) + no_data_count*(SpeedMeter.NO_DATA_SLEEP/1000)));

                        if(sp > 0) {
                            this.progress = p;
                            MiscTools.swingSetText(this.down.getPanel().speed, String.valueOf(sp)+" KB/s", false);
                            MiscTools.swingSetText(this.down.getPanel().rem_time, this.calculateRemTime((long)Math.floor(this.down.getFile_Size()-p)/(sp*1024)), false);
                        }
                        else if(!this.down.isPause())
                        {
                            no_data_count++;
                            MiscTools.swingSetText(this.down.getPanel().speed, "------ KB/s", false);
                            MiscTools.swingSetText(this.down.getPanel().rem_time, "--d --:--:--", false);
                            Thread.sleep(SpeedMeter.NO_DATA_SLEEP);
                        }

                    }while(sp == 0 && !this.down.isPause()); 
                    
                    if(this.down.isPause()) {
                        
                        MiscTools.swingSetText(this.down.getPanel().speed, "------ KB/s", false);
                        MiscTools.swingSetText(this.down.getPanel().rem_time, "--d --:--:--", false);

                        synchronized(this.down.getPauseLock()) {
                            this.down.getPauseLock().wait();
                        }
                    }
                }
            }
            catch (InterruptedException ex)
            {
                this.exit = true;
            }
        }
        
        MiscTools.swingSetVisible(this.down.getPanel().speed, false, false);
        MiscTools.swingSetVisible(this.down.getPanel().rem_time, false, false);
        this.down.printDebug("Speedmeter: bye bye");
    }
    
    private String calculateRemTime(long seconds)
    {
        int days = (int) TimeUnit.SECONDS.toDays(seconds);
        
        long hours = TimeUnit.SECONDS.toHours(seconds) -
                     TimeUnit.DAYS.toHours(days);
        
        long minutes = TimeUnit.SECONDS.toMinutes(seconds) - 
                      TimeUnit.DAYS.toMinutes(days) -
                      TimeUnit.HOURS.toMinutes(hours);
        
        long secs = TimeUnit.SECONDS.toSeconds(seconds) -
                      TimeUnit.DAYS.toSeconds(days) -
                      TimeUnit.HOURS.toSeconds(hours) - 
                      TimeUnit.MINUTES.toSeconds(minutes);
        
        return String.format("%dd %d:%02d:%02d", days, hours, minutes, secs);
    }
}
