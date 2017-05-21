package com.peersafe.base.client;

import static com.peersafe.base.client.requests.Request.VALIDATED_LEDGER;

import java.net.URI;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.peersafe.chainsql.util.Util;
import com.peersafe.base.client.enums.Command;
import com.peersafe.base.client.enums.Message;
import com.peersafe.base.client.enums.RPCErr;
import com.peersafe.base.client.pubsub.Publisher;
import com.peersafe.base.client.requests.Request;
import com.peersafe.base.client.requests.Request.Manager;
import com.peersafe.base.client.responses.Response;
import com.peersafe.base.client.subscriptions.ServerInfo;
import com.peersafe.base.client.subscriptions.SubscriptionManager;
import com.peersafe.base.client.subscriptions.TrackedAccountRoot;
import com.peersafe.base.client.subscriptions.TransactionSubscriptionManager;
import com.peersafe.base.client.transactions.AccountTxPager;
import com.peersafe.base.client.transactions.TransactionManager;
import com.peersafe.base.client.transport.TransportEventHandler;
import com.peersafe.base.client.transport.WebSocketTransport;
import com.peersafe.base.client.types.AccountLine;
import com.peersafe.base.core.coretypes.AccountID;
import com.peersafe.base.core.coretypes.Issue;
import com.peersafe.base.core.coretypes.STObject;
import com.peersafe.base.core.coretypes.hash.Hash256;
import com.peersafe.base.core.coretypes.uint.UInt32;
import com.peersafe.base.core.types.known.sle.LedgerEntry;
import com.peersafe.base.core.types.known.sle.entries.AccountRoot;
import com.peersafe.base.core.types.known.sle.entries.Offer;
import com.peersafe.base.core.types.known.tx.result.TransactionResult;
import com.peersafe.base.crypto.ecdsa.IKeyPair;
import com.peersafe.base.crypto.ecdsa.Seed;

public class Client extends Publisher<Client.events> implements TransportEventHandler {
    // Logger
    public static final Logger logger = Logger.getLogger(Client.class.getName());

    // Events
    public static interface events<T> extends Publisher.Callback<T> {}
    public static interface OnLedgerClosed extends events<ServerInfo> {}
    public static interface OnConnected extends events<Client> {}
    public static interface OnDisconnected extends events<Client> {}
    public static interface OnSubscribed extends events<ServerInfo> {}
    public static interface OnMessage extends events<JSONObject> {}
    public static interface OnTBMessage extends events<JSONObject> {}
    public static interface OnTXMessage extends events<JSONObject> {}
    public static interface OnSendMessage extends events<JSONObject> {}
    public static interface OnStateChange extends events<Client> {}
    public static interface OnPathFind extends events<JSONObject> {}
    public static interface OnValidatedTransaction extends events<TransactionResult> {}
    public static interface OnReconnecting extends events<JSONObject> {}
    public static interface OnReconnected extends events<JSONObject> {}

    /**
     * Trigger when a transaction validated.
     * @param cb
     * @return
     */
    public Client onValidatedTransaction(OnValidatedTransaction cb) {
        on(OnValidatedTransaction.class, cb);
        return this;
    }

    /**
     * Trigger when ledger closed
     * @param cb
     * @return
     */
	public Client onLedgerClosed(OnLedgerClosed cb) {
        on(OnLedgerClosed.class, cb);
        return this;
    }
	
	/**
	 * Trigger when transaction related to a subscribed table validate_success or db_success.
	 * @param cb
	 * @return
	 */
	public Client OnTBMessage(OnTBMessage cb) {
        on(OnTBMessage.class, cb);
        return this;
    }
	/**
	 * Trigger when a subscribed  transaction validate_success or db_success.
	 * @param cb
	 * @return
	 */
	public Client OnTXMessage(OnTXMessage cb) {
        on(OnTXMessage.class, cb);
        return this;
    }

	/**
	 * Trigger when websocket message received.
	 * @param cb
	 * @return
	 */
	public Client OnMessage(OnMessage cb) {
        on(OnMessage.class, cb);
        return this;
    } 
	
	/**
	 * Trigger when reconnecting to a server begins.
	 * @param cb
	 * @return
	 */
	public Client onReconnecting(OnReconnecting cb){
        on(OnReconnecting.class, cb);
        return this;
	}
	
	/**
	 * Trigger when reconnect to a server succeed.
	 * @param cb
	 * @return
	 */
	public Client onReconnected(OnReconnected cb){
        on(OnReconnected.class, cb);
        return this;
	}
	
	/**
	 * Trigger when websocket connection succeed.
	 * @param onConnected
	 * @return
	 */
    public Client onConnected(OnConnected onConnected) {
        this.on(OnConnected.class, onConnected);
        return this;
    }

    /**
     * Trigger when websocket connection disconnected.
     * @param cb
     * @return
     */
    public Client onDisconnected(OnDisconnected cb) {
        on(OnDisconnected.class, cb);
        return this;
    }

    // ### Members
    // The implementation of the WebSocket
    WebSocketTransport ws;

    /**
     * When this is non 0, we randomly disconnect when trying to send messages
     * See {@link Client#sendMessage}
     */
    public double randomBugsFrequency = 0;
    Random randomBugs = new Random();
    // When this is set, all transactions will be routed first to this, which
    // will then notify the client
    TransactionSubscriptionManager transactionSubscriptionManager;

    // This is in charge of executing code in the `clientThread`
    protected ScheduledExecutorService service;
    // All code that use the Client api, must be run on this thread

    /**
     See {@link Client#run}
     */
    protected Thread clientThread;
    protected TreeMap<Integer, Request> requests = new TreeMap<Integer, Request>();

    // Keeps track of the `id` doled out to Request objects
    private int cmdIDs;
    // The last uri we were connected to
    String previousUri;

    // Every x ms, we clean up timed out requests
    public long maintenanceSchedule = 10000; //ms

    public int SEQUENCE;
    
    public String NAMEINDB="";
    // Are we currently connected?
    public boolean connected = false;
    // If we haven't received any message from the server after x many
    // milliseconds, disconnect and reconnect again.
    private long reconnectDormantAfter = 20000; // ms
    // ms since unix time of the last indication of an alive connection
    private long lastConnection = -1; // -1 means null
    // Did we disconnect manually? If not, try and reconnect
    private boolean manuallyDisconnected = false;

    // Tracks the serverInfo we are currently connected to
    public ServerInfo serverInfo = new ServerInfo();
    private HashMap<AccountID, Account> accounts = new HashMap<AccountID, Account>();
    // Handles [un]subscription requests, also on reconnect
    public SubscriptionManager subscriptions = new SubscriptionManager();
    
    private static final int MAX_REQUEST_COUNT = 10; 
    
    private ScheduledFuture reconnect_future = null;
    /**
     *  Constructor
     * @param ws
     */
    public Client(WebSocketTransport ws) {
        this.ws = ws;
        ws.setHandler(this);

        prepareExecutor();
        // requires executor, so call after prepareExecutor
        scheduleMaintenance();

        subscriptions.on(SubscriptionManager.OnSubscribed.class, new SubscriptionManager.OnSubscribed() {
            @Override
            public void called(JSONObject subscription) {
                if (!connected)
                    return;
                subscribe(subscription);
            }
        });
    }

    // ### Getters

    private int reconnectDelay() {
        return 1000;
    }

    /**
     * 
     * @param transactionSubscriptionManager
     * @return
     */
    public Client transactionSubscriptionManager(TransactionSubscriptionManager transactionSubscriptionManager) {
        this.transactionSubscriptionManager = transactionSubscriptionManager;
        return this;
    }
    
    /**
     * Log tools.
     * @param level
     * @param fmt
     * @param args
     */
    public static void log(Level level, String fmt, Object... args) {
        if (logger.isLoggable(level)) {
            logger.log(level, fmt, args);
        }
    }

    /**
     * 
     * @param object
     * @return
     */
    public static String prettyJSON(JSONObject object) {
        return object.toString(4);
    }
    /**
     * 
     * @param s
     * @return
     */
    public static JSONObject parseJSON(String s) {
        return new JSONObject(s);
    }


    /* --------------------------- CONNECT / RECONNECT -------------------------- */

    /**
     * After calling this method, all subsequent interaction with the api should
     * be called via posting Runnable() run blocks to the Executor.
     *
     * Essentially, all ripple-lib-java api interaction
     * should happen on the one thread.
     *
     * @see #onMessage(org.json.JSONObject)
     */
    public Client connect(final String uri) {
        manuallyDisconnected = false;

        schedule(50, new Runnable() {
            @Override
            public void run() {
                doConnect(uri);
            }
        });
        return this;
    }

    /**
     * 
     * @param uri
     */
    public void doConnect(String uri) {
        log(Level.INFO, "Connecting to " + uri);
        previousUri = uri;
        ws.connect(URI.create(uri));
    }

    /**
     * Disconnect from websocket-url
     */
    public void disconnect() {
    	disconnectInner();
        service.shutdownNow();
        // our disconnect handler should do the rest
    }
    
    private void disconnectInner(){
        manuallyDisconnected = true;
        ws.disconnect();
    }

    private void emitOnDisconnected() {
        // This ensures that the callback method onDisconnect is
        // called before a new connection is established this keeps
        // the symmetry of connect-> disconnect -> reconnect
        emit(OnDisconnected.class, this);
    }

    /**
     * This will detect stalled connections When connected we are subscribed to
     * a ledger, and ledgers should be at most 20 seconds apart.
     *
     * This also
     */
    private void scheduleMaintenance() {
        schedule(maintenanceSchedule, new Runnable() {
            @Override
            public void run() {
                try {
                    manageTimedOutRequests();
                    int defaultValue = -1;

                    if (!manuallyDisconnected) {
                        if (connected && lastConnection != defaultValue) {
                            long time = new Date().getTime();
                            long msSince = time - lastConnection;
                            if (msSince > reconnectDormantAfter) {
                                lastConnection = defaultValue;
                                reconnect();
                            }
                        }
                    }
                } finally {
                    scheduleMaintenance();
                }
            }
        });
    }

    /**
     * 
     */
    public void reconnect() {
    	emit(OnReconnecting.class,null);
    	
    	log(Level.INFO, "reconnecting");
    	disconnectInner();
    	reconnect_future = service.scheduleAtFixedRate(new Runnable(){
			@Override
			public void run() {
				if(connected){
					System.out.println("reconnected");
					emit(OnReconnected.class,null);
					reconnect_future.cancel(true);
				}else{
					disconnectInner();
					doConnect(previousUri);
				}
			}
        	
        }, 0,2000, TimeUnit.MILLISECONDS);
    }

    void manageTimedOutRequests() {
        long now = System.currentTimeMillis();
        ArrayList<Request> timedOut = new ArrayList<Request>();

        for (Request request : requests.values()) {
            if (request.sendTime != 0) {
                long since = now - request.sendTime;
                if (since >= Request.TIME_OUT) {
                    timedOut.add(request);
                }
            }
        }
        for (Request request : timedOut) {
            request.emit(Request.OnTimeout.class, request.response);
            requests.remove(request.id);
        }
    }

    /**
     *  Handler binders binder
     * @param s
     * @param onConnected
     */
    public void connect(final String s, final OnConnected onConnected) {
        run(new Runnable() {
            public void run() {
                connect(s);
                once(OnConnected.class, onConnected);
            }
        });
    }

    /**
     * 
     * @param onDisconnected
     */
    public void disconnect(final OnDisconnected onDisconnected) {
        run(new Runnable() {
            public void run() {
                Client.this.once(OnDisconnected.class, onDisconnected);
                disconnect();
            }
        });
    }

    /**
     * 
     * @param nextTick
     * @param onConnected
     */
    public void whenConnected(boolean nextTick, final OnConnected onConnected) {
        if (connected) {
            if (nextTick) {
                schedule(0, new Runnable() {
                    @Override
                    public void run() {
                        onConnected.called(Client.this);
                    }
                });
            } else {
                onConnected.called(this);
            }
        }  else {
            once(OnConnected.class, onConnected);
        }
    }

    /**
     * 
     * @param onConnected
     */
    public void nowOrWhenConnected(OnConnected onConnected) {
        whenConnected(false, onConnected);
    }

    /**
     * 
     * @param onConnected
     */
    public void nextTickOrWhenConnected(OnConnected onConnected) {
        whenConnected(true, onConnected);
    }

    /**
     * 
     */
    public void dispose() {
        ws = null;
    }

    /* -------------------------------- EXECUTOR -------------------------------- */

    /**
     * 
     * @param runnable
     */
    public void run(Runnable runnable) {
        // What if we are already in the client thread?? What happens then ?
        if (runningOnClientThread()) {
            runnable.run();
        } else {
            service.submit(errorHandling(runnable));
        }
    }

    /**
     * Start a scheduled task.
     * @param ms
     * @param runnable
     */
    public void schedule(long ms, Runnable runnable) {
        service.schedule(errorHandling(runnable), ms, TimeUnit.MILLISECONDS);
    }

    private boolean runningOnClientThread() {
        return clientThread != null && Thread.currentThread().getId() == clientThread.getId();
    }

    protected void prepareExecutor() {
        service = new ScheduledThreadPoolExecutor(1, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                clientThread = new Thread(r);
                return clientThread;
            }
        });
    }

    public static abstract class ThrowingRunnable implements Runnable {
        public abstract void throwingRun() throws Exception;

        @Override
        public void run() {
            try {
                throwingRun();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    private Runnable errorHandling(final Runnable runnable) {
        return new Runnable() {
            @Override
            public void run() {
                try {
                    runnable.run();
                } catch (Exception e) {
                    onException(e);
                }
            }
        };
    }

    protected void onException(Exception e) {
        e.printStackTrace(System.out);
        if (logger.isLoggable(Level.WARNING)) {
            log(Level.WARNING, "Exception {0}", e);
        }
    }

    private void resetReconnectStatus() {
        lastConnection = new Date().getTime();
    }


    private void updateServerInfo(JSONObject msg) {
		serverInfo.update(msg);
    }

    /* ------------------------- TRANSPORT EVENT HANDLER ------------------------ */

    /**
     * This is to ensure we run everything on {@link Client#clientThread}
     */
    @Override
    public void onMessage(final JSONObject msg) {
    	//System.out.println(msg);
        resetReconnectStatus();
        run(new Runnable() {
            @Override
            public void run() {
                onMessageInClientThread(msg);
            }
        });
    }
    /**
     * 
     */
    @Override
    public void onConnecting(int attempt) {
    }

    /**
     * 
     */
    @Override
    public void onError(Exception error) {
        onException(error);
    }

    /**
     * 
     */
    @Override
    public void onDisconnected(boolean willReconnect) {
        run(new Runnable() {
            @Override
            public void run() {
                doOnDisconnected();
            }
        });
    }

    /**
     * 
     */
    @Override
    public void onConnected() {
        run(new Runnable() {
            public void run() {
                doOnConnected();
            }
        });
    }

    /* ----------------------- CLIENT THREAD EVENT HANDLER ---------------------- */

    /**
     * 
     * @param msg
     */
    public void onMessageInClientThread(JSONObject msg) {
    	String str = msg.optString("type",null);
        Message type = Message.valueOf(str);
        try {
            emit(OnMessage.class, msg);
            if (logger.isLoggable(Level.FINER)) {
                log(Level.FINER, "Receive `{0}`: {1}", type, prettyJSON(msg));
            }

            switch (type) {
                case serverStatus:
                    updateServerInfo(msg);
                    break;
                case ledgerClosed:
                    updateServerInfo(msg);
                    // TODO
                    emit(OnLedgerClosed.class, serverInfo);
                    break;
                case response:
                    onResponse(msg);
                    break;
                case transaction:
                    onTransaction(msg);
                    break;
                case path_find:
                    emit(OnPathFind.class, msg);
                    break;
                case singleTransaction:
                	emit(OnTXMessage.class,msg);
                	break;
                case table:
                	emit(OnTBMessage.class,msg);
                	break;
                default:
                    unhandledMessage(msg);
                    break;
            }
        } catch (Exception e) {
            //logger.log(Level.SEVERE, e.getLocalizedMessage(), e);
            // This seems to be swallowed higher up, (at least by the
            // Java-WebSocket transport implementation)
            System.out.println("error_message: "+e.getLocalizedMessage());
           // throw new RuntimeException(e);
        } finally {
            emit(OnStateChange.class, this);
        }
    }
    private void doOnDisconnected() {
        logger.entering(getClass().getName(), "doOnDisconnected");
        connected = false;
        emitOnDisconnected();

        if (!manuallyDisconnected) {
            schedule(reconnectDelay(), new Runnable() {
                @Override
                public void run() {
                    connect(previousUri);
                }
            });
        } else {
            logger.fine("Currently disconnecting, so will not reconnect");
        }

        logger.entering(getClass().getName(), "doOnDisconnected");
    }

    private void doOnConnected() {
        resetReconnectStatus();

        logger.entering(getClass().getName(), "doOnConnected");
        connected = true;
        emit(OnConnected.class, this);

        subscribe(prepareSubscription());
        logger.exiting(getClass().getName(), "doOnConnected");
    }

    void unhandledMessage(JSONObject msg) {
        log(Level.WARNING, "Unhandled message: " + msg);
    }

    void onResponse(JSONObject msg) {
        Request request = requests.remove(msg.optInt("id", -1));

        if (request == null) {
            log(Level.WARNING, "Response without a request: {0}", msg);
            return;
        }
        request.handleResponse(msg);
    }

    void onTransaction(JSONObject msg) {
        TransactionResult tr = new TransactionResult(msg, TransactionResult
                .Source
                .transaction_subscription_notification);
        if (tr.validated) {
            if (transactionSubscriptionManager != null) {
                transactionSubscriptionManager.notifyTransactionResult(tr);
            } else {
                onTransactionResult(tr);
            }
        }
    }

    /**
     * 
     * @param tr
     */
    public void onTransactionResult(TransactionResult tr) {
        log(Level.INFO, "Transaction {0} is validated", tr.hash);
        Map<AccountID, STObject> affected = tr.modifiedRoots();

        if (affected != null) {
            Hash256 transactionHash = tr.hash;
            UInt32 transactionLedgerIndex = tr.ledgerIndex;

            for (Map.Entry<AccountID, STObject> entry : affected.entrySet()) {
                Account account = accounts.get(entry.getKey());
                if (account != null) {
                    STObject rootUpdates = entry.getValue();
                    account.getAccountRoot()
                            .updateFromTransaction(
                                    transactionHash, transactionLedgerIndex, rootUpdates);
                }
            }
        }

        Account initator = accounts.get(tr.initiatingAccount());
        if (initator != null) {
            log(Level.INFO, "Found initiator {0}, notifying transactionManager", initator);
            initator.transactionManager().notifyTransactionResult(tr);
        } else {
            log(Level.INFO, "Can't find initiating account!");
        }
        emit(OnValidatedTransaction.class, tr);
    }

    private void sendMessage(JSONObject object) {
        if (logger.isLoggable(Level.FINER)) {
            logger.log(Level.FINER, "Send: {0}", prettyJSON(object));
        }
        emit(OnSendMessage.class, object);
        ws.sendMessage(object);

        if (randomBugsFrequency != 0) {
            if (randomBugs.nextDouble() > (1D - randomBugsFrequency)) {
                disconnect();
                connect(previousUri);
                String msg = "I disconnected you, now I'm gonna throw, " +
                        "deal with it suckah! ;)";
                logger.warning(msg);
                throw new RuntimeException(msg);
            }

        }
    }

    /* -------------------------------- ACCOUNTS -------------------------------- */

    /**
     * Request account information.
     * @param masterSeed
     * @return
     */
    public Account accountFromSeed(String masterSeed) {
        IKeyPair kp = Seed.fromBase58(masterSeed).keyPair();
        return account(AccountID.fromKeyPair(kp), kp);
    }

    private Account account(final AccountID id, IKeyPair keyPair) {
        if (accounts.containsKey(id)) {
            return accounts.get(id);
        } else {
            TrackedAccountRoot accountRoot = accountRoot(id);
            Account account = new Account(
                    id,
                    keyPair,
                    accountRoot,
                    new TransactionManager(this, accountRoot, id, keyPair)
            );
            accounts.put(id, account);
            subscriptions.addAccount(id);

            return account;
        }
    }

    private TrackedAccountRoot accountRoot(AccountID id) {
        TrackedAccountRoot accountRoot = new TrackedAccountRoot();
        requestAccountRoot(id, accountRoot);
        return accountRoot;
    }

    private void requestAccountRoot(final AccountID id,
                                    final TrackedAccountRoot accountRoot) {

        makeManagedRequest(Command.ledger_entry, new Manager<JSONObject>() {
            @Override
            public boolean retryOnUnsuccessful(Response r) {
                return r == null || r.rpcerr == null || r.rpcerr != RPCErr.entryNotFound;
            }

            @Override
            public void cb(Response response, JSONObject jsonObject) throws JSONException {
            	//System.out.println("requestAccountRoot response:" + jsonObject);
                if (response.succeeded) {
                    accountRoot.setFromJSON(jsonObject);
                } else {
                    log(Level.INFO, "Unfunded account: {0}", response.message);
                    accountRoot.setUnfundedAccount(id);
                }
            }
        }, new Request.Builder<JSONObject>() {
            @Override
            public void beforeRequest(Request request) {
                request.json("account_root", id);
            }

            @Override
            public JSONObject buildTypedResponse(Response response) {
                return response.result.getJSONObject("node");
            }
        });
    }

    /* ------------------------------ SUBSCRIPTIONS ----------------------------- */

    private void subscribe(JSONObject subscription) {
        Request request = newRequest(Command.subscribe);

        request.json(subscription);
        request.on(Request.OnSuccess.class, new Request.OnSuccess() {
            @Override
            public void called(Response response) {
                // TODO ... make sure this isn't just an account subscription
                serverInfo.update(response.result);
                emit(OnSubscribed.class, serverInfo);
            }
        });
        request.request();
    }

    private JSONObject prepareSubscription() {
        subscriptions.pauseEventEmissions();
        subscriptions.addStream(SubscriptionManager.Stream.ledger);
        subscriptions.addStream(SubscriptionManager.Stream.server);
        subscriptions.unpauseEventEmissions();
        return subscriptions.allSubscribed();
    }

    /* ------------------------------ REQUESTS ------------------------------ */

    /**
     * Create a new request.
     * @param cmd Command name.
     * @return
     */
    public Request newRequest(Command cmd) {
        return new Request(cmd, cmdIDs++, this);
    }

    /**
     * Send a request message.
     * @param request
     */
    public void sendRequest(final Request request) {
    	//System.out.println("request:"+request.json());
        Logger reqLog = Request.logger;

        try {
            requests.put(request.id, request);
            request.bumpSendTime();
            sendMessage(request.toJSON());
            // Better safe than sorry
        } catch (Exception e) {
            if (reqLog.isLoggable(Level.WARNING)) {
                reqLog.log(Level.WARNING, "Exception when trying to request: {0}", e);
            }
            nextTickOrWhenConnected(new OnConnected() {
                @Override
                public void called(Client args) {
                    sendRequest(request);
                }
            });
        }
    }

    /**
     *  Managed Requests API
     * @param cmd
     * @param manager
     * @param builder
     * @return
     */
    public <T> Request makeManagedRequest(final Command cmd, final Manager<T> manager, final Request.Builder<T> builder){
    	return makeManagedRequest(cmd,manager,builder,0);
    }
    
    private <T> Request makeManagedRequest(final Command cmd, final Manager<T> manager, final Request.Builder<T> builder,int depth) {
    	if(depth > MAX_REQUEST_COUNT){
    		return null;
    	}
        final Request request = newRequest(cmd);
        final boolean[] responded = new boolean[]{false};
        request.once(Request.OnTimeout.class, new Request.OnTimeout() {
            @Override
            public void called(Response args) {
            	System.out.println("timeout");
                if (!responded[0] && manager.retryOnUnsuccessful(null)) {
                    logRetry(request, "Request timed out");
                    request.clearAllListeners();
                    queueRetry(50, cmd, manager, builder,depth);
                }
            }
        });
        final OnDisconnected cb = new OnDisconnected() {
            @Override
            public void called(Client c) {
                if (!responded[0] && manager.retryOnUnsuccessful(null)) {
                    logRetry(request, "Client disconnected");
                    request.clearAllListeners();
                    queueRetry(50, cmd, manager, builder,depth);
                }
            }
        };
        once(OnDisconnected.class, cb);
        request.once(Request.OnResponse.class, new Request.OnResponse() {
            @Override
            public void called(final Response response) {
                responded[0] = true;
                Client.this.removeListener(OnDisconnected.class, cb);

                if (response.succeeded) {
                    final T t = builder.buildTypedResponse(response);
                    manager.cb(response, t);
                } else {
                    if (manager.retryOnUnsuccessful(response)) {
                        queueRetry(50, cmd, manager, builder,depth);
                    } else {
                        manager.cb(response, null);
                    }
                }
            }
        });
        builder.beforeRequest(request);
        manager.beforeRequest(request);

    	//System.out.println("request:" + request.toJSON());
    	
        request.request();
        return request;
    }

    private <T> void queueRetry(int ms,
                                final Command cmd,
                                final Manager<T> manager,
                                final Request.Builder<T> builder,
                                final int depth) {
        schedule(ms, new Runnable() {
            @Override
            public void run() {
                makeManagedRequest(cmd, manager, builder,depth + 1);
            }
        });
    }

    private void logRetry(Request request, String reason) {
        if (logger.isLoggable(Level.WARNING)) {
            log(Level.WARNING, previousUri + ": " + reason + ", muting listeners " +
                    "for " + request.json() + "and trying again");
        }
    }

    // ### Managed Requests

    /**
     * 
     * @param accountID
     * @return
     */
    public AccountTxPager accountTxPager(AccountID accountID) {
        return new AccountTxPager(this, accountID, null);
    }

    /**
     * Request for a ledger_entry.
     * @param index
     * @param ledger_index
     * @param cb
     */
    public void requestLedgerEntry(final Hash256 index, final Number ledger_index, final Manager<LedgerEntry> cb) {
        makeManagedRequest(Command.ledger_entry, cb, new Request.Builder<LedgerEntry>() {
            @Override
            public void beforeRequest(Request request) {
                if (ledger_index != null) {
                    request.json("ledger_index", ledgerIndex(ledger_index));
                }
                request.json("index", index.toJSON());
            }
            @Override
            public LedgerEntry buildTypedResponse(Response response) {
                String node_binary = response.result.optString("node_binary");
                STObject node = STObject.translate.fromHex(node_binary);
                node.put(Hash256.index, index);
                return (LedgerEntry) node;
            }
        });
    }

    private Object ledgerIndex(Number ledger_index) {
        long l = ledger_index.longValue();
        if (l == VALIDATED_LEDGER) {
            return "validated";
        }
        return l;
    }

    /**
     * Request for account_lines.
     * @param addy
     * @param manager
     */
    public void requestAccountLines(final AccountID addy, final Manager<ArrayList<AccountLine>> manager) {
        makeManagedRequest(Command.account_lines, manager, new Request.Builder<ArrayList<AccountLine>>() {
            @Override
            public void beforeRequest(Request request) {
                request.json("account", addy);
            }

            @Override
            public ArrayList<AccountLine> buildTypedResponse(Response response) {
                ArrayList<AccountLine> lines = new ArrayList<AccountLine>();
                JSONArray array = response.result.optJSONArray("lines");
                for (int i = 0; i < array.length(); i++) {
                    JSONObject line = array.optJSONObject(i);
                    lines.add(AccountLine.fromJSON(addy, line));
                }
                return lines;
            }
        });
    }

    /**
     * Request for book_offers.
     * @param ledger_index
     * @param get
     * @param pay
     * @param cb
     */
    public void requestBookOffers(final Number ledger_index,
                                  final Issue get,
                                  final Issue pay,
                                  final Manager<ArrayList<Offer>> cb) {
        makeManagedRequest(Command.book_offers, cb, new Request.Builder<ArrayList<Offer>>() {
            @Override
            public void beforeRequest(Request request) {
                request.json("taker_gets", get.toJSON());
                request.json("taker_pays", pay.toJSON());

                if (ledger_index != null) {
                    request.json("ledger_index", ledger_index);
                }
            }
            @Override
            public ArrayList<Offer> buildTypedResponse(Response response) {
                ArrayList<Offer> offers = new ArrayList<Offer>();
                JSONArray offersJson = response.result.getJSONArray("offers");
                for (int i = 0; i < offersJson.length(); i++) {
                    JSONObject jsonObject = offersJson.getJSONObject(i);
                    STObject object = STObject.fromJSONObject(jsonObject);
                    offers.add((Offer) object);
                }
                return offers;
            }
        });
    }

 
    // ### Request builders
    // These all return Request
    /**
     * Submit a transaction
     * @param tx_blob
     * @param fail_hard
     * @return
     */
    public Request submit(String tx_blob, boolean fail_hard) {
        Request req = newRequest(Command.submit);
        req.json("tx_blob", tx_blob);
        req.json("fail_hard", fail_hard);
        return req;
    }
    
    /**
     * Request for account information.
     * @param account
     * @return
     */
    public Request accountInfo(AccountID account) {
        Request request = newRequest(Command.account_info);
        request.json("account", account.address);

        request.request();
        waiting(request);
   		return request;
    }
    
    /**
     * 
     * @param messageTx
     * @return
     */
    public Request messageTx(JSONObject messageTx){
    	 Request request = newRequest(Command.subscribe);
    	 request.json("tx_json", messageTx);
         request.request();
         waiting(request);
         return request;
    }
    
    /**
     * Select data from chain.
     * @param account
     * @param tabarr
     * @param raw
     * @param cb
     * @return
     */
    public  Request select(AccountID account,JSONObject[] tabarr,String raw,Callback<Response> cb){
	   	 Request request = newRequest(Command.r_get);
	   	 JSONObject txjson = new JSONObject();
	   	 txjson.put("Owner", account);
	   	 txjson.put("Tables", tabarr);
	   	 txjson.put("Raw", raw);
	   	 txjson.put("OpType", 7);
	   	 request.json("tx_json", txjson);
	   	 request.once(Request.OnResponse.class, new Request.OnResponse() {
	            public  void called(Response response) {
	                if (response.succeeded) {
	                	//System.out.println("response:" + response.message.toString());
	                	cb.called(response);
	                   //Integer Sequence = (Integer) response.result.optJSONObject("account_data").get("Sequence");
	                }
	            }
	        });
        request.request();
        waiting(request);
   	 	return request;	
	}
    
    /**
     * Request for ledger data.
     * @param option
     * @param cb
     */
    public  void getLedger(JSONObject option,Callback<JSONObject> cb){  
    	makeManagedRequest(Command.ledger, new Manager<JSONObject>() {
            @Override
            public boolean retryOnUnsuccessful(Response r) {
            	return false;
            }

            @Override
            public void cb(Response response, JSONObject jsonObject) throws JSONException {
            	cb.called(jsonObject);
            }
        }, new Request.Builder<JSONObject>() {
            @Override
            public void beforeRequest(Request request) {
            	request.json("ledger_index", option.get("ledger_index"));
       		 	request.json("expand", false);
       		 	request.json("transactions",true);
       		 	request.json("accounts",false );
            }

            @Override
            public JSONObject buildTypedResponse(Response response) {
                return response.result;
            }
        });
    }
    /**
     * Request for newest published ledger_index.
     * @param cb
     */
    public  void getLedgerVersion(Callback<JSONObject> cb){   	
    	makeManagedRequest(Command.ledger_current, new Manager<JSONObject>() {
            @Override
            public boolean retryOnUnsuccessful(Response r) {
            	return false;
            }

            @Override
            public void cb(Response response, JSONObject jsonObject) throws JSONException {
            	cb.called(jsonObject);
            }
        }, new Request.Builder<JSONObject>() {
            @Override
            public void beforeRequest(Request request) {
            }

            @Override
            public JSONObject buildTypedResponse(Response response) {
                return response.result;
            }
        });
     }    
    
    /**
     * Request for transaction information.
     * @param address
     * @param cb
     */
    public  void getTransactions(String address,Callback<JSONObject> cb){
    	makeManagedRequest(Command.account_tx, new Manager<JSONObject>() {
            @Override
            public boolean retryOnUnsuccessful(Response r) {
            	return false;
            }

            @Override
            public void cb(Response response, JSONObject jsonObject) throws JSONException {
            	cb.called(jsonObject);
            }
        }, new Request.Builder<JSONObject>() {
            @Override
            public void beforeRequest(Request request) {
	           	 request.json("account", address);
	           	 request.json("ledger_index_min", -1);
	           	 request.json("ledger_index_max", -1);
	           	 request.json("limit", 10);
            }

            @Override
            public JSONObject buildTypedResponse(Response response) {
            	JSONArray txs = (JSONArray)response.result.get("transactions");
            	for(int i=0; i<txs.length(); i++){
            		JSONObject tx = (JSONObject)txs.get(i);
            		Util.unHexData(tx.getJSONObject("tx"));
            		if(tx.has("meta")){
            			tx.remove("meta");
            		}
            	}
            	
                return response.result;
            }
        });
    }
    private void waiting(Request request){
        int count = 100;
        while(request.response==null){
        	Util.waiting(); 
        	if(--count == 0){
        		break;
        	}
   	 	}
    }
    /**
     * Get transaction count on chain.
     * @return
     */
    public JSONObject getTransactionCount(){
    	Request request = newRequest(Command.tx_count);
	    request.request();
	    waiting(request);
	   	return request.response.result;	
    }
    
    /**
     * Get server_info
     * @return
     */
    public JSONObject getServerInfo(){
    	Request request = newRequest(Command.server_info);
        request.request();
        waiting(request);
   	 	return request.response.result;
    }

    /**
     * Get user_token for table,if token got not null, it is a confidential table.
     * @param owner Table's owner/creator.
     * @param user	Operating account.
     * @param name	Table name.
     * @return Request object contains response data.
     */
    public Request getUserToken(String owner,String user,String name){
    	 Request request = newRequest(Command.g_userToken);
	   	 JSONObject txjson = new JSONObject();
	   	 txjson.put("Owner", owner);
	   	 txjson.put("User", user);
	   	 txjson.put("TableName", name);
	   	 request.json("tx_json", txjson);

         request.request();
         waiting(request);
	   	 return request;	
    }
    
    /**
     * Prepare for a transaction for : filling in NameInDB field, filling in CheckHash field for StrictMode
     * @param txjson tx_json with fields and value a transaction needed.
     * @return
     */
    public Request getTxJson(JSONObject txjson){
    	Request request = newRequest(Command.t_prepare);
	   	request.json("tx_json", txjson);
	    request.request();
	    waiting(request);
	    return request;	
   }
    
    /**
     * Request for a transaction's information.
     * @param hash
     * @param cb
     */
    public  void getTransaction(String hash,Callback<JSONObject> cb){   
    	
    	makeManagedRequest(Command.tx, new Manager<JSONObject>() {
            @Override
            public boolean retryOnUnsuccessful(Response r) {
            	return false;
            }

            @Override
            public void cb(Response response, JSONObject jsonObject) throws JSONException {
            	cb.called(jsonObject);
            }
        }, new Request.Builder<JSONObject>() {
            @Override
            public void beforeRequest(Request request) {
            	 request.json("transaction", hash);
            }

            @Override
            public JSONObject buildTypedResponse(Response response) {
    			if(response.result.has("meta")){
    				response.result.remove("meta");
    			}
    			Util.unHexData(response.result);
                return response.result;
            }
        });
    }
    
    /**
     * Request ping.
     * @return
     */
    public Request ping() {
        return newRequest(Command.ping);
    }

    /**
     * Subscribe for account.
     * @param accounts
     * @return
     */
    public Request subscribeAccount(AccountID... accounts) {
        Request request = newRequest(Command.subscribe);
        JSONArray accounts_arr = new JSONArray();
        for (AccountID acc : accounts) {
            accounts_arr.put(acc);
        }
        request.json("accounts", accounts_arr);
        return request;
    }

    /**
     * Request for book-offers.
     * @param get
     * @param pay
     * @return
     */
    public Request subscribeBookOffers(Issue get, Issue pay) {
        Request request = newRequest(Command.subscribe);
        JSONObject book = new JSONObject();
        JSONArray books = new JSONArray(new Object[] { book });
        book.put("snapshot", true);
        book.put("taker_gets", get.toJSON());
        book.put("taker_pays", pay.toJSON());
        request.json("books", books);
        return request;
    }

    /**
     * Request for book offers.
     * @param get
     * @param pay
     * @return
     */
    public Request requestBookOffers(Issue get, Issue pay) {
        Request request = newRequest(Command.book_offers);
        request.json("taker_gets", get.toJSON());
        request.json("taker_pays", pay.toJSON());
        return request;
    }
}
