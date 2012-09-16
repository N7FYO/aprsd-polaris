package no.polaric.aprsd;
import java.net.*;
import java.util.*;
import java.io.*;
import java.text.*;



public class Logfile
{
    private PrintWriter _out; 
    private boolean _log = false;
    
    private DateFormat df = new SimpleDateFormat("dd MMM yyyy HH:mm:ss",
       new DateFormatSymbols(new Locale("no")));
    
    
    public Logfile(Properties config, String configname, String logfile) 
    {     
       try {
          _log = config.getProperty(configname+".log.on", "true").trim().matches("true|yes");
          if (_log) 
            _out = new PrintWriter(new FileOutputStream(Main.logdir+"/"+logfile, true));         
       }
       catch (Exception e) { System.out.println("LOGFILE: "+e); 
                             _log=false; }
    } 

   public void log(String text)
   {
       if (_log) {
         _out.println(df.format(new Date()) + text);
         _out.flush();
       }
   }
   
   
   
   public void add(String text)
   {
       if (_log) {
         _out.println(text);
         _out.flush(); 
       }
   }
   
}
