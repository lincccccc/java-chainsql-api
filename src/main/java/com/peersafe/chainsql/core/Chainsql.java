package com.peersafe.chainsql.core;

import static com.ripple.config.Config.getB58IdentiferCodecs;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

import com.peersafe.chainsql.crypto.Aes;
import com.peersafe.chainsql.crypto.Ecies;
import com.peersafe.chainsql.net.Connection;
import com.peersafe.chainsql.util.EventManager;
import com.peersafe.chainsql.util.Util;
import com.peersafe.chainsql.util.Validate;
import com.ripple.client.pubsub.Publisher.Callback;
import com.ripple.core.coretypes.AccountID;
import com.ripple.core.coretypes.Amount;
import com.ripple.core.coretypes.Blob;
import com.ripple.core.coretypes.STArray;
import com.ripple.core.coretypes.uint.UInt16;
import com.ripple.core.coretypes.uint.UInt32;
import com.ripple.core.serialized.enums.TransactionType;
import com.ripple.core.types.known.tx.Transaction;
import com.ripple.core.types.known.tx.signed.SignedTransaction;
import com.ripple.core.types.known.tx.txns.TableListSet;
import com.ripple.crypto.ecdsa.IKeyPair;
import com.ripple.crypto.ecdsa.Seed;
import com.ripple.encodings.B58IdentiferCodecs;

public class Chainsql extends Submit {
	public	EventManager event;
	public List<JSONObject> cache = new ArrayList<JSONObject>();
	private boolean strictMode = false;
	private boolean transaction = false;
	private Integer needVerify = 1;
	
	private static final int PASSWORD_LENGTH = 16;  
	
	private SignedTransaction signed;
	 
	public void as(String address, String secret) {
		this.connection.address = address;
		this.connection.secret = secret;
		if (this.connection.scope == null) {
			this.connection.scope = address;
		}
	}

	public void use(String address) {
		this.connection.scope = address;
	}

	public static final Chainsql c = new Chainsql();

	public Connection connect(String url) {
		connection = new Connection().connect(url);
		while (!connection.client.connected) {
			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
		this.event = new EventManager(this.connection);
		return connection;
	}

	public void disconnect() {
		this.connection.disconnect();
	}

	public void setRestrict(boolean falg) {
		this.strictMode = falg;
	}

	public Table table(String name) {
		Table tab = new Table(name);
		 if (this.transaction) {
		   	tab.transaction = this.transaction;
		    tab.cache = this.cache;
		}
		tab.strictMode = this.strictMode;
		tab.event = this.event;
		tab.connection = this.connection;
		return tab;
	}
	
	@Override
	JSONObject doSubmit() {
		return doSubmit(signed);
	}
	
	public Chainsql createTable(String name, List<String> raw) {
		return createTable(name, raw , false);
	}
	
	public Chainsql createTable(String name, List<String> rawList ,boolean confidential) {
		List<JSONObject> listRaw = Util.ListToJsonList(rawList);
		try {
			Util.checkinsert(listRaw);
		} catch (Exception e) {
			System.out.println("Exception:" + e.getLocalizedMessage());
		}
		
		JSONObject json = new JSONObject();
		json.put("OpType", 1);
		json.put("Tables", getTableArray(name));
		
		String strRaw = listRaw.toString();
		if(confidential){
			byte[] password = Util.getRandomBytes(PASSWORD_LENGTH);
			String token = generateUserToken(this.connection.secret,password);
			if(token.length() == 0){
				System.out.println("generateUserToken failed");
				return null;
			}
			json.put("Token", token);
			strRaw = Aes.aesEncrypt(password, strRaw);
		}else{
			strRaw = Util.toHexString(strRaw);
		}
		json.put("Raw", strRaw);
		
		if(this.transaction){
			this.cache.add(json);
			return null;
		}
		return create(json);
	}

	private Chainsql create(JSONObject txjson) {
		Transaction payment;
		try {
			payment = toPayment(txjson);
			signed = payment.sign(this.connection.secret);
		} catch (Exception e) {
			e.printStackTrace();
		}
		return this;
	}
	
	private String generateUserToken(String seed,byte[] password){
		IKeyPair keyPair = Seed.getKeyPair(seed);
		return Ecies.eciesEncrypt(password, keyPair.canonicalPubBytes());
	}

	public Chainsql dropTable(String name) {
		JSONObject json = new JSONObject();
		json.put("OpType", 2);
		json.put("Tables", getTableArray(name));
		if(this.transaction){
			this.cache.add(json);
			return null;
		}
		return drop(json);
	}

	private Chainsql drop(JSONObject txjson) {
		Transaction payment;
		try {
			payment = toPayment(txjson);
		signed = payment.sign(this.connection.secret);
		} catch (Exception e) {
			e.printStackTrace();
		}
		return this;
	}

	public Chainsql renameTable(String oldName, String newName) {
		String tablestr = "{\"Table\":{\"TableName\":\"" + Util.toHexString(oldName) + "\",\"TableNewName\":\"" + Util.toHexString(newName) + "\"}}";
		JSONArray table = new JSONArray();
		table.put(new JSONObject(tablestr));
		JSONObject json = new JSONObject();
		json.put("OpType", 3);
		json.put("Tables", table);
		if(this.transaction){
			this.cache.add(json);
			return null;
		}
		return rename(json);
		
	}
	private Chainsql rename(JSONObject txjson) {
		Transaction payment;
		try {
			payment = toPayment(txjson);
			signed = payment.sign(this.connection.secret);
		} catch (Exception e) {
			e.printStackTrace();
		}
		return this;
	}

	public Chainsql grant(String name, String user,String userPublicKey,String flag){
		JSONObject res = Validate.getUserToken(connection,this.connection.address,name);
		if(res.get("status").equals("error")){
			System.out.println(res.getString("error_message"));
			return null;
		}
		String token = res.getString("token");
		String newToken = "";
		if(token.length() != 0){
			try {
				byte[] password = Ecies.eciesDecrypt(token, this.connection.secret);
				if(password == null){
					return null;
				}
				byte [] pubBytes = getB58IdentiferCodecs().decode(userPublicKey, B58IdentiferCodecs.VER_ACCOUNT_PUBLIC);
				newToken = Ecies.eciesEncrypt(password, pubBytes);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		return grant_inner(name,user,flag,newToken);
		
	}
	
	public Chainsql grant(String name, String user,String flag) {
		return grant_inner(name,user,flag,"");
	}

	private Chainsql grant_inner(String name, String user,String flag,String token) {
		List<JSONObject> flags = new ArrayList<JSONObject>();
		JSONObject json = Util.StrToJson(flag);
		flags.add(json);
		JSONObject txJson = new JSONObject();
		txJson.put("Tables", getTableArray(name));
		txJson.put("OpType", 11);
		txJson.put("User", user);
		txJson.put("Raw", Util.toHexString(flags.toString()));
		if(token.length() > 0){
			txJson.put("Token", token);
		}
		
		if(this.transaction){
			this.cache.add(txJson);
			return null;
		}
		return grant(name, txJson);
	}
	private Chainsql grant(String name, JSONObject txJson) {
		Transaction payment;
		try {
			payment = toPayment(txJson);
		signed = payment.sign(this.connection.secret);
		} catch (Exception e) {
			e.printStackTrace();
		}
		return this;
	}

	public void beginTran(){
		 if (this.connection!=null && this.connection.address!=null) {
		    this.transaction = true;
		    return;
		  }
		
	}
	public JSONObject commit(){
		return doCommit("");
	}
	public JSONObject commit(SyncCond cond){
		return doCommit(cond);
	}
	public JSONObject commit(Callback cb){
		return doCommit(cb);
	}
	
	public JSONObject doCommit(Object  commitType){
		List<JSONObject> cache = this.cache;	
		
		JSONArray statements = new JSONArray();
        for (int i = 0; i < cache.size(); i++) {
        	statements.put(cache.get(i));
        }
        
		JSONObject payment = new JSONObject();
		payment.put("TransactionType",TransactionType.SQLTransaction);
		payment.put( "Account", this.connection.address);
		payment.put("Statements", statements);
		payment.put("NeedVerify",this.needVerify);
		
        JSONObject result = Validate.getTxJson(this.connection.client, payment);
		if(result.getString("status").equals("error")){
			return  new JSONObject(){{
				put("Error:",result.getString("error_message"));
			}};
		}
		JSONObject tx_json = result.getJSONObject("tx_json");
		Transaction paymentTS;
		try {
			paymentTS = toPayment(tx_json,TransactionType.SQLTransaction);
			signed = paymentTS.sign(this.connection.secret);
			if(commitType instanceof SyncCond ){
				return submit((SyncCond)commitType);
			}else if(commitType instanceof Callback){
				return submit((Callback<JSONObject>) commitType);
			}else{
				return submit();
			}
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			return null;
		}
	}
	
	private Transaction toPayment(JSONObject json) throws Exception{
		json.put("Account",this.connection.address);
    	JSONObject tx_json = Validate.getTxJson(this.connection.client, json);
    	if(tx_json.getString("status").equals("error")){
    		throw new Exception(tx_json.getString("error_message"));
    	}else{
    		tx_json = tx_json.getJSONObject("tx_json");
    	}
		return toPayment(json,TransactionType.TableListSet);
	}

	public void getLedger(JSONObject option,Callback<JSONObject> cb){
		this.connection.client.getLedger(option,cb);
	}
	
	public void getLedgerVersion(Callback<JSONObject>  cb){
		this.connection.client.getLedgerVersion(cb);
	}
	
	public void getTransactions(String address,Callback<JSONObject>  cb){
		this.connection.client.getTransactions(address,cb);	
	}

}
