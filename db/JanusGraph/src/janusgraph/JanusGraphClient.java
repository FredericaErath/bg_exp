/**                                                                                                                                                                                
 * Copyright (c) 2012 USC Database Laboratory All rights reserved. 
 *
 * Authors:  Sumita Barahmand and Shahram Ghandeharizadeh                                                                                                                            
 *                                                                                                                                                                                 
 * Licensed under the Apache License, Version 2.0 (the "License"); you                                                                                                             
 * may not use this file except in compliance with the License. You                                                                                                                
 * may obtain a copy of the License at                                                                                                                                             
 *                                                                                                                                                                                 
 * http://www.apache.org/licenses/LICENSE-2.0                                                                                                                                      
 *                                                                                                                                                                                 
 * Unless required by applicable law or agreed to in writing, software                                                                                                             
 * distributed under the License is distributed on an "AS IS" BASIS,                                                                                                               
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or                                                                                                                 
 * implied. See the License for the specific language governing                                                                                                                    
 * permissions and limitations under the License. See accompanying                                                                                                                 
 * LICENSE file.                                                                                                                                                                   
 */

package janusgraph;

import edu.usc.bg.base.ByteIterator;
import edu.usc.bg.base.DB;
import edu.usc.bg.base.DBException;
import edu.usc.bg.base.StringByteIterator;
import org.apache.tinkerpop.gremlin.driver.Client;
import org.apache.tinkerpop.gremlin.driver.Cluster;
import org.apache.tinkerpop.gremlin.driver.Result;
import org.apache.tinkerpop.gremlin.structure.io.binary.TypeSerializerRegistry;
import org.apache.tinkerpop.gremlin.util.ser.GraphBinaryMessageSerializerV1;
import org.codehaus.jackson.map.ObjectMapper;
import org.apache.tinkerpop.gremlin.driver.ResultSet;
import org.janusgraph.graphdb.tinkerpop.JanusGraphIoRegistry;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ExecutionException;

public class JanusGraphClient extends DB{
	/** The code to return when the call succeeds. **/
	public static final int SUCCESS = 0;
	/** The code to return when the call fails. **/
	public static final int ERROR   = -1;
	private Properties props;
	Client client;
	
	
	@Override
	public boolean init() throws DBException {
		try {
			TypeSerializerRegistry registry = TypeSerializerRegistry.build()
					.addRegistry(JanusGraphIoRegistry.instance())
					.create();
			Cluster cluster = Cluster.build()
					.addContactPoint("128.110.96.123")
					.port(8182)
					.serializer(new GraphBinaryMessageSerializerV1(registry))
					.maxContentLength(524288)
					.create();
			client = cluster.connect();
//			try {
//				createSchema(props);
//			} catch (Exception e){
//				e.printStackTrace(System.out);
//			}
			return true;
		} catch (Exception e) {
			e.printStackTrace(System.out);
			return false;
		}
	}

	private void cleanupAllConnections() {
		try {
			if (client != null) client.close();
		} catch (Exception e) {
			e.printStackTrace(System.out);
		}
	}

	@Override
	public HashMap<String, String> getInitialStats() {
		HashMap<String, String> stats = new HashMap<String, String>();
		org.apache.tinkerpop.gremlin.driver.ResultSet rs = null;
		String query = "";

		try {
			// Get user count.
			query = "g.V().hasLabel('users').count().tryNext().orElse(null)";
			rs = client.submit(query);
			if (rs!=null) {
				stats.put("usercount", rs.one().getString());
			} else
				stats.put("usercount", "0");

			// Get user offset.
			query = "g.V().hasLabel('users').values('userid').min().tryNext().orElse(null)";
			rs = client.submit(query);
			String offset = "0";
			try {
				offset = rs.one().getString();
			} catch (NullPointerException e){
				System.out.println("no elements");
			}

			// Get resources per user.
			stats.put("resourcesperuser", "0");


			// Get number of friends per user.
			if(!offset.equals("0")){
				query = "g.V().hasLabel('users').has('userid', " + offset + ").both('friendship').has('status', 'friend').count().tryNext().orElse(null)";
				rs = client.submit(query);
				if (rs!=null) {
					stats.put("avgfriendsperuser", rs.one().getString());
				} else
					stats.put("avgfriendsperuser", "0");
				query = "g.V().hasLabel('users').has('userid', " + offset + ").both('friendship').has('status', 'pending').count().tryNext().orElse(null)";
				rs = client.submit(query);
				if (rs!=null) {
					stats.put("avgpendingperuser", rs.one().getString());
				} else
					stats.put("avgpendingperuser", "0");
			} else {
				stats.put("avgfriendsperuser", "0");
				stats.put("avgpendingperuser", "0");
			}

		} catch (Exception sx) {
			sx.printStackTrace(System.out);
		}
		return stats;
	}
	
	@Override
	public void cleanup(boolean warmup) throws ExecutionException, InterruptedException {
		client.submit("g.V().drop()").all().get();
	}
	
	@Override
	public void createSchema(Properties props) {
		try {
			// read JSON file
			String schemaScript = new String(Files.readAllBytes(Paths.get("conf/schema.groovy")));
			client.submit(schemaScript).all().get();

			System.out.println("Schema successfully created!");
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	

	
	@Override
	public int insertEntity(String entitySet, String entityPK,
			HashMap<String, ByteIterator> values, boolean insertImage) {
		// entityPK is automaticly generated?
		if (entitySet == null) {
			return -1;
		}
		if (entityPK == null) {
			return -1;
		}
		ResultSet rs =null;

		try {
			if(entitySet.equalsIgnoreCase("resources")) {
				return SUCCESS; // TODO: at this stage we don't add any resourse
			}
			StringBuilder gremlinQuery = new StringBuilder("g.addV('" + entitySet + "')");
			gremlinQuery.append(".property('").append("userid").append("', ").append(Integer.parseInt(entityPK)).append(")");
			for(Map.Entry<String, ByteIterator> entry: values.entrySet()) {
				//TODO: at this stage we don't add any pic and tpics
				if(entry.getKey().equalsIgnoreCase("pic") || entry.getKey().equalsIgnoreCase("tpic") ) {
					continue;
				}
				String value = entry.getValue().toString();
				String cleaned = value.replace("'", "\\'");
				gremlinQuery.append(".property('").append(entry.getKey().toLowerCase()).append("', '").append(cleaned).append("')");
			}
			client.submit(gremlinQuery.toString()).all().get();
			return SUCCESS;
		} catch (Exception e) {
			System.err.println("Error while inserting entity into graph: " + entitySet);
			e.printStackTrace();
			return ERROR;
		}
	}

	@Override
	public int inviteFriend(int inviterID, int inviteeID){
		try {
			String checkInviter = "g.V().hasLabel('users').has('userid', " + inviterID + ").id().tryNext().orElse(null)";
			Object inviterVertexId = client.submit(checkInviter).one().getObject();
			String checkInvitee = "g.V().hasLabel('users').has('userid', " + inviteeID + ").id().tryNext().orElse(null)";
			Object inviteeVertexId = client.submit(checkInvitee).one().getObject();

			if (inviterVertexId == null || inviteeVertexId == null) {
				System.err.println("Inviter or Invitee ID not found.");
				return ERROR;
			}
			String checkExistingEdge = "g.V(" + inviterVertexId + ").outE('friendship')" +
					".where(__.inV().hasId(" + inviteeVertexId + "))" +
					".values('status').tryNext().orElse(null)";

			Object existingStatus = client.submit(checkExistingEdge).one().getObject();
			if (existingStatus != null) {
				System.err.println(inviterID + " -> " + inviteeID + " Friendship already exists with status: " + existingStatus);
				return ERROR;
			}
			String createFriendRequest = "g.V(" + inviterVertexId + ").addE('friendship').to(__.V(" + inviteeVertexId + "))" +
					".property('status', 'pending')";
			client.submit(createFriendRequest).all().get();
			System.out.println(inviterID + " -> " + inviteeID + " Friend request sent successfully from " + inviterID + " to " + inviteeID);

		} catch (Exception e){
			System.err.println("Error while inserting entity into graph: " );
			e.printStackTrace();
			return ERROR;
		}
		return SUCCESS;
	}
	
	@Override
	public int CreateFriendship(int friendid1, int friendid2) {
		try {
			String checkVertex1 = "g.V().hasLabel('users').has('userid', " + friendid1 + ").id().tryNext().orElse(null)";
			System.out.println(checkVertex1);
			Object fromVertexId = client.submit(checkVertex1).one().getObject();

			String checkVertex2 = "g.V().hasLabel('users').has('userid', " + friendid2 + ").id().tryNext().orElse(null)";
			Object toVertexId = client.submit(checkVertex2).one().getObject();

			if (fromVertexId != null && toVertexId != null) {
				String createEdge = "g.V(" + fromVertexId + ").addE('friendship').to(__.V(" + toVertexId + "))" +
						".property('status', 'friendship')";
				System.out.println(createEdge);
				client.submit(createEdge).all().get();
				return SUCCESS;
			} else {
				System.err.println("One or both vertices not found.");
				return ERROR;
			}
		} catch (Exception e) {
			System.err.println("Error while creating friendship");
			e.printStackTrace();
			return ERROR;
		}
	}
	
	@Override
	public int acceptFriend(int inviterID, int inviteeID) {
		// change the status of inviter and invitee into confirmed.
		try {
			String checkEdge = "g.E().hasLabel('friendship').where(__.outV().has('userid', " + inviterID + "))" +
					".where(__.inV().has('userid', " + inviteeID + ")).has('status', 'pending').id().tryNext().orElse(null)";

			System.out.println(checkEdge);
			String edgeId = client.submit(checkEdge).one().getObject().toString();
			if (edgeId == null) {
				System.out.println("No pendingFriendship edge found.");
				return ERROR;
			}

			String updateEdge = "g.E(" + "'" + edgeId + "'" + ").property('status', 'friend')";
			System.out.println(updateEdge);
			client.submit(updateEdge).all().get();

			return SUCCESS;
		} catch (Exception e) {
			e.printStackTrace();
			return ERROR;
		}
	}
	
	@Override
	public int rejectFriend(int inviterID, int inviteeID) {
		try {
			String getInviterVertex = "g.V().hasLabel('users').has('userid', " + inviterID + ").id().next()";
			String inviterVertexId = client.submit(getInviterVertex).one().getString();
			String getInviteeIDVertex = "g.V().hasLabel('users').has('userid', " + inviteeID + ").id().next()";
			String inviteeVertexId = client.submit(getInviteeIDVertex).one().getString();

			if (inviteeVertexId == null || inviterVertexId == null) {
				System.out.println("Invitee vertex not found.");
				return ERROR;
			}

			String checkEdge = "g.V(" + inviterVertexId + ").outE('friendship').has('status', 'pending')" +
					".where(__.inV().hasId(" + inviteeVertexId + ")).limit(1).id().tryNext().orElse(null)";
			Result result = client.submit(checkEdge).one();

			String edgeId = (result != null && result.getObject() != null) ? result.getString() : null;
			if (edgeId == null) {
				System.out.println("No pendingFriendship edge found.");
				return ERROR;
			}

			// update as rejected
			String updateEdge = "g.E(" + "'" + edgeId +"'" + ").property('status', 'rejected')";
			client.submit(updateEdge).all().get();

			return SUCCESS;
		} catch (Exception e) {
			e.printStackTrace();
			return ERROR;
		}
	}
	
	
	@Override
	public int viewProfile(int requesterID, int profileOwnerID,
						   HashMap<String, ByteIterator> result, boolean insertImage, boolean testMode) {
			try {
		// get all the attributes
		String query = "g.V().hasLabel('users').has('userid', " + profileOwnerID + ").valueMap()";
		Iterator<Result> results = client.submit(query).iterator();
		if (!results.hasNext()) {
			return SUCCESS;
		} else {
			for (Result res : client.submit(query)) {
				Map<?, ?> valueMap = res.get(Map.class);
				for (Map.Entry<?, ?> entry : valueMap.entrySet()) {
					String key = (String) entry.getKey();
					String value = ((List<?>) entry.getValue()).get(0).toString();
					result.put(key, new StringByteIterator(value));
				}
			}
		}

		String queryPendingCount = "g.V().has('userid', " + profileOwnerID + ").inE('friendship')" +
				".has('status', 'pending').count().next()";
		long pendingFriendCount = client.submit(queryPendingCount).one().getLong();

		String queryFriendCount = "g.V().has('userid', " + profileOwnerID + ").bothE('friendship')" +
				".has('status', 'friend').count().next()";
		long friendCount = client.submit(queryFriendCount).one().getLong();
		result.put("pendingcount", new StringByteIterator(String.valueOf(pendingFriendCount)));
		result.put("friendcount", new StringByteIterator(String.valueOf(friendCount)));
		System.out.println(result);

		return SUCCESS;
	} catch (Exception e) {
		e.printStackTrace();
		return ERROR;
	}
	}

	@Override
	public int thawFriendship(int friendid1, int friendid2) {
		try {
			String checkEdge = "g.E().hasLabel('friendship').where(__.or(" +
					"__.and(__.outV().has('userid', " + friendid1 + "), __.inV().has('userid', " + friendid2 + "))," +
					"__.and(__.outV().has('userid', " + friendid2 + "), __.inV().has('userid', " + friendid1 + "))))" +
					".has('status', 'friend').id().tryNext().orElse(null)";

			Object edgeId = client.submit(checkEdge).one().getObject();
			if (edgeId == null) {
				System.out.println("Friendship does not exist.");
				return ERROR;
			}

			String deleteEdge = "g.E(" +"'" + edgeId +"'" + ").drop()";
			client.submit(deleteEdge).all().get();

			return SUCCESS;
		} catch (Exception e) {
			e.printStackTrace();
			return ERROR;
		}
}
	
	@Override
	public int listFriends(int requesterID, int profileOwnerID,
			Set<String> fields, Vector<HashMap<String, ByteIterator>> result,
			boolean insertImage, boolean testMode) {
		// gets the list of friends for a member.
		if (requesterID < 0 || profileOwnerID < 0)
			return ERROR;
		try {
			String query = "g.V().has('userid', " + profileOwnerID + ").bothE('friendship')" +
					".has('status', 'friend').otherV().valueMap().toList()";

			for (Object friend : client.submit(query).all().get()) {
				Map<String, Object> friendData = (Map<String, Object>) friend;
				HashMap<String, ByteIterator> friendMap = new HashMap<>();
				friendData.forEach((key, value) -> friendMap.put(key, new StringByteIterator(value.toString())));
				result.add(friendMap);
			}

			return SUCCESS;
		} catch (Exception e) {
			e.printStackTrace();
			return ERROR;
		}
	}
	
	@Override
	public int viewFriendReq(int profileOwnerID, Vector<HashMap<String,ByteIterator>> results, boolean insertImage, boolean testMode) {
		// gets the list of pending friend requests for a member.
		try {
			String query = "g.V().has('userid', " + profileOwnerID + ").inE('friendship')" +
					".has('status', 'pending').outV().valueMap().toList()";

			for (Object friend : client.submit(query).all().get()) {
				Map<String, Object> friendData = (Map<String, Object>) friend;
				HashMap<String, ByteIterator> friendMap = new HashMap<>();
				friendData.forEach((key, value) -> friendMap.put(key, new StringByteIterator(value.toString())));
				results.add(friendMap);
			}

			return SUCCESS;
		} catch (Exception e) {
			e.printStackTrace();
			return ERROR;
		}
	}


	@Override
	public int queryPendingFriendshipIds(int inviteeid, Vector<Integer> pendingIds){
		try {
			String query = "g.V().has('userid', " + inviteeid + ").inE('friendship')" +
					".has('status', 'pending').outV().values('userid').toList()";

			for (Result result : client.submit(query).all().get()) {
				Object idObject = result.getObject();
				pendingIds.add(Integer.parseInt(idObject.toString()));
			}
			return SUCCESS;
		} catch (Exception e) {
			e.printStackTrace();
			return ERROR;
		}
	}

	@Override
	public int queryConfirmedFriendshipIds(int profileId, Vector<Integer> confirmedIds){try {
		String query = "g.V().has('userid', " + profileId + ").inE('friendship')" +
				".has('status', 'friend').outV().values('userid').toList()";

		for (Result result : client.submit(query).all().get()) {
			Object idObject = result.getObject();
			confirmedIds.add(Integer.parseInt(idObject.toString()));
		}

		return SUCCESS;
	} catch (Exception e) {
		e.printStackTrace();
		return ERROR;
	}
	}

	
	@Override
	public int viewTopKResources(int requesterID, int profileOwnerID, int k,
			Vector<HashMap<String, ByteIterator>> result) {return SUCCESS;}
	
	@Override
	public int getCreatedResources(int creatorID,
			Vector<HashMap<String, ByteIterator>> result) {return SUCCESS;}
	
	@Override
	public int viewCommentOnResource(int requesterID, int profileOwnerID,
			int resourceID, Vector<HashMap<String, ByteIterator>> result) {return SUCCESS;}
	
	@Override
	public int postCommentOnResource(int commentCreatorID, int profileOwnerID,
			int resourceID, HashMap<String, ByteIterator> values) {return SUCCESS;}
	
	@Override
	public int delCommentOnResource(int resourceCreatorID, int resourceID,
			int manipulationID) {return SUCCESS;}

	

}
