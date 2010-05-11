package edu.stanford.junction.props;
import org.json.JSONObject;
import org.json.JSONException;
import java.util.Vector;
import java.util.UUID;
import java.util.Random;
import edu.stanford.junction.api.activity.JunctionExtra;
import edu.stanford.junction.api.messaging.MessageHeader;


/**
 * TODO: 
 * state numbers should be long, not int
 * don't say 'nonce', say 'uuid'
 *
 */
public abstract class Prop extends JunctionExtra {
	public static final int MODE_NORM = 1;
	public static final int MODE_SYNC = 2;
	public static final int MSG_STATE_OPERATION = 1;
	public static final int MSG_STATE_SYNC = 2;
	public static final int MSG_WHO_HAS_STATE = 3;
	public static final int MSG_I_HAVE_STATE = 4;
	public static final int MSG_SEND_ME_STATE = 5;
	public static final int MSG_PLZ_CATCHUP = 6;

	public static final int PLZ_CATCHUP_THRESHOLD = 5;

	private String uuid = UUID.randomUUID().toString();
	private String propName;
	private String propReplicaName = "";
	private IPropState state;
	private long stateNumber = 0;
	private IPropState ackState;
	private long ackStateNumber = 0;
	private int mode = MODE_NORM;
	private long staleness = 0;
	private String lastAckedOpUUID = "";
	private long syncNonce = 0;
	private boolean waitingForIHaveState = false;
	private Vector<IStateOperationMsg> incomingBuffer = new Vector<IStateOperationMsg>();
	private Vector<IStateOperationMsg> unAckedPredictionsBuffer = new Vector<IStateOperationMsg>();
	private Vector<HistoryMAC> verifyHistory = new Vector<HistoryMAC>();
	private Vector<IPropChangeListener> changeListeners = new Vector<IPropChangeListener>();


	public Prop(String propName, IPropState state, String propReplicaName){
		this.propName = propName;
		this.ackState = state;
		this.state = ackState.copy();
		this.propReplicaName = propReplicaName;
	}

	public Prop(String propName, IPropState state){
		this(propName, state, propName + "-replica" + UUID.randomUUID().toString());
	}

	public long getStaleness(){
		return staleness;
	}

	public IPropState getState(){
		return state;
	}

	public long getStateNumber(){
		return stateNumber;
	}

	protected long getNextStateNumber(){
		return getStateNumber() + 1;
	}

	protected String getPropName(){
		return propName;
	}

	protected void signalStateUpdate(){}

	protected void logInfo(String s){
		System.out.println("prop@" + propReplicaName + ": " + s);
	}

	protected void logErr(String s){
		System.err.println("prop@" + propReplicaName + ": " + s);
	}

	protected HistoryMAC newHistoryMAC(){
		return new HistoryMAC(ackStateNumber, this.ackState.hash());
	}

	abstract protected IPropState destringifyState(String s);
	abstract protected IPropStateOperation destringifyOperation(String s);

	public void addChangeListener(IPropChangeListener listener){
		changeListeners.add(listener);
	}

	protected void dispatchChangeNotification(String evtType, Object o){
		for(IPropChangeListener l : changeListeners){
			logInfo("Dispatching change notification...");
			l.onChange(evtType, o);
		}
	}

	protected void applyOperation(IStateOperationMsg msg, boolean notify, boolean localPrediction){
		IPropStateOperation op = msg.getOp();
		if(localPrediction){
			// apply predicted operation immediately
			logInfo("Applying local prediction: " + msg);
			this.state = state.applyOperation(op);
			this.stateNumber += 1;
			logInfo("Now at predicted state:  " + this.stateNumber + "\n");
		}
		else{
			logInfo("Applying remote message: " + msg);
			// Otherwise, this msg has been serialized by the router,
			// and may acknowledge a set of msgs in the prediction buffer.
			
			// Apply all these to ackState (they've already been applied to 
			// predicted state. Removing them from the local prediction buffer.

			// Actually we just keep those that are NOT acknowledged..
			boolean isPredictionAck = false;
			Vector<IStateOperationMsg> stillUnAcked = new Vector<IStateOperationMsg>();
			for(int i = 0; i < this.unAckedPredictionsBuffer.size(); i++){
				IStateOperationMsg m = this.unAckedPredictionsBuffer.get(i);
				if(m.getPredictedStateNumber() <= msg.getAckStateNumber()){
					logInfo("Acknowledging: " + m);
					this.ackState = ackState.applyOperation(m.getOp());
					this.ackStateNumber += 1;
					this.lastAckedOpUUID = m.getUUID();

					// While we're at it, note whether msg is an ack for
					// a prediction we made..
					if(m.getUUID().equals(msg.getUUID())) isPredictionAck = true;
				}
				else{
					stillUnAcked.add(m);
				}
			}
			this.unAckedPredictionsBuffer = stillUnAcked;

			// If msg is a prediction ack for a message we sent, skip this part!
			//
			// Otherwise transform this msg (and all remaining msgs in the prediction buffer,
			// and apply the transformed message to the predicted state..
			if(!isPredictionAck){
				logInfo("Is not prediction ack.. ");
				if(this.unAckedPredictionsBuffer.isEmpty()){
					logInfo("..prediction buffer empty. Apply it!");
					this.ackState = ackState.applyOperation(msg.getOp());
					this.ackStateNumber += 1;
					this.lastAckedOpUUID = msg.getUUID();

					this.state = state.applyOperation(msg.getOp());
					this.stateNumber += 1;

					assert (this.ackStateNumber == this.stateNumber);

					logInfo("Now at predicted state: " + this.stateNumber + ", ack state: " + this.ackStateNumber + "\n");
				}
				else{
					IStateOperationMsg transformed = msg;
					for(int i = 0; i < this.unAckedPredictionsBuffer.size(); i++){
						IStateOperationMsg m = this.unAckedPredictionsBuffer.get(i);
						logInfo("..transforming with: " + m);
						Pair<IStateOperationMsg, IStateOperationMsg> p= this.doOT(transformed, m);
						transformed = p.getFirst();
						this.unAckedPredictionsBuffer.set(i, p.getSecond());
					}
					this.state = state.applyOperation(transformed.getOp());
					this.stateNumber += 1;

					assert (this.stateNumber > this.ackStateNumber);

					logInfo("Finished applying transformations. Now at predicted state: " + this.stateNumber + ", ack state: " + this.ackStateNumber + "\n");
				}
			}

			// Do some book-keeping for history debugging..
			this.verifyHistory.insertElementAt(newHistoryMAC(), 0);
			Vector<HistoryMAC> newHistory = new Vector<HistoryMAC>();
			for(int i = 0; i < verifyHistory.size() && i < 10; i++){
				newHistory.add(verifyHistory.get(i));
			}
			this.verifyHistory = newHistory;
		}
		if(notify){
			dispatchChangeNotification("change", null);
		}
	}

	protected Pair<IStateOperationMsg, IStateOperationMsg> doOT(IStateOperationMsg m1, IStateOperationMsg m2){
		return new Pair(m1, m2);
	}

	protected void checkHistory(HistoryMAC mac){
		for(HistoryMAC m : this.verifyHistory){
			if(m.stateNumber == mac.stateNumber){
				if(!(m.stateHash.equals(mac.stateHash))){
					logErr("Invalid state!" + 
						   m + " vs " + 
						   mac + 
						   " broadcasting Hello to flush out newbs..");
					sendHello();
				}
			}
		}
	}

	protected void exitSYNCMode(){
		logInfo("Exiting SYNC mode");
		this.mode = MODE_NORM;
		this.syncNonce = -1;
	}

	protected void enterSYNCMode(long desiredStateNumber){
		logInfo("Entering SYNC mode.");
		this.mode = MODE_SYNC;
		Random rng = new Random();
		this.syncNonce = rng.nextLong();
		sendMessageToProp(new WhoHasStateMsg(desiredStateNumber, this.syncNonce));
		this.waitingForIHaveState = true;
	}

	protected boolean isSelfMsg(IPropMsg msg){
		return msg.getSenderReplicaUUID().equals(this.uuid);
	}

	protected void handleMessage(MessageHeader header, JSONObject rawMsg){
		int msgType = rawMsg.optInt("type");
		String fromActor = header.getSender();
		switch(mode){
		case MODE_NORM:
			switch(msgType){
			case MSG_STATE_OPERATION: {
				StateOperationMsg msg = new StateOperationMsg(rawMsg, this);
				if(msg.getAckStateNumber() > ackStateNumber){
					// Check whether the remote peer has more acknowledged state
					// than we do..
					logInfo("Buffering FIRST " + 
							msg + "from " + 
							msg.getSenderReplicaName() + 
							". Currently at " + 
							getStateNumber());
					enterSYNCMode(msg.getAckStateNumber());
					this.incomingBuffer.add(msg);
				}
				else {
					// Note, operation may have been created WRT to stale state.
					//
					// It's not unsound to apply the operation - but we
					// hope that sender will eventually notice it's own
					// staleness and SYNC.

					// Send them a hint if things get too bad..
					if(!isSelfMsg(msg) && 
					   (ackStateNumber - msg.getAckStateNumber()) > PLZ_CATCHUP_THRESHOLD){
						logInfo("I'm at " + ackStateNumber + ", they're at " + msg.getAckStateNumber() + ". " + 
								"Sending catchup.");
						sendMessageToPropReplica(fromActor, new PlzCatchUpMsg(ackStateNumber));
					}

					if(isSelfMsg(msg)){
						this.staleness = (ackStateNumber - msg.getAckStateNumber());
					}

					applyOperation(msg, true, false);

					if(!isSelfMsg(msg)){
						checkHistory(msg.mac);
					}
				}
				break;
			}
			case MSG_WHO_HAS_STATE:{
				WhoHasStateMsg msg = new WhoHasStateMsg(rawMsg);
				if(!isSelfMsg(msg)){
					logInfo("Got who has state..");
					// Can we fill the gap for this peer?
					if(ackStateNumber >= msg.desiredStateNumber){
						logInfo("Sending IHaveState..");
						sendMessageToPropReplica(
							fromActor, 
							new IHaveStateMsg(ackStateNumber, msg.syncNonce));
					}
					else{
						logInfo("Oops! got state request for state i don't have!");
					}
				}
				break;
			}
			case MSG_SEND_ME_STATE:{
				SendMeStateMsg msg = new SendMeStateMsg(rawMsg);
				if(!isSelfMsg(msg)){
					logInfo("Got SendMeState");
					// Can we fill the gap for this peer?
					if(ackStateNumber >= msg.desiredStateNumber){
						StateSyncMsg sync = new StateSyncMsg(
							ackStateNumber, 
							this.ackState.stringify(),
							msg.syncNonce,
							this.lastAckedOpUUID);
						logInfo("Sending state sync msg: " + sync);
						sendMessageToPropReplica(fromActor, sync);
					}
				}
				break;
			}
			case MSG_PLZ_CATCHUP:{
				PlzCatchUpMsg msg = new PlzCatchUpMsg(rawMsg);
				if(!isSelfMsg(msg)){
					// Some peer is trying to tell us we are stale.
					// Do we believe them?
					logInfo("Got PlzCatchup : " + msg);
					if(msg.ackStateNumber > ackStateNumber) {
						enterSYNCMode(msg.ackStateNumber);
					}
				}
				break;
			}
			case MSG_STATE_SYNC:
				break;
			case MSG_I_HAVE_STATE:
				break;
			default:
				logErr("NORM mode: Unrecognized message, "  + rawMsg);
			}
			break;
		case MODE_SYNC:
			switch(msgType){
			case MSG_STATE_OPERATION:{
				StateOperationMsg msg = new StateOperationMsg(rawMsg, this);
				if(msg.ackStateNumber <= ackStateNumber && this.incomingBuffer.isEmpty()){
					// We're in sync-mode, but it looks like we're up to date. 
					// Go back to NORM mode.
					applyOperation(msg, true, false);
					checkHistory(msg.mac);
					exitSYNCMode();
				}
				else{
					// Message is on far side of gap, buffer it.
					logInfo("Buffering " + msg + 
							" from " + msg.getSenderReplicaName() + 
							". Currently acknowledged at " + ackStateNumber);
					this.incomingBuffer.add(msg);
				}
				break;
			}
			case MSG_I_HAVE_STATE:{
				IHaveStateMsg msg = new IHaveStateMsg(rawMsg);
				if(!isSelfMsg(msg) && this.waitingForIHaveState){
					logInfo("Got IHaveState");
					if(msg.syncNonce == this.syncNonce && msg.ackStateNumber > ackStateNumber){
						logInfo("Requesting state");
						this.waitingForIHaveState = false;
						sendMessageToPropReplica(fromActor, new SendMeStateMsg(msg.ackStateNumber, msg.syncNonce));
					}
				}
				break;
			}
			case MSG_STATE_SYNC:{
				StateSyncMsg msg = new StateSyncMsg(rawMsg);
				if(!isSelfMsg(msg)){
					// First check that this sync message corresponds to this
					// instance of SYNC mode. This is critical for assumptions
					// we make about the contents of incomingBuffer...
					if(msg.syncNonce != this.syncNonce){
						logInfo("Bogus SYNC nonce! ignoring StateSyncMsg");
					}
					else{
						logInfo("Got StateSyncMsg:" + msg.state);

						this.lastAckedOpUUID = msg.lastAckedOpUUID;
						this.ackState = destringifyState(msg.state);
						this.ackStateNumber = msg.ackStateNumber;
						this.state = ackState.copy();
						this.stateNumber = ackStateNumber;

						// We may have applied some predictions locally.
						// Just forget all these predictions (we're wiping our
						// local state completely. Any straggler ACKS originating
						// from this peer will be interpreted as non-predictions (thus applied
						// at all peers equally).
						// 
						this.unAckedPredictionsBuffer.clear();


						if(this.incomingBuffer.isEmpty()){

							// If local peer has buffered no operations, we know that no operations
							// were received from remote peers since the creation of state. We can safely assume
							// the given state.

							logInfo("No buffered items.. sync state applied.");
							dispatchChangeNotification("change", null);
						}
						else{
							// Otherwise, we've buffered some remote messages.
							//
							// Since we started buffering before we sent the WhoHasState request, 
							// it must be that we've buffered all operation messages with ackStateNums >= that of
							// state.
							// 

							// It's possible that ALL buffered messages are already incorportated
							// into the received state. In that case want to ignore buffered items and 
							// just assume state.

							// Otherwise, there is some tail of buffered operations that occurred after
							// the state was captured. We find this tail by comparing the uuid
							// of each buffered operation with that of the last operation incorporated
							// into state. 

							for(int i = 0; i < this.incomingBuffer.size(); i++){
								IStateOperationMsg m = this.incomingBuffer.get(i);
								if(m.getUUID() == msg.lastAckedOpUUID){
									logInfo("Found uuid match in buffered messages!");
									for(int j = i + 1; j < this.incomingBuffer.size(); j++){
										IStateOperationMsg mj = this.incomingBuffer.get(j); 
										applyOperation(mj, false, false);
									}
									break;
								}
							}
							this.incomingBuffer.clear();
							exitSYNCMode();
							dispatchChangeNotification("change", null);
						}
					}
				}
				break;
			}
			default:
				logErr("SYNC mode: Unrecognized message, "  + rawMsg);
			}
			break;
		}

		signalStateUpdate();
	}

	/**
	 * Returns true if the normal event handling should proceed;
	 * Return false to stop cascading.
	 */
	public boolean beforeOnMessageReceived(MessageHeader h, JSONObject msg) { 
		if(msg.optString("propTarget").equals(getPropName())){
			handleMessage(h, msg);
			return false;
		}
		else{
			return true; 
		}
	}

	/**
	 * Send a hello message to all prop-replicas in this prop.
	 * The hello message is a state operation that does not affect
	 * the state. It serves to initiate conversation when all peers
	 * are at state 0.
	 */
	protected void sendHello(){
		sendMessageToProp(new HelloMsg(
							  getNextStateNumber(), 
							  ackStateNumber,
							  new NullOp(), 
							  newHistoryMAC()));
	}


	/**
	 * Add an operation to the state managed by this Prop
	 */
	synchronized public void addOperation(IPropStateOperation operation){
		HistoryMAC mac = newHistoryMAC();
		StateOperationMsg msg = new StateOperationMsg(
			getNextStateNumber(), 
			ackStateNumber,
			operation, 
			mac);
		sendMessageToProp(msg);

		logInfo(propReplicaName + " sending " + msg + 
				". Current MAC: " + mac);
	}

	/**
	 * Add an operation to the state managed by this Prop, with prediction
	 */
	synchronized public void addPredictedOperation(IPropStateOperation operation){
		HistoryMAC mac = newHistoryMAC();
		StateOperationMsg msg = new StateOperationMsg(
			getNextStateNumber(),
			ackStateNumber,
			operation,
			mac);
		applyOperation(msg, true, true);
		unAckedPredictionsBuffer.add(msg);
		sendMessageToProp(msg);

		logInfo(propReplicaName + " predicting and sending " + msg + 
				". Current MAC: " + mac);
	}


	/**
	 * Send a message to all prop-replicas in this prop
	 */
	protected void sendMessageToProp(PropMsg msg){
		JSONObject m = msg.toJSONObject();
		try{
			m.put("propTarget", getPropName());
			m.put("senderReplicaUUID", uuid);
			m.put("senderReplicaName", propReplicaName);
		}catch(JSONException e){}
		getActor().sendMessageToSession(m);
	}


	/**
	 * Send a message to the prop-replica hosted at the given actorId.
	 */
	protected void sendMessageToPropReplica(String actorId, PropMsg msg){
		JSONObject m = msg.toJSONObject();
		try{
			m.put("propTarget", getPropName());
			m.put("senderReplicaUUID", uuid);
			m.put("senderReplicaName", propReplicaName);
		}catch(JSONException e){}
		getActor().sendMessageToActor(actorId, m);
	}
    
	
	public void afterActivityJoin() {
		sendHello();
	}


	public static class Pair<T, S>{
		private T first;
		private S second;
		public Pair(T f, S s){ 
			first = f;
			second = s;   
		}
		public T getFirst(){
			return first;
		}
		public S getSecond() {
			return second;
		}
		public String toString(){ 
			return "(" + first.toString() + ", " + second.toString() + ")"; 
		}
	}

	abstract class PropMsg implements IPropMsg{
		protected String senderReplicaUUID = "";
		protected String senderReplicaName = "";

		public PropMsg(){}

		public PropMsg(JSONObject obj){
			this.senderReplicaUUID = obj.optString("senderReplicaUUID");
			this.senderReplicaName = obj.optString("senderReplicaName");
		}

		abstract public JSONObject toJSONObject();

		public String toString(){
			return toJSONObject().toString();
		}

		public String getSenderReplicaUUID(){
			return senderReplicaUUID;
		}

		public String getSenderReplicaName(){
			return senderReplicaName;
		}
	}

	class StateOperationMsg extends PropMsg implements IStateOperationMsg{
		protected long predictedStateNumber;
		protected long ackStateNumber;
		protected String uuid;
		protected IPropStateOperation operation;
		protected HistoryMAC mac;

		public StateOperationMsg(JSONObject msg, Prop prop){
			super(msg);
			this.uuid = msg.optString("uuid");
			this.predictedStateNumber = msg.optLong("predStateNum");
			this.ackStateNumber = msg.optLong("ackStateNum");
			this.mac = new HistoryMAC(msg.optLong("macStateNum"),
									  msg.optString("macStateHash"));
			this.operation = prop.destringifyOperation(msg.optString("operation"));
		}

		public StateOperationMsg(long predictedStateNumber, long ackStateNum, IPropStateOperation operation, HistoryMAC mac){
			this.uuid = UUID.randomUUID().toString();
			this.predictedStateNumber = predictedStateNumber;
			this.ackStateNumber = ackStateNum;
			this.mac = mac;
			this.operation = operation;
		}

		public String getUUID(){
			return this.uuid;
		}
		public long getAckStateNumber(){
			return this.ackStateNumber;
		}
		public long getPredictedStateNumber(){
			return this.predictedStateNumber;
		}
		public HistoryMAC getHistoryMAC(){
			return this.mac;
		}
		public IPropStateOperation getOp(){
			return this.operation;
		}

		public JSONObject toJSONObject(){

			JSONObject obj = new JSONObject();
			try{
				obj.put("type", MSG_STATE_OPERATION);
				obj.put("uuid", uuid);
				obj.put("ackStateNum", ackStateNumber);
				obj.put("predStateNum", predictedStateNumber);
				obj.put("macStateNum", mac.stateNumber);
				obj.put("macStateHash", mac.stateHash);
				obj.put("operation", operation.stringify());
			}catch(JSONException e){}
			return obj;
		}

	}

	class HelloMsg extends StateOperationMsg{
		public HelloMsg(JSONObject msg, Prop prop){
			super(msg, prop);
		}
		public HelloMsg(long predictedStateNumber, long ackStateNum, IPropStateOperation operation, HistoryMAC mac){
			super(predictedStateNumber, ackStateNum, operation, mac);
		}
	}

	class StateSyncMsg extends PropMsg{
		public long ackStateNumber;
		public String state;
		public long syncNonce;
		public String lastAckedOpUUID;
		public StateSyncMsg(JSONObject msg){
			super(msg);
			ackStateNumber = msg.optLong("ackStateNumber");
			state = msg.optString("state");
			syncNonce = msg.optLong("syncNonce");
			lastAckedOpUUID = msg.optString("lastAckedOpUUID");
		}
		public StateSyncMsg(long ackStateNumber, String state, long syncNonce, String lastAckedOpUUID){
			this.ackStateNumber = ackStateNumber;
			this.state = state;
			this.syncNonce = syncNonce;
			this.lastAckedOpUUID = lastAckedOpUUID;
		}
		public JSONObject toJSONObject(){
			JSONObject obj = new JSONObject();
			try{
				obj.put("type", MSG_STATE_SYNC);
				obj.put("ackStateNumber", ackStateNumber);
				obj.put("state", state);
				obj.put("syncNonce", syncNonce);
				obj.put("lastAckedOpUUID", lastAckedOpUUID);
			}catch(JSONException e){}
			return obj;
		}

	}

	class WhoHasStateMsg extends PropMsg{
		public long desiredStateNumber;
		public long syncNonce;
		public WhoHasStateMsg(JSONObject msg){
			super(msg);
			desiredStateNumber = msg.optLong("desiredStateNumber");
			syncNonce = msg.optLong("syncNonce");
		}
		public WhoHasStateMsg(long desiredStateNumber, long syncNonce){
			this.desiredStateNumber = desiredStateNumber;
			this.syncNonce = syncNonce;
		}
		public JSONObject toJSONObject(){
			JSONObject obj = new JSONObject();
			try{
				obj.put("type", MSG_WHO_HAS_STATE);
				obj.put("desiredStateNumber", desiredStateNumber);
				obj.put("syncNonce", syncNonce);
			}catch(JSONException e){}
			return obj;
		}
	}


	class IHaveStateMsg extends PropMsg{
		public long ackStateNumber;
		public long syncNonce;
		public IHaveStateMsg(JSONObject msg){
			super(msg);
			ackStateNumber = msg.optLong("ackStateNumber");
			syncNonce = msg.optLong("syncNonce");
		}
		public IHaveStateMsg(long ackStateNumber, long syncNonce){
			this.ackStateNumber = ackStateNumber;
			this.syncNonce = syncNonce;
		}
		public JSONObject toJSONObject(){
			JSONObject obj = new JSONObject();
			try{
				obj.put("type", MSG_I_HAVE_STATE);
				obj.put("ackStateNumber", ackStateNumber);
				obj.put("syncNonce", syncNonce);
			}catch(JSONException e){}
			return obj;
		}
	}

	class SendMeStateMsg extends PropMsg{
		public long desiredStateNumber;
		public long syncNonce;
		public SendMeStateMsg(JSONObject msg){
			super(msg);
			desiredStateNumber = msg.optLong("desiredStateNumber");
			syncNonce = msg.optLong("syncNonce");
		}
		public SendMeStateMsg(long desiredStateNumber, long syncNonce){
			this.desiredStateNumber = desiredStateNumber;
			this.syncNonce = syncNonce;
		}
		public JSONObject toJSONObject(){
			JSONObject obj = new JSONObject();
			try{
				obj.put("type", MSG_SEND_ME_STATE);
				obj.put("desiredStateNumber", desiredStateNumber);
				obj.put("syncNonce", syncNonce);
			}catch(JSONException e){}
			return obj;
		}
	}

	class PlzCatchUpMsg extends PropMsg{
		public long ackStateNumber;
		public PlzCatchUpMsg(JSONObject msg){
			super(msg);
			ackStateNumber = msg.optLong("ackStateNumber");
		}
		public PlzCatchUpMsg(long ackStateNumber){
			this.ackStateNumber = ackStateNumber;
		}
		public JSONObject toJSONObject(){
			JSONObject obj = new JSONObject();
			try{
				obj.put("type", MSG_PLZ_CATCHUP);
				obj.put("ackStateNumber", ackStateNumber);
			}catch(JSONException e){}
			return obj;
		}
	}


}
