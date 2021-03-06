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


package edu.stanford.junction.api.activity;

import java.net.URI;
import java.util.Iterator;

import org.json.JSONArray;
import org.json.JSONObject;
import edu.stanford.junction.JunctionMaker;

@Deprecated
public class ActivityScript {
	
	/**
	 * Note that there is probably a lot of confusing code in here
	 * due to our redefinition of the Activity Description.
	 * 
	 *  The new setup:: there is an ActivityDescription that details
	 *  roles / platforms / codebases, and a SessionDescription,
	 *  that specifies sessionID, switchboard, and role.
	 *  
	 *  Both of these constructs can be used to instantiate an Actor,
	 *  one by creating a new session and one by joining a previously
	 *  created one.
	 */
	
	// JSON representation
	private JSONObject mJSON = null;
	
	// session tokens
	// TODO: Remove this stuff from this class
	private String sessionID;
	private String host;
	
	private String activityID;
	private String friendlyName;
	public String getFriendlyName() {
		return friendlyName;
	}

	public void setFriendlyName(String friendlyName) {
		this.friendlyName = friendlyName;
		mJSON=null; // reset
	}

	private JSONObject roleSpecs;
	
	private boolean generatedSessionID=false;
	
	public ActivityScript() {
		generatedSessionID=true;
	}
	
	public void setUri(URI uri) {
		sessionID = JunctionMaker.getSessionIDFromURI(uri);
		host = uri.getHost();
	}
	
	public ActivityScript(JSONObject json) {
		mJSON = json;
		
		
		// TODO: Deprecate. These should not be in the activityDescription.
		// preferred
		if (json.has("switchboard")) {
			host = json.optString("switchboard");
		}
		// deprecated
		else if (json.has("host")) {
			host = json.optString("host");
		}
		
		// TODO: rename this field
		if (json.has(("ad"))) {
			activityID = json.optString("ad");
		}
		
		if (json.has("friendlyName")) {
			friendlyName=json.optString("friendlyName");
		}
		
		if (json.has("sessionID")) {
			sessionID = json.optString("sessionID");
		}
		
		////////////////////////////////////////////
		roleSpecs = json.optJSONObject("roles");
			
	}
	
	
	public boolean isActivityCreator() {
		return generatedSessionID;
	}
	
	public void isActivityCreator(boolean is) {
		this.generatedSessionID=is;
	}
	
	public String getSessionID() {
		return sessionID;
	}
	public void setSessionID(String sessionID) {
		this.sessionID = sessionID;
	}
	public String getHost() {
		return host;
	}
	public void setHost(String host) {
		this.host = host;
	}
	public String getActivityID() {
		return activityID;
	}
	public void setActivityID(String activityID) {
		this.activityID = activityID;
		mJSON=null; // reset
	}
	
	@Override
	public String toString() {
		return getJSON().toString();
	}
	
	public JSONObject getJSON() {
		// hack until the object is populated correctly
		if (mJSON != null) {
			return mJSON;
		}
		
		JSONObject j = new JSONObject();
		try {
			j.put("sessionID", sessionID);
			j.put("switchboard",host);
			if (roleSpecs != null) {
				j.put("roles",roleSpecs);
			}
			if (friendlyName != null) {
				j.put("friendlyName", friendlyName);
			}
			if (activityID != null) {
				j.put("ad", activityID);
			}
		} catch (Exception e) {}
		
		mJSON=j;
		return j;
	}
	
	public String[] getRoles() {
		if (roleSpecs == null) return new String[]{};
		String[] roles = new String[roleSpecs.length()];
		
		try {
			Iterator<String>iter = roleSpecs.keys();
			int i=0;
			while (iter.hasNext()) {
				roles[i++] = iter.next();
			}
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
		
		return roles;
	}
	
	public JSONObject getRoleSpec(String role) {
		if (roleSpecs == null) return null;
		if (roleSpecs.has(role)) {
			try {
				return roleSpecs.getJSONObject(role);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		return null;
	}
	
	public JSONObject getRolePlatform(String role, String platform) {
		try {
			JSONObject spec = getRoleSpec(role);
			if (spec == null) return null;
			spec = spec.optJSONObject("platforms");
			
			if (spec == null) return null;
			if (spec.has(platform)) {
				return spec.getJSONObject(platform);
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		return null;
	}
	
	/*
	 * Role/Platform like: "player" : { platforms: { "web" : { ... } } }
	 */
	public void addRolePlatform(String role, String platform, JSONObject platformSpec) {
		try {
			if (roleSpecs==null) roleSpecs = new JSONObject();
			if (!roleSpecs.has(role)) {
				roleSpecs.put(role, new JSONObject());
			}

			JSONObject jsonRole = roleSpecs.getJSONObject(role);
			if (!jsonRole.has("platforms")) {
				jsonRole.put("platforms", new JSONObject());
			}
			
			JSONObject jsonPlatforms = jsonRole.getJSONObject("platforms");
			jsonPlatforms.put(platform, platformSpec);

			mJSON=null; // reset
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	public void addRoleHint(String role, String hint) {
		try {
			if (roleSpecs==null) roleSpecs = new JSONObject();
			if (!roleSpecs.has(role)) {
				roleSpecs.put(role, new JSONObject());
			}

			JSONObject jsonRole = roleSpecs.getJSONObject(role);
			if (!jsonRole.has("hints")) {
				jsonRole.put("hints", new JSONArray());
			}
			
			JSONArray jsonHints = jsonRole.getJSONArray("hints");
			jsonHints.put(hint);

			mJSON=null; // reset
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	public void setRoles(JSONObject roles) {
		roleSpecs=roles;
	}
}
