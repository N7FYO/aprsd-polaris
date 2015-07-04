 /* 
 * Copyright (C) 2015 by LA7ECA, Øyvind Hanssen (ohanssen@acm.org)
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 */
 
package no.polaric.aprsd;
import java.util.*;
import java.util.regex.*;
import java.io.*;
import se.raek.charset.*;
import java.text.*;
import java.lang.reflect.Constructor; 


/**
 * Channel for sending/receiving APRS data. 
 */
public abstract class Channel extends Source implements Serializable, ManagedObject
{
     private static final long HRD_TIMEOUT = 1000 * 60 * 40; /* 40 minutes */
     private static boolean _logPackets = false; 
     
     transient protected LinkedHashMap<String, Heard> _heard = new LinkedHashMap();
    
     /* Statistics */
     transient protected long _heardPackets, _duplicates, _sent;        

     /* State. RUNNING if running ok. */
 
     public enum State {
         OFF, STARTING, RUNNING, FAILED
     }
     transient protected State _state = State.OFF;


   
     /** Return true if service is running */
     public boolean isActive() {
        return _state == State.STARTING || _state == State.RUNNING;
     }
 
 
     
     /**
      * Abstract factory for Channel objects. 
      */
      
     /* FIXME: Consider writing the interface spec */

     public static class Manager {
         private HashMap<String, String>  _classes = new HashMap();
         private HashMap<String, Channel> _instances = new LinkedHashMap();
         private Set<String> _backups = new HashSet();
         
         
         /**
          * Register a channel class. 
          * @param tname A short name for the class/type.
          * @param cls The full name of the class.
          */
         public void addClass(String tname, String cls)
         {
            _classes.put(tname, cls);
         }

         
         /**
          * Instantiate a channel.
          * @param api
          * @param tname A short name for the type. See addClass method.
          * @param id A short name for the channel instance to allow later lookup.  
          */
         public Channel newInstance(ServerAPI api, String tname, String id)
         {
            try {
               String cname = _classes.get(tname); 
               if (cname == null)
                  return null; // Or throw exception??
               Class<Channel> cls = (Class<Channel>) Class.forName(cname);
               Constructor<Channel> constr = (Constructor<Channel>) cls.getConstructors()[0];
               Channel  c = constr.newInstance(api, id);
               _instances.put(id, c);
               return c;
            }
            catch (Exception e) {
               e.printStackTrace(System.out);
               return null; 
            }
         }
         
         
         /**
          * Get the full set of channel names. Keys for lookup.
          */
         public Set<String> getKeys()
           { return _instances.keySet(); }
           
           
         /**
          * Look up a channel from a name.
          */
         public Channel get(String id)
           { return _instances.get(id); }
           
           
         /**
          * Return true if the named channel is a backup-channel
          */
         public boolean isBackup(String id)
            { return _backups.contains(id); }
         
         
         public void addBackup(String id)
            { _backups.add(id); }
     }

       
    

     /**
      * Information about APRS packet heard on the channel. 
      */ 
     protected static class Heard {
         public Date time; 
         public String path;
         public Heard(Date t, String p)
           { time = t; path = p;}
     }
     
     
     
     public static void init(ServerAPI api) {
        _logPackets = api.getBoolProperty("channel.logpackets", true);
     }
     
     
     public State getState() 
        { return _state; }
     
     private void removeOldHeardEntries()
     {
          Iterator<Heard> it = _heard.values().iterator();
          Date now = new Date();
          while (it.hasNext()) {
              Date x = it.next().time;
              if (now.getTime() > x.getTime() + HRD_TIMEOUT)
                 it.remove();
              else
                 return;
          }
     }

    

    /**
     * Interface for receivers of APRS packets.
     */
    public interface Receiver {
        /** Receive an APRS packet. 
         * @param p AprsPacket content.
         * @param dup Set to true to indicate that this packet is a duplicate.
         */
        public void receivePacket(AprsPacket p, boolean dup);
    }


    
    private DateFormat df = new SimpleDateFormat("dd MMM yyyy HH:mm:ss",
           new DateFormatSymbols(new Locale("no")));
           
           
    /* 
     * Perhaps we should use a list of receivers in a later version, but for now
     * two is sufficient: A parser and an igate.
     */
    transient private List<Receiver> _rcv = new LinkedList<Receiver>(); 
    transient protected PrintWriter  _out = null; 
    protected String _rfilter = null;

    public static DupCheck  _dupCheck = new DupCheck();
    public static final String _rx_encoding = /* "X-UTF-8_with_cp-850_fallback"; */
                                              "X-UTF-8_with_ISO-8859-1_fallback";
                          
    public static final String _tx_encoding = "UTF-8";

    
    protected void logNote(String txt)
      { System.out.println("*** [" + getShortDescr()+"] " + txt ); }
    
    
    
    public abstract void close(); 
    
    
    /**
      * Returns true if callsign is heard on the channel.
      */
    public boolean heard(String call)
     {
         removeOldHeardEntries();
         return _heard.containsKey(call);
     }
         
  
    /**
     * Returns the path (digipeater) of the last heard packet from the given callsign.
     */
    public String heardPath(String call)
     {
         Heard x = _heard.get(call);
         if (x==null)
            return null;
         else
            return x.path;
     }
     
    /**
     * Reverse the order of the elements of the path. 
     */ 
    public static String getReversePath(String path)
    {
       String result = "";
       Stack<String> st = new Stack<String>();
       String[] p = path.split(",");
       boolean dflag = false;
       for(String x : p) {       
          if (x.length() < 1)
             break;
          if (x.charAt(x.length()-1) == '*') {
             x = x.substring(0, x.length()-1);
             dflag = true;
          }
          else if (dflag) 
             break;
          if (!x.matches("(WIDE|TRACE|NOR|SAR|(TCP[A-Z0-9]{2})|NOGATE|RFONLY|NO_TX).*")) 
             st.push(x);   
       }
       if (dflag) 
          while (!st.empty())
            result += (st.pop() + ",");
       return result.length() == 0 ? "" : result.substring(0,result.length()-1);
    }    
    
    
    
   /**
    * Return a string that presents a packet as a third party report. 
    */
    public static String thirdPartyReport(AprsPacket p)
      { return thirdPartyReport(p, null); }
      

      
    /**
     * Return a string that presents a packet as a third party report. 
     * @param p The packet
     * @param path Digi-path to be used in the thirdparty report. If null, we will 
     *     use the path of the original packet. 
     */
    public static String thirdPartyReport(AprsPacket p, String path)
    { 
       if (path == null) 
          path = ((p.via_orig != null && p.via_orig.length() > 0) ? ","+p.via_orig : "");
       else 
          path = ","+path; 
       return "}" + p.from + ">" + p.to + path + ":" + p.report + "\r";
    }
       
       
     
   /**
     * Number of stations heard.
     */     
    public int nHeard()
       { return _heard.keySet().size(); }
       
    public long nHeardPackets()
       { return _heardPackets; }

    public long nDuplicates()
       { return _duplicates; }

    public long nSentPackets()
       { return _sent; }
       
    public PrintWriter getWriter()
       { return _out; }

    
    public abstract void sendPacket(AprsPacket p);
    
    
    /**
     * Configure receivers. 
     */
    public void addReceiver(Receiver r)
       { if (r != null) _rcv.add(r); }
    
    public void removeReceiver(Receiver r)
       { if (r != null) _rcv.remove(r); }   
    
    

    
    
    /**
     * Do some preliminary parsing of report part of the packet. 
     * Return null if report is invalid. 
     */
    protected AprsPacket checkReport(AprsPacket p) 
    {   
         p.report = p.report.replace('\uffff', ' ');
         if (p.report.length() <= 1)
            return null;
         p.type = p.report.charAt(0); 
         
         if (p.report.charAt(p.report.length()-1) == '\r')
           p.report = p.report.substring(0, p.report.length()-1);
         if (p.type == '}') {
            /* Special treatment for third-party type. 
             * Strip off type character and apply this function recursively
             * on the wrapped message. 
             */
             p = AprsPacket.fromString(p.report.substring(1, p.report.length()));
             if (p != null) 
                p.thirdparty = true; 
             else
                return null;
               
          }
          else if (p.type == ':' || p.type == ';') 
             /* Special treatment for message type or object
              * Extract recipient/object id
              */
             p.msgto = p.report.substring(1,10).trim();
         return p;
    }
    
    

    protected abstract void regHeard(AprsPacket p);
    
    
    
    /**
     * Process incoming packet. 
     * To be called from subclass. Parses packet, updates heard table, checks for
     * duplicates and if all is ok, deliver packet to receivers. It also applies
     * an optional receive filter. 
     * @param packet String representation of packet. 
     * @param dup True if packet is known to be a duplicate.
     * @return true if accepted
     */
    protected boolean receivePacket(String packet, boolean dup)
    { 
       if (packet == null || packet.length() < 1)
          return false; 
       AprsPacket p = AprsPacket.fromString(packet);

       return receivePacket(p, dup);
    }
    
    
    
    /**
     * Process incoming packet. 
     * To be called from subclass. Parses packet, updates heard table, checks for
     * duplicates and if all is ok, deliver packet to receivers.
     * @param p Pre-parsed packet.
     * @param dup True if packet is known to be a duplicate.
     * @return true if accepted.
     */
    protected boolean receivePacket(AprsPacket p, boolean dup)
    {      
       if (p == null)
          return false; 
      
       p = checkReport(p); 
       if (p==null)
          return false;
       
       if (_rfilter != null && !_rfilter.equals("") && !p.toString().matches(_rfilter))
          return false; 
          
       p.source = this;
       if (_logPackets)
          System.out.println(df.format(new Date()) + " ["+getShortDescr()+"] "+p);
       _heardPackets++;
       dup = _dupCheck.checkPacket(p.from, p.to, p.report);
       if (!dup) 
          /* Register heard, only for first instance of packet, not duplicates */
          regHeard(p);
       else
          _duplicates++;
       for (Receiver r: _rcv)
           r.receivePacket(p, dup);
       return !dup;
    }
    
    
    public String toString() { return "Channel"; }
    
}

