/*
 * Copyright (C) 2010 Stanford University
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package edu.stanford.junction.provider.irc;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.util.Observer;
import java.util.List;
import java.util.Observable;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

import org.json.JSONException;
import org.json.JSONObject;

import edu.stanford.junction.api.activity.ActivityScript;
import edu.stanford.junction.api.activity.JunctionActor;
import edu.stanford.junction.api.messaging.MessageHeader;

import f00f.net.irc.martyr.InCommand;
import f00f.net.irc.martyr.commands.MessageCommand;
import f00f.net.irc.martyr.commands.NoticeCommand;
import f00f.net.irc.martyr.commands.JoinCommand;
import f00f.net.irc.martyr.services.AutoJoin;
import f00f.net.irc.martyr.services.AutoRegister;
import f00f.net.irc.martyr.services.AutoReconnect;
import f00f.net.irc.martyr.services.AutoResponder;
import f00f.net.irc.martyr.clientstate.ClientState;
import f00f.net.irc.martyr.IRCConnection;
import f00f.net.irc.martyr.util.FullNick;


public class Junction extends edu.stanford.junction.Junction {
	
	private final URI mAcceptedInvitation;
	private final String mSession;
	private ActivityScript mActivityScript;
	private IRCConnection mConnection;
	private FullNick mFullNick;
	private String mNickname;
	private ClientState mClientState;
	private MessageMultiplexer mMultiplex = new MessageMultiplexer();
	private MessageDemultiplexer mDemultiplex = new MessageDemultiplexer();

	public static String JX_NS = "jx";
	public static String JX_SYS_MSG = "jxsysmsg";

	// Stores responses to distributed activity script requests.
	private LinkedBlockingQueue<JSONObject> scriptQ = new LinkedBlockingQueue<JSONObject>();

	public static String makeIRCName(String name){
		String s = String.valueOf(name.hashCode());
		s = s.replace("-","0");
		s = "x" + s.substring(0, Math.min(s.length() - 1, 7));
		return s;
	}
	
	public Junction(URI uri, ActivityScript script, final JunctionActor actor) {
		this.setActor(actor);

		mNickname = makeIRCName(actor.getActorID());

		// User & name are not important for 
		// our needs. 
		String user = "jxuser";
		String name = "jxuser";

		mFullNick = new FullNick(mNickname + "!" + user + "@127.0.0.1");
		
		mAcceptedInvitation = uri;
		mActivityScript = script;
		mSession = "jxsession-" + makeIRCName(uri.getPath().substring(1));
		String host = uri.getHost();
		int port = uri.getPort();

		mClientState = new ClientState();
		mConnection = new IRCConnection( mClientState );

		mConnection.addStateObserver(new JXStateObserver());
		mConnection.addCommandObserver(new JXCommandObserver());

 		AutoJoin autoJoin = new AutoJoin( mConnection, "#" + mSession );
		AutoRegister autoReg = new AutoRegister( mConnection, mNickname, user, name);
		AutoReconnect autoRecon = new AutoReconnect( mConnection );
		AutoResponder autoRes = new AutoResponder( mConnection );

		autoRecon.go( host, port );
	}


	//   This observer is updated whenever a command
	// is received by the irc connection. 'update' is 
	// called from the irc thread. Therefore junction
	// is 'driven' (by way of triggerActorJoin 
	// and triggerMessageReceived) from the irc thread.
	//
	//   This is safe as long the irc thread is the ONLY 
	// thread sending updates into junction. Additionally,
	// the client must be aware that JunctionActor's methods
	// will be called on the irc thread.
	// 
	class JXCommandObserver implements Observer{
		public void update(Observable o, Object arg) {
			InCommand cmd = (InCommand)arg;
			if(cmd instanceof JoinCommand){
				JoinCommand c = (JoinCommand)cmd;
				if(c.weJoined(mClientState)){
					triggerActorJoin(mActivityScript == null || 
									 mActivityScript.isActivityCreator());
				}
			}
			else if(cmd instanceof MessageCommand){
				MessageCommand c = (MessageCommand)cmd;
				mDemultiplex.addFragment(c.getMessage(),c.getSource().getNick());
				List<MessageDemultiplexer.CompleteMessage> msgs = mDemultiplex.drainCompleteMessages();
				for(MessageDemultiplexer.CompleteMessage each : msgs){
					String from = each.from;
					String src = each.msg;
					try {
						JSONObject obj = new JSONObject(src);
						if(obj.optBoolean("scriptRequest")){
							handleScriptRequest(from, obj);
							return;
						}
						else if(obj.optBoolean("scriptResponse")){
							scriptQ.put(obj);
							return;
						}
						else if (obj.has(NS_JX)) {
							JSONObject header = obj.optJSONObject(NS_JX);
							if (header.has("targetRole")) {
								String target = header.optString("targetRole");
								if(!inLocalRoles(target)) return;
							}
						}
						MessageHeader header = new MessageHeader(Junction.this, obj, from);
						triggerMessageReceived(header, obj);
					} catch (Exception e) {
						System.err.println("Could not handle incoming message: " + src);
					}
				}
			}
		}
	}

	class JXStateObserver implements Observer{
		public void update(Observable o, Object arg) {
			System.out.println("State update: " + arg.toString());
		}
	}

	protected void handleScriptRequest(String from, JSONObject req){
		if(mActivityScript == null) return;
		try{
			JSONObject response = new JSONObject();
			response.put("scriptResponse", true);
			response.put("requestId", req.optString("requestId"));
			response.put("script", mActivityScript.getJSON());
			sendMessageToActor(from, response);
		}
		catch(JSONException e){
			e.printStackTrace(System.err);
		}
	}

	private Boolean inLocalRoles(String target){
		String[] roles = getActor().getRoles();
		for (int i = 0; i < roles.length; i++) {
			if (roles[i].equals(target)) {
				return true;
			}
		}
		return false;
	}

	
	@Override
	public void disconnect() {
		mConnection.disconnect();
	}

	@Override
	public URI getAcceptedInvitation() {
		return mAcceptedInvitation;
	}

	@Override
	public ActivityScript getActivityScript() {
		scriptQ.clear();
		JSONObject req = new JSONObject();
		String requestId = UUID.randomUUID().toString();
		try{
			req.put("scriptRequest", "true");
			req.put("requestId", requestId);
			sendMessageToSession(req);
		}
		catch(JSONException e){
			e.printStackTrace(System.err);
			return null;
		}
		try{
			int maxTries = 5;
			long maxWaitPerTry = 2000L;
			for(int i = 0; i < maxTries; i++){
				JSONObject response = scriptQ.poll(
					maxWaitPerTry, TimeUnit.MILLISECONDS);
				if(response == null) {
					return null;
				}
				else if(response.optString("requestId").equals(requestId)){
					JSONObject script = response.optJSONObject("script");
					return new ActivityScript(script);
				}
			}
		}
		catch(InterruptedException e){
			return null;
		}
		return null;
	}

	@Override
	public URI getBaseInvitationURI() {
		try {
			return new URI("junction://localhost#irc");
		} catch (Exception e) {
			return null;
		}
	}

	@Override
	public String getSessionID() {
		return mSession;
	}

	@Override
	public String getSwitchboard() {
		return mAcceptedInvitation.getHost();
	}

	private void sendMsgTo(String msg, String to){
		List<String> fragments = mMultiplex.divide(msg);
		for(String frag : fragments){
			MessageCommand cmd = new MessageCommand(mFullNick, to, frag);
			mConnection.sendCommand(cmd);
		}
	}

	@Override
	public void doSendMessageToActor(String actorID, JSONObject message) {
		sendMsgTo(message.toString(), actorID);
	}

	@Override
	public void doSendMessageToRole(String role, JSONObject message) {
		JSONObject jx;
		if (message.has(NS_JX)) {
			jx = message.optJSONObject(NS_JX);
		} else {
			jx = new JSONObject();
			try {
				message.put(NS_JX, jx);
			} catch (JSONException j) {}
		}
		try {
			jx.put("targetRole", role);
		} catch (Exception e) {}

		String msg = message.toString();
		sendMsgTo(msg, "#" + mSession + "," + mNickname);
	}

	@Override
	public void doSendMessageToSession(JSONObject message) {
		String msg = message.toString();
		sendMsgTo(msg, "#" + mSession + "," + mNickname);
	}
	
	
}