package edu.stanford.junction.provider.jx;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.json.JSONException;
import org.json.JSONObject;

import edu.stanford.junction.JunctionException;
import edu.stanford.junction.JunctionMaker;
import edu.stanford.junction.SwitchboardConfig;
import edu.stanford.junction.api.activity.ActivityScript;
import edu.stanford.junction.api.activity.JunctionActor;
import edu.stanford.junction.api.messaging.MessageHeader;
import edu.stanford.junction.provider.jx.json.JsonHandler;
import edu.stanford.junction.provider.jx.json.JsonSocketHandler;
import edu.stanford.junction.provider.jx.json.JsonWebSocketHandler;

public class JXServer {
	public static final int SERVER_PORT = 8283;
	private static final String TAG = "jx_server";
	private static final int BUFFER_LENGTH = 2048;
	
	private Map<RoomId, JSONObject> mActivityScripts;
	private Set<ConnectedThread> mConnections;
	private Map<RoomId, Map<String, ConnectedThread>> mSubscriptions;
	private AcceptThread mAcceptThread;
	
	public static void main(String[] argv) {
		final String TAG = "test";
		
		JXServer server = new JXServer();
		Log.d(TAG, "Starting server.");
		server.start();
		
		try {
			Thread.sleep(1000);
		} catch (InterruptedException e) {}
		
		final TestActor a1 = server.new TestActor("a1");
		final TestActor a2 = server.new TestActor("a2");
		final TestActor a3 = server.new TestActor("a3");
		a3.buddy = a2;
		
		
		final URI uri = URI.create("junction://localhost/jxserver#jx");
		final SwitchboardConfig cfg = JunctionMaker.getDefaultSwitchboardConfig(uri); 
		final ActivityScript script = new ActivityScript();
		script.setFriendlyName("Test Session");
		script.setActivityID("org.openjunction.test");
		
		boolean TEST_CLIENTS = true;
		if (TEST_CLIENTS) {
			try {
				Log.d(TAG, "Attempting to join session");
				JunctionMaker.getInstance(cfg).newJunction(uri, script, a1);
				JunctionMaker.getInstance(cfg).newJunction(uri, script, a2);
				JunctionMaker.getInstance(cfg).newJunction(uri, script, a3);
			} catch (JunctionException e) {
				Log.e(TAG, "error joining juction", e);
			}
		}
	}
	
	
	class TestActor extends JunctionActor {
		JunctionActor buddy;
		final String name;
		public TestActor(String name) {
			super("test");
			this.name = name;
		}
		
		@Override
		public void onMessageReceived(MessageHeader header, JSONObject message) {
			Log.d(TAG, name + " got: " + message.toString() + " !" + " from " + header.from);
		}
		
		@Override
		public void onActivityJoin() {
			super.onActivityJoin();
			Log.d(TAG, name + " joined session!");
			try {
				new Thread() {
					boolean loop = false;
					JSONObject hello = new JSONObject("{\"msg\":\"hello world! from: " + name + "\"}");
					public void run() {
						do {
							sendMessageToSession(hello);
							Log.d(TAG, name + " sent a message.");
							try {
								Thread.sleep(5000);
							} catch (Exception e) {}
						} while (loop);
					};
				}.start();
				
				if ("a3".equals(name)) {
					sendMessageToActor(buddy.getActorID(), new JSONObject("{\"psst\":\"hi\"}"));
					Log.d(TAG, name + " sent a secret message to " );
				}
			} catch (JSONException e) {
				e.printStackTrace();
			}
		}
		
		@Override
		public void onActivityCreate() {
			Log.d(TAG, "CREATED the session!!!");
		}
	};
	
	
	public JXServer() {
		
	}
	
	/**
	 * Starts a simple chat server, allowing users to
	 * connect to an arbitrary chat room.
	 */
	public void start() {
		mConnections = new HashSet<ConnectedThread>();
		mSubscriptions = new ConcurrentHashMap<RoomId, Map<String, ConnectedThread>>();
		mActivityScripts = new ConcurrentHashMap<RoomId, JSONObject>();
		mAcceptThread = new AcceptThread();
		mAcceptThread.start();
	}
	
	public void stop() {
		mAcceptThread.cancel();
		mAcceptThread = null;
		mConnections = null;
		
		mSubscriptions.clear();
		mSubscriptions = null;
		
		mActivityScripts.clear();
		mActivityScripts = null;
	}
	
	private class AcceptThread extends Thread {
        // The local server socket
        private final ServerSocket mmServerSocket;

        public AcceptThread() {
            ServerSocket tmp = null;
            
            // Create a new listening server socket
            try {
                tmp = new ServerSocket(SERVER_PORT);
            } catch (IOException e) {
                System.err.println("Could not open server socket");
                e.printStackTrace(System.err);
            }
            mmServerSocket = tmp;
        }

        public void run() {
            //Log.d(TAG, "BEGIN mAcceptThread" + this);
            setName("AcceptThread");
            Socket socket = null;

            // Listen to the server socket always
            while (true) {
                try {
                    // This is a blocking call and will only return on a
                    // successful connection or an exception
                	Log.d(TAG, "waiting for client...");
                    socket = mmServerSocket.accept();
                    Log.d(TAG, "Client connected!");
                } catch (SocketException e) {
                	
                } catch (IOException e) {
                    Log.e(TAG, "accept() failed", e);
                    break;
                }

                // If a connection was accepted
                if (socket == null) {
                	break;
                }
                
                //synchronized (JXServer.this) {
                    ConnectedThread conThread = new ConnectedThread(socket);
                    conThread.start();
                    mConnections.add(conThread);
                //}
            }
            Log.d(TAG, "END mAcceptThread");
        }

        public void cancel() {
            Log.d(TAG, "cancel " + this);
            try {
                mmServerSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "close() of server failed", e);
            }

            for (ConnectedThread conn : mConnections) {
        		conn.cancel();
            }
            mConnections.clear();
        }
    }
	
    /**
     * This thread runs during a connection with a remote device.
     * It handles all incoming and outgoing transmissions.
     */
    private class ConnectedThread extends Thread {
        private final Socket mmSocket;
        private final InputStream mmInStream;
        private final OutputStream mmOutStream;
        private Set<RoomOccupancy> mmSubscriptions;
        private JsonHandler mmJsonHelper;

        public ConnectedThread(Socket socket) {
            Log.d(TAG, "create ConnectedThread");

            mmSubscriptions = new HashSet<RoomOccupancy>();
            mmSocket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;

            try {
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            } catch (IOException e) {
                Log.e(TAG, "temp sockets not created", e);
            }

            mmInStream = tmpIn;
            mmOutStream = tmpOut;
        }

        public void run() {
            Log.d(TAG, "BEGIN mConnectedThread");
            byte[] buffer = new byte[BUFFER_LENGTH];
            int bytes;

            // Read header information, determine connection type
            try {
            	bytes = mmInStream.read(buffer);
            	String header = new String(buffer, 0, bytes);
            	
            	// determine request type
            	if (header.startsWith("GET ")) {
            		Log.d(TAG, "Found HTTP GET request");
            		doHttpConnection(header);
            	} else if (header.startsWith("JUNCTION")) {
            		Log.d(TAG, "Found Junction connection");
            		mmJsonHelper = new JsonSocketHandler(mmInStream, mmOutStream);
            		doJunctionConnection();
            	}
            } catch (IOException e) {
            	Log.e(TAG, "Error reading connection header", e);
            }
            
            // No longer listening.
            cancel();
        }
        
        private void doHttpConnection(String header) {
        	Log.d(TAG, "HTTP header:\n" + header);
        	String[] lines = header.split("\r\n");
        	boolean isWebSocket = false;
        	String origin = null;
        	String host = null;
        	String webSocketKey1 = null;
        	String webSocketKey2 = null;

        	for (String l : lines) {
        		if (l.startsWith("Upgrade: WebSocket")) {
        			isWebSocket = true;
        		}
        		if (l.startsWith("Sec-WebSocket-Key1: ")) {
        			webSocketKey1 = l.substring(20);
        		}
        		if (l.startsWith("Sec-WebSocket-Key2: ")) {
        			webSocketKey2 = l.substring(20);
        		}
        		
        		if (l.startsWith("Origin: ")) {
        			origin = l.substring(8);
        		}
        		
        		if (l.startsWith("Host: ")) {
        			host = l.substring(6);
        		}
        	}
        	
			if (!isWebSocket) {
				Log.e(TAG, "Not a websocket request");
				return;
			}

			long v1 = getKeyNumber(webSocketKey1);
			long v2 = getKeyNumber(webSocketKey2);

			int s1 = countSpaces(webSocketKey1);
			int s2 = countSpaces(webSocketKey2);

			if (v1 % s1 != 0 || v2 % s2 != 0) {
				Log.e(TAG, "WebSocket failed handshake");
			}
			
			long p1 = v1 / s1;
			long p2 = v2 / s2;
			String p3 = header.substring(header.length()-8);
			byte[] response = webSocketResponse(p1, p2, p3);
			String endpoint = "ws://" + host + "/"; // TODO
			
			this.write("HTTP/1.1 101 Web Socket Protocol Handshake\r\n");
			this.write("Upgrade: WebSocket\r\n");
			this.write("Connection: Upgrade\r\n");
			this.write("Sec-WebSocket-Origin: " + origin + "\r\n");
			this.write("Sec-WebSocket-Location: " + endpoint + "\r\n");
			this.write("\r\n");
			this.write(response, response.length);
			try {
				mmOutStream.flush();
			} catch (IOException e) {
				Log.e(TAG, "Error completing handshake", e);
				return;
			}

			// TODO: support length prefixed data?
			mmJsonHelper = new JsonWebSocketHandler(mmInStream, mmOutStream);
			doJunctionConnection();
        }
        
        private byte[] webSocketResponse(long p1, long p2, String p3) {
        	try {
        		byte[] challenge = new byte[16];
        		challenge[0] = (byte)( p1 >>> 24 );
        		challenge[1] = (byte)( (p1 << 8) >>> 24 );
        		challenge[2] = (byte)( (p1 << 16) >>> 24 );
        		challenge[3] = (byte)( (p1 << 24) >>> 24 );
        		
        		challenge[4] = (byte)( p2 >>> 24 );
        		challenge[5] = (byte)( (p2 << 8) >>> 24 );
        		challenge[6] = (byte)( (p2 << 16) >>> 24 );
        		challenge[7] = (byte)( (p2 << 24) >>> 24 );
        		
        		System.arraycopy(p3.getBytes(), 0, challenge, 8, 8);
        		
        		MessageDigest md = MessageDigest.getInstance("MD5");
        		byte[] resp = md.digest(challenge);
        		return resp;
        	} catch (Exception e) {
        		Log.e(TAG, "Error computing response to websocket challenge", e);
        		return null;
        	}
        }
        
        private long getKeyNumber(String key) {
        	long n = 0;
        	for (int i = 0; i < key.length(); i++) {
        		char c = key.charAt(i);
        		if ('0' <= c && c <= '9') {
        			n = 10*n + (c-'0'); 
        		}
        	}
        	return n;
        }
        
        private int countSpaces(String key) {
        	int n = 0;
        	for (int i = 0; i < key.length(); i++) {
        		char c = key.charAt(i);
        		if (c == ' ') n++;
        	}
        	return n;
        }
        
        private void doJunctionConnection() {
        	
            // Keep listening to the InputStream while connected
            while (true) {
                try {
                    JSONObject json = mmJsonHelper.jsonFromStream();
                    if (json == null) {
                    	break;
                    }
                    handleJson(json);
                } catch (IOException e) {
                    Log.e(TAG, "disconnected", e);
                    //connectionLost();
                    break;
                }
            }
        }
                
        private void handleJson(JSONObject json) {
        	try {
        		Log.d(TAG, json.toString());
        		
	        	if (json.has(Junction.NS_JX)) {
	            	JSONObject jx = json.getJSONObject(Junction.NS_JX);
	            	if (jx.has(Junction.JX_SYS_MSG)) {
	                	JSONObject sys = jx.getJSONObject(Junction.JX_SYS_MSG);
	                	// Join
	                	if (sys.has("join")) {
	                		boolean isCreator = false;
	                		
	                		String roomName = sys.getString("join");
	                		RoomId joinRoom = getRoomId(roomName);
	                		String me = sys.getString("id");
	                		synchronized (joinRoom) {
		                		//Log.d(TAG, "Adding " + me.substring(0,6) + " to " + room);
		                		mmSubscriptions.add(new RoomOccupancy(joinRoom, me));
		                		
		                		Map<String, ConnectedThread> participants;
		                		if (mSubscriptions.containsKey(joinRoom)) {
		                			// Joining existing session
		                			participants = mSubscriptions.get(joinRoom);
		                			isCreator = false;
		                		} else {
		                			// New session
		                			isCreator = true;
		                			participants = new HashMap<String, ConnectedThread>();
		                			mSubscriptions.put(joinRoom, participants);
		                			
		                			JSONObject script = sys.optJSONObject("script");
		                			if (script != null) {
			                			mActivityScripts.put(joinRoom, script);
		                			}
		                		}
		                		
		                		participants.put(me, this);
		                		
		                		// Response
		                		JSONObject script = null;
                				JSONObject joinedObj = new JSONObject();
        	                    JSONObject joinedMsg = new JSONObject();
        	                    try {
        		                    joinedObj.put(Junction.JX_SYS_MSG, true);
        		                    joinedObj.put(Junction.JX_JOINED, true);
        		                    joinedObj.put(Junction.JX_CREATOR, isCreator);
        		                    if (isCreator) {
        		                    	script = mActivityScripts.get(joinRoom);
        		                    	if (script != null) {
        		                    		joinedObj.put(Junction.JX_SCRIPT, script);
        		                    	}
        		                    }
        		                    joinedMsg.put(Junction.NS_JX, joinedObj);
        		                    mmJsonHelper.sendJson(joinedMsg);
        	                    } catch (Exception e) {
        	                    	Log.e(TAG, "Error sending join response",e);
        	                    }
	                		}
	                	}
	                	
	                	// Send message to session
	                	String action = sys.optString("action");
	                	if ("send_s".equals(action)) {
	                		String session = sys.getString("session");
	                		RoomId room = getRoomId(session);
	                		jx.remove(Junction.JX_SYS_MSG);
	                		
	                		synchronized(room) {
	                			Map<String, ConnectedThread> peers = mSubscriptions.get(room);
	                			for (String u : peers.keySet()) {
	                				ConnectedThread conn = peers.get(u);
	                				conn.sendJson(json);
	                			}
	                		}
	                	}
	                	
	                	if ("send_a".equals(action)) {
	                		String session = sys.getString("session");
	                		RoomId room = getRoomId(session);
	                		String actor = sys.getString("actor");
	                		jx.remove(Junction.JX_SYS_MSG);
	                		
	                		synchronized(room) {
	                			ConnectedThread conn = mSubscriptions.get(room).get(actor);
	                			if (conn != null) {
	                				conn.sendJson(json);
	                			}
	                		}
	                	}
	            	}
	        	}
        	} catch (JSONException e) {
        		Log.e(TAG, "Error building json object", e);
        	}
        }

        public void sendJson(JSONObject json) {
        	try {
        		mmJsonHelper.sendJson(json);
        	} catch (Exception e) {
        		Log.e(TAG, "Error writing JSON", e);
        	}
        }
        
        /**
         * Write to the connected OutStream.
         * @param buffer  The bytes to write
         */
        public void write(byte[] buffer, int bytes) {
            try {
                mmOutStream.write(buffer, 0, bytes);
            } catch (IOException e) {
                Log.e(TAG, "Exception during write", e);
            }
        }
        
        public void write(String buffer) {
        	try {
        		byte[] b = buffer.getBytes();
                mmOutStream.write(b, 0, b.length);
            } catch (IOException e) {
                Log.e(TAG, "Exception during write", e);
            }
        }

        public void cancel() {
            try {
            	//synchronized(JXServer.this) {
	            	for (RoomOccupancy entry : mmSubscriptions) {
	            		synchronized(entry.room) {
		            		Map<String, ConnectedThread> users = mSubscriptions.get(entry.room);
		            		users.remove(entry.id);
		            		if (users.size() == 0) {
		            			mSubscriptions.remove(entry.room);
		            		}
	            		}
	            	}
	            	mmSubscriptions.clear();
	            	mmSubscriptions = null;
            	//}
                mmSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "close() of connect socket failed", e);
            }
        }
    }
	
    public class RoomOccupancy {
    	public RoomId room;
    	public String id;
    	
    	public RoomOccupancy(RoomId r, String me) {
    		room = r;
    		id = me;
    	}
    }
    
    /**
     * Use a wrapper class so we can better trust locks
     */
    public class RoomId {
    	public String name;
    	
    	private RoomId(String name) {
    		this.name = name;
    	}
    }
    
    Map<String,RoomId> mRoomMap = new HashMap<String,RoomId>();
    public RoomId getRoomId(String name) {
    	if (!mRoomMap.containsKey(name)) {
    		mRoomMap.put(name, new RoomId(name));
    	}
    	return mRoomMap.get(name);
    }
    
	public static class Log {
		public static void d(String tag, String msg) {
			System.out.println(tag + ": " + msg);
		}
		
		public static void e(String tag, String msg) {
			System.err.println(tag + ": " + msg);
		}
		
		public static void e(String tag, String msg, Exception e) {
			System.err.println(tag + ": " + msg);
			e.printStackTrace(System.err);
		}
	}
}
