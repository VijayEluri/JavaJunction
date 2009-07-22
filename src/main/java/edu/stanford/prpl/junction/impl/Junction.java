package edu.stanford.prpl.junction.impl;

import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jivesoftware.smack.Chat;
import org.jivesoftware.smack.MessageListener;
import org.jivesoftware.smack.PacketListener;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smack.XMPPException;
import org.jivesoftware.smack.filter.PacketFilter;
import org.jivesoftware.smack.filter.PacketTypeFilter;
import org.jivesoftware.smack.packet.IQ;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.Packet;
import org.jivesoftware.smackx.Form;
import org.jivesoftware.smackx.FormField;
import org.jivesoftware.smackx.muc.MultiUserChat;
import org.json.JSONObject;

import edu.stanford.prpl.junction.api.activity.ActivityDescription;
import edu.stanford.prpl.junction.api.activity.JunctionActor;
import edu.stanford.prpl.junction.api.messaging.JunctionMessage;
import edu.stanford.prpl.junction.api.messaging.MessageHandler;
import edu.stanford.prpl.junction.api.messaging.MessageHeader;

public class Junction implements edu.stanford.prpl.junction.api.activity.Junction {
	private ActivityDescription mActivityDescription;
	private JunctionActor mOwner;
	private XMPPConnection xmppConnection;
	private URL mHostURL;
	private Map<String,String>mPeers; // actorID to Role

	private String mXMPPServer;
	private XMPPConnection mXMPPConnection;
	private MultiUserChat mSessionChat;
	
	/**
	 * Creates a new activity and registers it
	 * with a Junction server.
	 * 
	 * TODO: add constructor w/ activity descriptor; keep this one for nonconstrained activity.
	 */
	protected Junction(ActivityDescription desc) {
		mActivityDescription=desc;
		mXMPPServer=mActivityDescription.getHost();
		init();
	}
	
	private void init() {
		mXMPPConnection= new XMPPConnection(mActivityDescription.getHost());
		try {
			mXMPPConnection.connect();
			mXMPPConnection.loginAnonymously();
			
			String mSessionChatString = mActivityDescription.getSessionID()+"@conference."+mXMPPServer;
			mSessionChat = new MultiUserChat(mXMPPConnection, mSessionChatString);

			System.out.println("Joining " + mSessionChatString);
			if (mActivityDescription.isActivityOwner()) {
				try {
					// TODO: is this an error? is there really a notion of ownership?
					mSessionChat.create(mActivityDescription.getActorID());
					mSessionChat.sendConfigurationForm(new Form(Form.TYPE_SUBMIT));
				} catch (XMPPException e) {
					try {
						mSessionChat.join(mActivityDescription.getActorID());
					} catch (XMPPException e2) {
						System.err.println("could not join or create room. ");
						e2.printStackTrace();
					}
				}
			} else {
				mSessionChat.join(mActivityDescription.getActorID());
			}
			
			mSessionChat.addMessageListener(new PacketListener() {
				@Override
				public void processPacket(Packet packet) {
					System.out.println("got packet: " + packet.toXML());
				}
			});
			
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	public String getActivityID() {
		return mActivityDescription.getActivityID();
	}
	
	
	public void registerActor(final JunctionActor actor) {
		System.out.println("adding actor for role " + actor.getRole());
		mOwner = actor;
		mOwner.setJunction(this);
		
		MessageHandler handler = actor.getMessageHandler();
		if (handler != null) {
			registerMessageHandler(handler);
		}
		
	}
	
	
	// TODO: use a URL for the service endpoint? (query == service)
	public void requestService(String role, URL host, String serviceName) {
		System.out.println("inviting actor for role " + role);
		
		Map<String,Object>message = new HashMap<String, Object>();
		//message.put("sessionID",getSessionID());
		//message.put("activityHost",mHostURL);
		message.put("activityURL", getInvitationURL(role));
		message.put("serviceName", serviceName);
		//mManager.publish("/srv/ServiceFactory", message);
	}
	
	
	
	public void start() {
		Map<String,String>go = new HashMap<String,String>();
		try {
			mSessionChat.sendMessage(go.toString());
		} catch (XMPPException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	

	
	
	class OnStartListener extends MessageHandler {
		private boolean started=false;
		public void onMessageReceived(MessageHeader header, JSONObject message) {
			
			/*for (JunctionActor actor : mActors) {
					actor.onActivityStart();
			}*/
			mOwner.onActivityStart();
			started=true;
			// mManager.removeListener(this);
		}
	}



	public List<String> getActorsForRole(String role) {
		// inefficient but who cares. small map.
		List<String>results = new ArrayList<String>();
		Set<String>keys = mPeers.keySet();
		for (String key : keys) {
			if (mPeers.get(key).equals(role)) {
				results.add(key);
			}
		}
		return results;
	}

	public List<String> getRoles() {
		// TODO Auto-generated method stub
		return null;
	}

	public String getSessionID() {
		return mActivityDescription.getSessionID();
	}

	public void registerMessageHandler(final MessageHandler handler) {
		PacketListener packetListener = new PacketListener() {
			@Override
			public void processPacket(Packet packet) {
				Message message = (Message)packet;
				System.out.println("got message " + message.toXML());
				
				JSONObject obj = null;
				try {
					obj = new JSONObject(message.getBody());
				} catch (Exception e) {
					System.out.println("Could not convert to json: " + message.getBody());
					//e.printStackTrace();
					return;
				}
				handler.onMessageReceived(null, obj);
			}
		};
		mXMPPConnection.addPacketListener(packetListener, new PacketTypeFilter(Message.class));
	}

	public void sendMessageToActor(String actorID, JSONObject message) {
	
		try {
			Chat chat = mSessionChat.createPrivateChat(mSessionChat.getRoom()+"/"+actorID,
					null);
		
			chat.sendMessage(message.toString());
		} catch (XMPPException e) {
			e.printStackTrace();
		}
	}
	
	public void sendMessageToRole(String role, JSONObject message) {
		//mManager.publish(mManager.channelForRole(role), message);
	}

	public void sendMessageToSession(JSONObject message) {
		try {
			mSessionChat.sendMessage(message.toString());
		} catch (XMPPException e) {
			e.printStackTrace();
		}
		
	}
	
	
/*
	public void sendMessageToChannel(String channel, JunctionMessage message) {
		mManager.publish(channel, message);
		
	}
*/
	/*
	protected void sendMessageToSystem(JunctionMessage message) {
		mManager.publish(mManager.channelForSystem(), message);
	}*/

	public URL getInvitationURL() {
		URL invitation = null;
		try {
			// TODO: strip query part from hostURL
			invitation = new URL(mHostURL.toExternalForm() + "?session=" + URLEncoder.encode(getSessionID(),"UTF-8"));
		} catch (Exception e) {
			e.printStackTrace();
		}

		return invitation;
	}

	public URL getInvitationURL(String requestedRole) {
		URL invitation = null;
		try {
			// TODO: strip query part from hostURL
			invitation = new URL(mHostURL.toExternalForm() + "?session=" + URLEncoder.encode(getSessionID(),"UTF-8") + "&requestedRole=" + URLEncoder.encode(requestedRole,"UTF-8"));
		} catch (Exception e) {
			e.printStackTrace();
		}

		return invitation;
	}
	
}

