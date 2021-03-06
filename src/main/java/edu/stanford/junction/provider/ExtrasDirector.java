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


package edu.stanford.junction.provider;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeSet;

import org.json.JSONObject;

import edu.stanford.junction.api.activity.JunctionExtra;
import edu.stanford.junction.api.messaging.MessageHeader;

public class ExtrasDirector extends JunctionExtra {
	
	Comparator<JunctionExtra>mComparator = new Comparator<JunctionExtra>() {
		@Override
		public int compare(JunctionExtra o1, JunctionExtra o2) {
			return o1.getPriority().compareTo(o2.getPriority());
		}
	};
	TreeSet<JunctionExtra>mExtras = new TreeSet<JunctionExtra>(mComparator);
	
	/**
	 * Returns true if onMessageReceived should be called in the usual way.
	 * @param header
	 * @param message
	 * @return
	 */
	@Override
	public boolean beforeOnMessageReceived(MessageHeader header, JSONObject message) {
		// not available on android (API 4)
		/*
		Iterator<JunctionExtra>iter = mExtras.descendingIterator();
		while (iter.hasNext()) {
			JunctionExtra ex = iter.next();
			if (!ex.beforeOnMessageReceived(header, message))
				return false;
		}
		return true;
		*/
		
		Iterator<JunctionExtra>fwd = mExtras.iterator();
		ArrayList<JunctionExtra>list = new ArrayList<JunctionExtra>();
		while (fwd.hasNext()) {
			list.add(fwd.next());
		}
		for (int i=list.size()-1;i>=0;i--){
			JunctionExtra ex = list.get(i);
			if (!ex.beforeOnMessageReceived(header, message))
				return false;
		}
		
		return true;
	}
	
	@Override
	public void afterOnMessageReceived(MessageHeader header, JSONObject message) {
		// not available on android (API 4)
		/*
		Iterator<JunctionExtra>iter = mExtras.descendingIterator();
		while (iter.hasNext()) {
			JunctionExtra ex = iter.next();
			ex.afterOnMessageReceived(header, message);
		}
		*/
		
		Iterator<JunctionExtra>fwd = mExtras.iterator();
		ArrayList<JunctionExtra>list = new ArrayList<JunctionExtra>();
		while (fwd.hasNext()) {
			list.add(fwd.next());
		}
		for (int i=list.size()-1;i>=0;i--){
			JunctionExtra ex = list.get(i);
			ex.afterOnMessageReceived(header, message);
		}
	}
	
	@Override
	public boolean beforeSendMessageToActor(String actorID, JSONObject message) {
		Iterator<JunctionExtra>iter = mExtras.iterator();
		while (iter.hasNext()) {
			JunctionExtra ex = iter.next();
			if (!ex.beforeSendMessageToActor(actorID, message))
				return false;
		}
		return true;
	}
	
	@Override
	public boolean beforeSendMessageToRole(String role, JSONObject message) {
		Iterator<JunctionExtra>iter = mExtras.iterator();
		while (iter.hasNext()) {
			JunctionExtra ex = iter.next();
			if (!ex.beforeSendMessageToRole(role, message))
				return false;
		}
		return true;
	}
	
	@Override
	public boolean beforeSendMessageToSession(JSONObject message) {
		Iterator<JunctionExtra>iter = mExtras.iterator();
		while (iter.hasNext()) {
			JunctionExtra ex = iter.next();
			if (!ex.beforeSendMessageToSession(message))
				return false;
		}
		return true;
	}
	
	
	@Override
	public boolean beforeActivityJoin() {
		Iterator<JunctionExtra>iter = mExtras.iterator();
		while (iter.hasNext()) {
			JunctionExtra ex = iter.next();
			if (!ex.beforeActivityJoin())
				return false;
		}
		return true;
	}
	
	@Override
	public void afterActivityJoin() {
		Iterator<JunctionExtra>iter = mExtras.iterator();
		while (iter.hasNext()) {
			JunctionExtra ex = iter.next();
			ex.afterActivityJoin();
		}
	}
	
	@Override
	public boolean beforeActivityCreate() {
		Iterator<JunctionExtra>iter = mExtras.iterator();
		while (iter.hasNext()) {
			JunctionExtra ex = iter.next();
			if (!ex.beforeActivityCreate())
				return false;
		}
		return true;
	}
	
	@Override
	public void afterActivityCreate() {
		Iterator<JunctionExtra>iter = mExtras.iterator();
		while (iter.hasNext()) {
			JunctionExtra ex = iter.next();
			ex.afterActivityCreate();
		}
	}
	
	/**
	 * Adds an Extra to the set of executed extras.
	 * @param extra
	 */
	public void registerExtra(JunctionExtra extra) {
		mExtras.add(extra);
	}
	
	@Override
	public void updateInvitationParameters(Map<String, String> params) {
		Iterator<JunctionExtra>iter = mExtras.iterator();
		while (iter.hasNext()) {
			JunctionExtra ex = iter.next();
			ex.updateInvitationParameters(params);
		}
	}
}