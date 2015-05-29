package kissdownloader;

import java.awt.Color;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.URL;
import java.net.URLConnection;
import java.nio.channels.FileChannel;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.swing.JFileChooser;
import javax.swing.JSpinner;
import netscape.javascript.JSObject;

class Downloader extends Thread
{
    public static final int MIN_WORKERS=1;
    public static final int DEFAULT_WORKERS=6;
    public static final int MAX_WORKERS=30;
    public static final int MAX_WORKERS_URL=10;
    public static final int CONNECT_TIMEOUT = 30000;
    public static final int EXP_BACKOFF_BASE=2;
    public static final int EXP_BACKOFF_SECS_RETRY=1;
    public static final int EXP_BACKOFF_MAX_WAIT_TIME=128;
    private final MainBox panel;
    private long size;
    private final String megacrypter_link;
    private String file_key;
    private String file_name;
    private String file_pass_hash;
    private String file_noexpire_token;
    private volatile long prog;
    private File file;
    private volatile boolean exit;
    private volatile boolean pause;
    private ChunkWriter filewriter;
    private final ArrayList<ChunkDownloader> chunkdownloaders;
    private final ExecutorService executor;
    private final String[] download_urls;
    private Double progress_bar_rate;
    private OutputStream os;
    private boolean checking_cbc;
    private boolean retrying_mc_api;
    private String fatal_error;
    private final boolean debug_mode;
    private PrintWriter log_file;
    private final ConcurrentLinkedQueue<Integer> chunkPartialReads;
    private final Object pause_lock;
    private int paused_workers;

    public void setPaused_workers(int paused_workers) {
        this.paused_workers = paused_workers;
    }

    public ConcurrentLinkedQueue<Integer> getChunkPartialReads() {
        return chunkPartialReads;
    }

    public boolean isChecking_cbc() {
        return checking_cbc;
    }
    
    public boolean isRetrying_mc_api() {
        return retrying_mc_api;
    }
    
    public String[] getDownload_urls() {
        return download_urls;
    }
    
    public OutputStream getOs() {
        return os;
    }
    
    public Object getPauseLock() {
        return this.pause_lock;
    }
    
    public String getFile_key() {
        return file_key;
    }
    
    public long getFile_Size() {
        return size;
    }
    
    public ExecutorService getExecutor() {
        return executor;
    }

    public ChunkWriter getFilewriter() {
        return filewriter;
    }
  
    public ArrayList getChunkdownloaders() {
        return chunkdownloaders;
    }

    public boolean isExit() {
        return this.exit;
    }
    
    public boolean isPause() {
        return this.pause;
    }
    
    public File getFile()
    {
        return this.file;
    }
    
    public long getProg()
    {
        return this.prog;
    }
    
    public MainBox getPanel()
    {
        return this.panel;
    }
    
    public void setExit(boolean value)
    {
        this.exit = value;
    }
    
    public void setPause(boolean value)
    {
        this.pause = value;
    }
    
    public Downloader(MainBox panel, String url, boolean debug_mode)
    {
        this.panel = panel;
        this.megacrypter_link = url;
        this.chunkdownloaders = new ArrayList();
        this.chunkPartialReads = new ConcurrentLinkedQueue();
        this.checking_cbc = false;
        this.retrying_mc_api = false;
        this.debug_mode = debug_mode;
        this.exit = false;
        this.pause = false;
        this.fatal_error = null;
        this.executor = Executors.newFixedThreadPool(Downloader.MAX_WORKERS + 3);
        this.download_urls = new String[(int)Math.ceil(Downloader.MAX_WORKERS/Downloader.MAX_WORKERS_URL)];
        this.pause_lock = new Object();
        this.paused_workers=0;
    }
        
    @Override
    public void run()
    {
        String[] file_info;
        
        JSObject win = (JSObject) JSObject.getWindow(this.panel);
        
        String exit_message;
        
        int r;

        MiscTools.swingSetText(this.getPanel().status, "Preparing download, please wait...", false);
        
        try {       
            
            file_info = this.getMegaFileMetadata(this.megacrypter_link, this.panel);

            if(!this.exit && file_info!=null)
            {
                this.file_name=file_info[0];
                this.size=Long.valueOf(file_info[1]);
                this.file_key=file_info[2];
                this.file_pass_hash = file_info[3];
                this.file_noexpire_token = file_info[4];
                
                MiscTools.swingSetSelectedFile(this.getPanel().jFileChooser, new File(this.file_name), false);

                do
                {
                    r=MiscTools.swingShowSaveDialog(this.getPanel().jFileChooser, this.getPanel());

                }while(r == JFileChooser.APPROVE_OPTION && MiscTools.swingGetSelectedFile(this.getPanel().jFileChooser).exists());
                
                if(r != JFileChooser.APPROVE_OPTION)
                {
                    this.hideAllExceptStatus();
                    
                    exit_message = "Download CANCELED! (Reload this page to restart)";
                            
                    this.printStatusError(exit_message);
                            
                    win.eval("dl_applet_exit_error('"+exit_message+"');");
                }
                else
                {
                    this.download_urls[0] = this.getMegaFileDownloadUrl(this.megacrypter_link);
                    
                    if(!this.exit)
                    {
                        this.retrying_mc_api = false;
                        
                        MiscTools.swingSetForeground(this.getPanel().status, Color.black, false);
                        
                        MiscTools.swingSetMinimum(this.getPanel().progress, 0, false);
                        MiscTools.swingSetMaximum(this.getPanel().progress, Integer.MAX_VALUE, false);
                        MiscTools.swingSetStringPainted(this.getPanel().progress, true, false);

                        this.progress_bar_rate = (double)Integer.MAX_VALUE/(double)this.size;

                        String filename = MiscTools.swingGetSelectedFile(this.getPanel().jFileChooser).getAbsolutePath();

                        this.file = new File(filename+".mctemp");

                        if(this.file.exists())
                        {
                            MiscTools.swingSetText(this.getPanel().status, "File exists, resuming download...", false);

                            long max_size = this.calculateMaxTempFileSize(this.file.length());

                            if(max_size != this.file.length())
                            {                            
                                MiscTools.swingSetText(this.getPanel().status, "Truncating temp file...", false);

                                try (FileChannel out_truncate = new FileOutputStream(filename+".mctemp", true).getChannel())
                                {
                                    out_truncate.truncate(max_size);
                                }
                            }

                            this.prog = this.file.length();
                            MiscTools.swingSetValue(this.getPanel().progress, (int)Math.ceil(this.progress_bar_rate*this.prog), false);
                        }
                        else
                        {
                            this.prog = 0;
                            MiscTools.swingSetValue(this.getPanel().progress, 0, false);
                        }

                        if(this.debug_mode)
                        {
                            this.log_file = new PrintWriter( new BufferedWriter( new FileWriter(filename +".log") ) );
                            MiscTools.swingSetText(this.getPanel().debug_enabled, "Debug log -> "+filename +".log", false);
                        }
                        else
                            MiscTools.swingSetVisible(this.getPanel().debug_enabled, false, false);

                        this.os = new BufferedOutputStream(new FileOutputStream(this.file, (this.prog > 0)));

                        this.filewriter = new ChunkWriter(this);

                        this.executor.execute(this.filewriter);
                        
                        ProgressMeter pm = new ProgressMeter(this);
                        this.executor.execute(pm);
                        
                        SpeedMeter sp = new SpeedMeter(this);
                        Future future_sp=executor.submit(sp);

                        for(int t=1; t <= Downloader.DEFAULT_WORKERS; t++)
                        {
                            ChunkDownloader c = new ChunkDownloader(t, this);

                            this.chunkdownloaders.add(c);

                            this.executor.execute(c);
                        }
                        
                        MiscTools.swingSetText(this.getPanel().status, "Downloading file from mega.co.nz ...", false);
                        MiscTools.swingSetVisible(this.getPanel().pause_button, true, false);
                        MiscTools.swingSetVisible(this.getPanel().progress, true, false);
                        MiscTools.swingSetVisible(this.getPanel().slots_label, true, false);
                        MiscTools.swingSetVisible(this.getPanel().slots, true, false);
                        MiscTools.swingSetVisible(this.getPanel().cbc_check, true, false);

                        synchronized(this.executor)
                        {
                            try {
                                this.executor.wait();
                            } catch (InterruptedException ex) {
                                this.printDebug(ex.getMessage());
                            }
                        }
                        
                        synchronized(this.chunkPartialReads)
                        {
                            pm.setExit(true);
                            this.chunkPartialReads.notifyAll();
                        }
                        
                        future_sp.cancel(true);
                        
                        this.executor.shutdown();
                        
                        while(!this.executor.isTerminated())
                        {
                            try {
                                
                                this.executor.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS);

                            } catch (InterruptedException ex) {
                                Logger.getLogger(Downloader.class.getName()).log(Level.SEVERE, null, ex);
                            }
                        }

                        this.os.close();

                        MiscTools.swingSetVisible(this.getPanel().pause_button, false, false);
                        MiscTools.swingSetVisible(this.getPanel().stop_button, false, false);
                        MiscTools.swingSetVisible(this.getPanel().cbc_check, false, false);
                        MiscTools.swingSetVisible(this.getPanel().slots_label, false, false);
                        MiscTools.swingSetVisible(this.getPanel().slots, false, false);


                        if(this.prog == this.size)
                        {
                            MiscTools.swingSetValue(this.getPanel().progress, Integer.MAX_VALUE, false);

                            this.file.renameTo(new File(filename));

                            if(MiscTools.swingIsSelected(this.panel.cbc_check))
                            {
                                this.checking_cbc = true;
                                MiscTools.swingSetText(this.getPanel().status, "Checking file integrity, please wait...", false);
                                MiscTools.swingSetVisible(this.getPanel().stop_button, true, false);
                                MiscTools.swingSetText(this.getPanel().stop_button, "CANCEL CHECK", false);

                                this.prog = 0;
                                MiscTools.swingSetValue(this.getPanel().progress, 0, false);

                                boolean cbc_ok;

                                if((cbc_ok = this.verifyFileCBCMAC(filename)))
                                {
                                    exit_message = "File successfully downloaded! (Integrity check PASSED)";
                                    
                                    this.printStatusOK(exit_message);
                                    
                                    win.eval("dl_applet_exit_ok('"+exit_message+"');");
                                }
                                else if(!this.exit)
                                {
                                    exit_message = "BAD NEWS :( File is DAMAGED! (see FAQ for more info)";
                                    
                                    this.printStatusError(exit_message);
                                    
                                    win.eval("dl_applet_exit_error('"+exit_message+"');");
                                }
                                else
                                {                                
                                    exit_message = "File downloaded (Integrity check CANCELED)";
                                    
                                    this.printStatusOK(exit_message);
                                    
                                    win.eval("dl_applet_exit_ok('"+exit_message+"');");
                                }

                                MiscTools.swingSetVisible(this.getPanel().stop_button, false, false);

                                MiscTools.swingSetValue(this.getPanel().progress, Integer.MAX_VALUE, false);
                            }
                            else
                            {
                                exit_message = "File downloaded!";
                                
                                this.printStatusOK(exit_message);
                                
                                win.eval("dl_applet_exit_ok('"+exit_message+"');");
                            }
                        }
                        else if(this.exit && this.fatal_error == null)
                        {
                            this.hideAllExceptStatus();
                            
                            exit_message = "Download STOPPED! (Reload this page to restart)";
                            
                            this.printStatusError(exit_message);
                            
                            win.eval("dl_applet_exit_error('"+exit_message+"');");
                            
                            if(this.file!=null && !MiscTools.swingIsSelected(this.panel.keep_temp)){
                                this.file.delete();
                            }
                            
                        }
                        else if(this.fatal_error != null)
                        {
                            this.hideAllExceptStatus();
                            
                            exit_message = this.fatal_error;
                            
                            this.printStatusError(this.fatal_error);
                            
                            win.eval("dl_applet_exit_error('"+exit_message+"');");
                        }
                        else
                        {
                            this.hideAllExceptStatus();
                            
                            exit_message = "OOOPS!! Something (bad) happened but... what? (Reload this page to restart)";
                            
                            this.printStatusError(exit_message);
                            
                            win.eval("dl_applet_exit_error('"+exit_message+"');");
                        }     
                        

                        if(this.debug_mode)
                            this.log_file.close();
                    }
                    else if(this.fatal_error != null)
                    {
                        this.hideAllExceptStatus();
                        
                        exit_message = this.fatal_error;
                            
                        this.printStatusError(this.fatal_error);
                            
                        win.eval("dl_applet_exit_error('"+exit_message+"');");
                    }
                    else
                    {
                        this.hideAllExceptStatus();
                        
                        exit_message = "Download STOPPED! (Reload this page to restart)";
                            
                        this.printStatusError(exit_message);
                            
                        win.eval("dl_applet_exit_error('"+exit_message+"');");
                        
                        if(this.file!=null && !MiscTools.swingIsSelected(this.panel.keep_temp)){
                            this.file.delete();
                        }
                    }
                }
            }
            else if(this.fatal_error != null)
            {
                this.hideAllExceptStatus();
                
                exit_message = this.fatal_error;
                            
                this.printStatusError(this.fatal_error);
                            
                win.eval("dl_applet_exit_error('"+exit_message+"');");
            }
            else if(file_info == null)
            {
                this.hideAllExceptStatus();
                
                exit_message = "Bad password! (Reload this page to restart)";
                            
                this.printStatusError(exit_message);
                            
                win.eval("dl_applet_exit_error('"+exit_message+"');");
            }
            else
            {
                this.hideAllExceptStatus();
                
                exit_message = "Download STOPPED! (Reload this page to restart)";
                            
                this.printStatusError(exit_message);
                            
                win.eval("dl_applet_exit_error('"+exit_message+"');");
                
                if(this.file!=null && !MiscTools.swingIsSelected(this.panel.keep_temp)){
                    this.file.delete();
                }
            }
        }
        catch (IOException ex) {
            exit_message = "I/O ERROR";
                            
            this.printStatusError(exit_message);
                            
            win.eval("dl_applet_exit_error('"+exit_message+"');");
            
            this.printDebug(ex.getMessage());
        } catch (NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException | InvalidAlgorithmParameterException | IllegalBlockSizeException | BadPaddingException ex) {
            this.printDebug(ex.getMessage());
        }        
        
        this.printDebug("Downloader: bye bye");
        
        win.eval("dl_applet_byebye();");
    }
    
    public synchronized void pause_worker() {
        
        if(++this.paused_workers == (int)MiscTools.swingGetValue(this.getPanel().slots)) {
            
            MiscTools.swingSetText(this.getPanel().status, "Download paused!", false);
            MiscTools.swingSetVisible(this.getPanel().stop_button, true, false);
            MiscTools.swingSetVisible(this.getPanel().keep_temp, true, false);
            MiscTools.swingSetText(this.getPanel().pause_button, "RESUME DOWNLOAD", false);
            MiscTools.swingSetEnabled(this.getPanel().pause_button, true, false);
        }
    }
    
    public boolean checkDownloadUrl(String string_url)
    {
       try {
            URL url = new URL(string_url+"/0-0");
            URLConnection connection = url.openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT);
            InputStream is = connection.getInputStream();
            
            while(is.read()!=-1);
            
            is.close();
             
            return true;
            
        }catch (Exception ex) {
            
            return false;
        }        
    }
    
    public synchronized String getDownloadUrlForWorker(int chunk_downloader_id) throws IOException
    {
        int pos = (int)Math.ceil((double)chunk_downloader_id/Downloader.MAX_WORKERS_URL)-1;

        if(this.download_urls[pos] == null || !this.checkDownloadUrl(this.download_urls[pos]))
        {
            int retry=0;
            
            boolean mc_error;
            
            do
            {
                mc_error=false;
                
                try {
                    this.download_urls[pos] = MegaCrypterAPI.getMegaFileDownloadUrl(this.megacrypter_link, this.file_pass_hash, this.file_noexpire_token);
                }
                catch(MegaCrypterAPIException e)
                {
                    mc_error=true;

                    for(long i=MiscTools.getWaitTimeExpBackOff(retry++, Downloader.EXP_BACKOFF_BASE, Downloader.EXP_BACKOFF_SECS_RETRY, Downloader.EXP_BACKOFF_MAX_WAIT_TIME); i>0 && !this.exit; i--)
                    {
                        this.printStatusError("[Worker "+chunk_downloader_id+"] E "+e.getMessage()+" ("+i+")");
                        
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException ex) {}
                    }
                }

            }while(!this.exit && mc_error);
        }
        
        return this.download_urls[pos];
    }
    
    public synchronized void startSlot()
    {
        MiscTools.swingSetForeground(((JSpinner.DefaultEditor)MiscTools.swingGetEditor(this.getPanel().slots)).getTextField(), Color.black, false);

        this.printDebug("Adding download slot...");

        int chunk_id = this.chunkdownloaders.size()+1;

        ChunkDownloader c = new ChunkDownloader(chunk_id, this);

        this.chunkdownloaders.add(c);

        this.executor.execute(c);
    }
    
    public synchronized void stopLastStartedSlot()
    {
        MiscTools.swingSetForeground(((JSpinner.DefaultEditor)MiscTools.swingGetEditor(this.getPanel().slots)).getTextField(), Color.black, false);
        
        this.printDebug("Removing download slot...");
        
        ChunkDownloader chunkdownloader = this.chunkdownloaders.remove(this.chunkdownloaders.size()-1);
        
        chunkdownloader.setExit(true);
    }
    
    public synchronized void stopThisSlot(ChunkDownloader chunkdownloader, boolean error)
    {
        if(this.chunkdownloaders.remove(chunkdownloader))
        {
            if(error) {
                MiscTools.swingSetForeground(((JSpinner.DefaultEditor)MiscTools.swingGetEditor(this.getPanel().slots)).getTextField(), Color.red, false);
            }

            MiscTools.swingSetValue(this.getPanel().slots, ((int)MiscTools.swingGetValue(this.getPanel().slots))-1, true);
        }
    }
   
    public synchronized boolean chunkDownloadersRunning()
    {
        return !this.getChunkdownloaders().isEmpty();
    }
    
    /* NO SINCRONIZADO para evitar que el progress-meter tenga que esperar */
    public void updateProgress(int reads)
    {
        this.prog+=reads;

        MiscTools.swingSetValue(this.getPanel().progress, (int)Math.ceil(this.progress_bar_rate*this.prog), false);     
    }
    
    private void printStatusError(String message)
    {
        MiscTools.swingSetForeground(this.getPanel().status, Color.red, false);
        MiscTools.swingSetText(this.getPanel().status, message, false);
    }
    
    private void printStatusOK(String message)
    {        
        MiscTools.swingSetForeground(this.getPanel().status, new Color(0,128,0), false);
        MiscTools.swingSetText(this.getPanel().status, message, false);
    }
    
    private boolean verifyFileCBCMAC(String filename) throws FileNotFoundException, IOException, NoSuchAlgorithmException, NoSuchPaddingException, InvalidKeyException, InvalidAlgorithmParameterException, IllegalBlockSizeException, BadPaddingException
    {
        int[] int_key = MiscTools.bin2i32a(MiscTools.UrlBASE642Bin(this.file_key));
        
        int[] iv = new int[2];

        iv[0] = int_key[4];
        iv[1] = int_key[5];
        
        int[] meta_mac = new int[2];
        
        meta_mac[0] = int_key[6];
        meta_mac[1] = int_key[7];
        
        int[] file_mac = {0,0,0,0};
        
        int[] cbc_iv = {0,0,0,0};
     
        Cipher cryptor = CryptTools.genCrypter("AES", "AES/CBC/NoPadding", this.filewriter.getByte_file_key(), MiscTools.i32a2bin(cbc_iv));
 
        File f = new File(filename);
        
        FileInputStream is = new FileInputStream(f);
        
        Chunk chunk=null;
        
        long chunk_id=1;
        long tot=0;
 
        try
        {
            while(!this.exit)
            {
                chunk = new Chunk(chunk_id++, this.size, null);

                tot+=chunk.getSize();
                int[] chunk_mac = {iv[0], iv[1], iv[0], iv[1]};
                int re;
                byte[] buffer = new byte[8*1024];
                byte[] byte_block = new byte[16];
                int[] int_block;
                int reads = -2;
                int to_read;

                do
                {
                    to_read = chunk.getSize() - chunk.getOutputStream().size() >= buffer.length?buffer.length:(int)(chunk.getSize() - chunk.getOutputStream().size());

                    re=is.read(buffer, 0, to_read);

                    chunk.getOutputStream().write(buffer, 0, re);

                }while(!this.exit && chunk.getOutputStream().size()<chunk.getSize());

                InputStream chunk_is = chunk.getInputStream();

                while(!this.exit && (reads=chunk_is.read(byte_block))!=-1)
                {
                     if(reads<byte_block.length)
                     {
                         for(int i=reads; i<byte_block.length; i++)
                             byte_block[i]=0;
                     }

                     int_block = MiscTools.bin2i32a(byte_block);

                     for(int i=0; i<chunk_mac.length; i++)
                     {
                        chunk_mac[i]^=int_block[i];
                     }

                     chunk_mac = MiscTools.bin2i32a(cryptor.doFinal(MiscTools.i32a2bin(chunk_mac)));
                }

                this.updateProgress((int)chunk.getSize());

                for(int i=0; i<file_mac.length; i++)
                {
                    file_mac[i]^=chunk_mac[i];
                }

                file_mac = MiscTools.bin2i32a(cryptor.doFinal(MiscTools.i32a2bin(file_mac)));
            }

        } catch (ChunkInvalidIdException e){}
        
        is.close();
        
        int[] cbc={file_mac[0]^file_mac[1], file_mac[2]^file_mac[3]};

        return (cbc[0] == meta_mac[0] && cbc[1]==meta_mac[1]);
    }
    
    public synchronized void stopDownloader()
    {
        if(!this.exit)
        {
            this.setExit(true);
            
            if(this.isRetrying_mc_api())
            {
                MiscTools.swingSetText(this.getPanel().status, "Canceling retrying, please wait...", false);
                MiscTools.swingSetEnabled(this.getPanel().stop_button, false, false);
            }
            else if(this.isChecking_cbc())
            {
                MiscTools.swingSetText(this.getPanel().status, "Canceling verification, please wait...", false);
                MiscTools.swingSetEnabled(this.getPanel().stop_button, false, false);
            }
            else
            {
                MiscTools.swingSetText(this.getPanel().status, "Stopping download safely, please wait...", false);
                MiscTools.swingSetEnabled(this.getPanel().speed, false, false);
                MiscTools.swingSetEnabled(this.getPanel().pause_button, false, false);
                MiscTools.swingSetEnabled(this.getPanel().stop_button, false, false);
                MiscTools.swingSetEnabled(this.getPanel().keep_temp, false, false);
                MiscTools.swingSetEnabled(this.getPanel().slots_label, false, false);
                MiscTools.swingSetEnabled(this.getPanel().slots, false, false);
                MiscTools.swingSetEnabled(this.getPanel().cbc_check, false, false);
                
                if(this.pause) {
                    
                    synchronized(this.pause_lock)
                    {
                        this.pause_lock.notifyAll();
                    }
                }
                
                synchronized(this.executor)
                {
                    this.executor.notify();
                }
            }
        }
    }
    
    public synchronized void emergencyStopDownloader(String reason)
    {
        if(this.fatal_error == null)
        {
            this.fatal_error = reason!=null?reason:"FATAL ERROR!";
            
            this.stopDownloader();
        }
    }
    
    public void hideAllExceptStatus()
    {
        MiscTools.swingSetVisible(this.getPanel().speed, false, false);
        MiscTools.swingSetVisible(this.getPanel().rem_time, false, false);
        MiscTools.swingSetVisible(this.getPanel().slots, false, false);
        MiscTools.swingSetVisible(this.getPanel().slots_label, false, false);
        MiscTools.swingSetVisible(this.getPanel().pause_button, false, false);
        MiscTools.swingSetVisible(this.getPanel().stop_button, false, false);
        MiscTools.swingSetVisible(this.getPanel().cbc_check, false, false);
        MiscTools.swingSetVisible(this.getPanel().progress, false, false);
    }
    
    public long calculateMaxTempFileSize(long size)
    {
        if(size > 3584*1024)
        {
            long reminder = (size - 3584*1024)%(1024*1024);
            
            return reminder==0?size:(size - reminder);
        }
        else
        {
            int i=0, tot=0;
            
            while(tot < size)
            {
                i++;
                tot+=i*128*1024;
            }
            
            return tot==size?size:(tot-i*128*1024);
        }
    }
    
    public void printDebug(String message)
    {
        if(this.debug_mode) {
            this.log_file.write(message+"\n");
        }
    }
    
    public String[] getMegaFileMetadata(String link, javax.swing.JApplet panel) throws IOException
    {
        String[] file_info=null;
        int retry=0, mc_error_code;
        boolean mc_error;

        do
        {
            mc_error=false;

            try
            {
                 file_info = MegaCrypterAPI.getMegaFileMetadata(link, panel);
            }
            catch(MegaCrypterAPIException e)
            {
                mc_error=true;

                mc_error_code = Integer.parseInt(e.getMessage());

                if(mc_error_code == 23)
                {
                    this.emergencyStopDownloader("MegaCrypter link has expired!");
                }
                else
                {
                    this.retrying_mc_api = true;
                    MiscTools.swingSetVisible(this.getPanel().stop_button, true, false);
                    MiscTools.swingSetText(this.getPanel().stop_button, "CANCEL RETRY", false);

                    for(long i=MiscTools.getWaitTimeExpBackOff(retry++, Downloader.EXP_BACKOFF_BASE, Downloader.EXP_BACKOFF_SECS_RETRY, Downloader.EXP_BACKOFF_MAX_WAIT_TIME); i>0 && !this.exit; i--)
                    {
                        if(mc_error_code == -18)
                        {
                            this.printStatusError("File temporarily unavailable! (Retrying in "+i+" secs...)");
                        }
                        else
                        {
                            this.printStatusError("MegaCrypterAPIException error "+e.getMessage()+" (Retrying in "+i+" secs...)");
                        }

                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException ex) {}
                    }
                }
            }

        }while(!this.exit && mc_error);
        
        if(!mc_error) {
            MiscTools.swingSetVisible(this.getPanel().stop_button, false, false);
        }
        
        return file_info;
    }
    
    public String getMegaFileDownloadUrl(String link) throws IOException
    {
        String dl_url=null;
        int retry=0, mc_error_code;
        boolean mc_error;

        do
        {
            mc_error=false;

            try
            {
                 dl_url = MegaCrypterAPI.getMegaFileDownloadUrl(this.megacrypter_link, this.file_pass_hash, this.file_noexpire_token);
            }
            catch(MegaCrypterAPIException e)
            {
                mc_error=true;

                mc_error_code = Integer.parseInt(e.getMessage());

                if(mc_error_code == 23)
                {
                    this.emergencyStopDownloader("MegaCrypter link has expired!");
                }
                else
                {
                    this.retrying_mc_api = true;
                    MiscTools.swingSetVisible(this.getPanel().stop_button, true, false);
                    MiscTools.swingSetText(this.getPanel().stop_button, "CANCEL RETRY", false);

                    for(long i=MiscTools.getWaitTimeExpBackOff(retry++, Downloader.EXP_BACKOFF_BASE, Downloader.EXP_BACKOFF_SECS_RETRY, Downloader.EXP_BACKOFF_MAX_WAIT_TIME); i>0 && !this.exit; i--)
                    {
                        if(mc_error_code == -18)
                        {
                            this.printStatusError("File temporarily unavailable! (Retrying in "+i+" secs...)");
                        }
                        else
                        {
                            this.printStatusError("MegaCrypterAPIException error "+e.getMessage()+" (Retrying in "+i+" secs...)");
                        }

                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException ex) {}
                    }
                }
            }

        }while(!this.exit && mc_error);
        
        if(!mc_error) {
            MiscTools.swingSetVisible(this.getPanel().stop_button, false, false);
        }
        
        return dl_url;
    }
}
