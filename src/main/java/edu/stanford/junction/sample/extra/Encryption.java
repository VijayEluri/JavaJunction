package edu.stanford.junction.sample.extra;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.json.JSONObject;

import edu.stanford.junction.JunctionMaker;
import edu.stanford.junction.SwitchboardConfig;
import edu.stanford.junction.api.activity.JunctionExtra;
import edu.stanford.junction.api.messaging.MessageHeader;
import edu.stanford.junction.provider.xmpp.XMPPSwitchboardConfig;

public class Encryption extends JunctionExtra {
	/**
	 * If an invitation is accepted, auto-detect whether
	 * to use encryption via a parameter "aes=[key]" in the invitation.
	 */
	public final static String FIELD_ENC = "e";
	public final static String URL_KEY_PARAM = "skey";
	
	private Cipher mCipher = null;
	private SecretKeySpec mKeySpec = null;
	protected byte[] mKey = null;

	public Encryption() {
		
	}
	
	private Encryption(byte[] key) {
		mKey=key;
		init();
	}
	
	private void init() {
		try {
			mCipher = Cipher.getInstance("AES");
			mKeySpec = new SecretKeySpec(mKey, "AES");
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	public static void main(String[] args) {
		SwitchboardConfig config = new XMPPSwitchboardConfig("prpl.stanford.edu");
		JunctionMaker jm = JunctionMaker.getInstance(config);
		
		// TODO: add JunctionExtra.addInvitationParams(Map<String,String>());
		
		//jm.newJunction(desc, actor)
	}
	
	public static void encTest() throws Exception {
		KeyGenerator kgen = KeyGenerator.getInstance("AES");
		kgen.init(128);
		SecretKey skey = kgen.generateKey();
		
		Encryption encryption = new Encryption(skey.getEncoded());
		
	    JSONObject testObj = new JSONObject("{\"msg\":\"testing!\"}");
	    
	    System.out.println("before encryption: " + testObj.toString());
	    encryption.beforeSendMessage(testObj);
	    System.out.println("after encryption: " + testObj.toString());
	    
	    encryption.beforeOnMessageReceived(null, testObj);
	    System.out.println("after decryption: " + testObj.toString());
	}
	
	
	@Override
	public boolean beforeActivityJoin() {
		// TODO: probably better to have mCreated or something.
		if (mKey != null) return true;
		
		try {
			URI invite = getActor().getJunction().getAcceptedInvitation();
			if (invite != null) {
				String params = invite.getQuery();
				QueryString qs = new QueryString(params);
				String b64key = qs.getParameter(URL_KEY_PARAM);
				if (b64key == null) return true;
				
				mKey = Base64Coder.decode(b64key);
				init();
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		
		return true;
	}
	
	@Override
	public boolean beforeActivityCreate() {
		try {
			KeyGenerator kgen = KeyGenerator.getInstance("AES");
			kgen.init(128);
			SecretKey skey = kgen.generateKey();
			
			mKey = skey.getEncoded();
			init();
		} catch (Exception e) {
			e.printStackTrace();
		}
		
		return true;
	}
	
	/**
	 * Encrypts a message before sending it over the wire.
	 */
	@Override
	public synchronized boolean beforeSendMessage(JSONObject msg) {
		if (mKeySpec == null) return true;
		
		try {
			String msgStr = msg.toString();
			
			mCipher.init(Cipher.ENCRYPT_MODE, mKeySpec);
			
			
			byte[] enc = null;
			enc = mCipher.doFinal(msgStr.getBytes());
			String encStr = new String(Base64Coder.encode(enc));
			
			// clear object
			Iterator<String>keys = msg.keys();
			while (keys.hasNext()) {
				msg.remove(keys.next());
			}
			msg.put(FIELD_ENC,encStr);
		} catch (Exception e) {
			e.printStackTrace();
		}
		
		
		return true;
	}
	
	/**
	 * Decrypts an inbound message before handing it to the activity developer.
	 */
	@Override
	public synchronized boolean beforeOnMessageReceived(MessageHeader h, JSONObject msg) {
		if (mKeySpec == null) return true;
		
		try {
			if (msg.has(FIELD_ENC)) {
				String b64 = msg.getString(FIELD_ENC);
				byte[] dec = Base64Coder.decode(b64);
			
				mCipher.init(Cipher.DECRYPT_MODE, mKeySpec);
				
				byte[] res = mCipher.doFinal(dec);
				JSONObject obj = new JSONObject(new String(res));
				
				msg.remove("e");
				Iterator<String> keys = obj.keys();
				while (keys.hasNext()) {
					String key = keys.next();
					msg.put(key, obj.get(key));
				}
			}
		
		} catch (Exception e) {
			e.printStackTrace();
		}
		return true;
	}
	
	/**
	 * Low priority so we don't interfere with other extras
	 */
	@Override
	public Integer getPriority() {
		return 3;
	}
}



class QueryString {

	 private Map<String, List<String>> parameters;

	 public QueryString(String qs) {
	  parameters = new TreeMap<String, List<String>>();

	  // Parse query string
	     String pairs[] = qs.split("&");
	     for (String pair : pairs) {
	            String name;
	            String value;
	            int pos = pair.indexOf('=');
	            // for "n=", the value is "", for "n", the value is null
	         if (pos == -1) {
	          name = pair;
	          value = null;
	         } else {
	       try {
	        name = URLDecoder.decode(pair.substring(0, pos), "UTF-8");
	              value = URLDecoder.decode(pair.substring(pos+1, pair.length()), "UTF-8");            
	       } catch (UnsupportedEncodingException e) {
	        // Not really possible, throw unchecked
	           throw new IllegalStateException("No UTF-8");
	       }
	         }
	         List<String> list = parameters.get(name);
	         if (list == null) {
	          list = new ArrayList<String>();
	          parameters.put(name, list);
	         }
	         list.add(value);
	     }
	 }

	 public String getParameter(String name) {        
	  List<String> values = parameters.get(name);
	  if (values == null)
	   return null;

	  if (values.size() == 0)
	   return "";

	  return values.get(0);
	 }

	 public String[] getParameterValues(String name) {        
	  List<String> values = parameters.get(name);
	  if (values == null)
	   return null;

	  return (String[])values.toArray(new String[values.size()]);
	 }

	 public Enumeration<String> getParameterNames() {  
	  return Collections.enumeration(parameters.keySet()); 
	 }

	 public Map<String, String[]> getParameterMap() {
	  Map<String, String[]> map = new TreeMap<String, String[]>();
	  for (Map.Entry<String, List<String>> entry : parameters.entrySet()) {
	   List<String> list = entry.getValue();
	   String[] values;
	   if (list == null)
	    values = null;
	   else
	    values = (String[]) list.toArray(new String[list.size()]);
	   map.put(entry.getKey(), values);
	  }
	  return map;
	 } 
	}