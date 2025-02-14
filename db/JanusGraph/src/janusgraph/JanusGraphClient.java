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
import org.apache.tinkerpop.gremlin.driver.ResultSet;
import org.apache.tinkerpop.gremlin.driver.remote.DriverRemoteConnection;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.io.binary.TypeSerializerRegistry;
import org.apache.tinkerpop.gremlin.util.ser.GraphBinaryMessageSerializerV1;
import org.janusgraph.graphdb.tinkerpop.JanusGraphIoRegistry;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import static org.apache.tinkerpop.gremlin.process.traversal.AnonymousTraversalSource.traversal;

public class JanusGraphClient extends DB{
	/** The code to return when the call succeeds. **/
	public static final int SUCCESS = 0;
	/** The code to return when the call fails. **/
	public static final int ERROR   = -1;
	private Properties props;
	GraphTraversalSource g;
	Client client;


	@Override
	public boolean init() throws DBException {
		// todo: connection how many? docs
		try {
			TypeSerializerRegistry registry = TypeSerializerRegistry.build()
					.addRegistry(JanusGraphIoRegistry.instance())
					.create();
			Cluster cluster = Cluster.build()
					.addContactPoint("128.110.96.123")
					.port(8182)
					.minConnectionPoolSize(10)
					.maxConnectionPoolSize(100)
					.maxSimultaneousUsagePerConnection(16)
					.maxWaitForConnection(5000)
					.serializer(new GraphBinaryMessageSerializerV1(registry))
					.maxContentLength(524288)
					.create();
			client = cluster.connect();
			g = traversal().withRemote(DriverRemoteConnection.using(cluster));
			System.out.println("connected successfully");
			try {
				createSchema(props);
			} catch (Exception e){
				e.printStackTrace(System.out);
			}
			return true;
		} catch (Exception e) {
			e.printStackTrace(System.out);
			return false;
		}
	}

	public static void main(String[] args) throws DBException {
		JanusGraphClient jclient = new JanusGraphClient();
		jclient.init();
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

		try {
			Map<String, Object> resultMap = g.V().hasLabel("users")
					.project("userCount", "minUserId", "avgFriendsPerUser", "avgPendingPerUser")
					.by(__.count())  // user_count
					.by(__.values("userid").min())  // offset
					.by(__.bothE("friendship").has("status", "friend").count())  // friendcount
					.by(__.bothE("friendship").has("status", "pending").count())  // pendingfriendcount
					.tryNext().orElse(null);

			if (resultMap == null) {
				stats.put("usercount", "0");
				stats.put("resourcesperuser", "0");
				stats.put("avgfriendsperuser", "0");
				stats.put("avgpendingperuser", "0");
				return stats;
			}

			// 获取统计结果
			stats.put("usercount", resultMap.getOrDefault("userCount", 0).toString());
			stats.put("resourcesperuser", "0");  // 资源数（此处为 0，可扩展）
			stats.put("avgfriendsperuser", resultMap.getOrDefault("avgFriendsPerUser", 0).toString());
			stats.put("avgpendingperuser", resultMap.getOrDefault("avgPendingPerUser", 0).toString());
			System.out.println(stats);


		} catch (Exception sx) {
			sx.printStackTrace(System.out);
		}
		return stats;
	}

	@Override
	public void cleanup(boolean warmup) throws ExecutionException, InterruptedException {
		try {
			g.V().drop().iterate();
			System.out.println("Graph database cleaned up.");

		} catch (Exception e) {
			e.printStackTrace();
		}
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
			GraphTraversal<Vertex, Vertex> traversal = g.addV(entitySet)
					.property("userid", Integer.parseInt(entityPK));

			values.forEach((key, value) -> {
				if (!key.equalsIgnoreCase("pic") && !key.equalsIgnoreCase("tpic")) {
					String cleaned = value.toString().replace("'", "\\'");
					traversal.property(key.toLowerCase(), cleaned);
				}
			});
			traversal.next();
			System.out.println("inserted successfully");
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
			Object inviterVertexId = g.V().hasLabel("users").has("userid", inviterID).id().tryNext().orElse(null);
			Object inviteeVertexId = g.V().hasLabel("users").has("userid", inviteeID).id().tryNext().orElse(null);

			if (inviterVertexId == null || inviteeVertexId == null) {
				System.err.println("Inviter or Invitee ID not found.");
				return SUCCESS;
			}

			Object existingStatus = g.V(inviterVertexId)
					.outE("friendship")
					.where(__.inV().hasId(inviteeVertexId))
					.values("status")
					.tryNext()
					.orElse(null);

			if (existingStatus != null) {
				System.err.println(inviterID + " -> " + inviteeID + " Friendship already exists with status: " + existingStatus);
				return SUCCESS;
			}

			g.V(inviterVertexId)
					.addE("friendship")
					.to(__.V(inviteeVertexId))
					.property("status", "pending")
					.iterate();

			System.out.println(inviterID + " -> " + inviteeID + " Friend request sent successfully!");

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
			Map<String, Object> vertexIds = g.V().hasLabel("users")
					.has("userid", P.within(friendid1, friendid2))
					.project("userid", "vertexId")
					.by("userid")
					.by(__.id())
					.toList()
					.stream()
					.collect(Collectors.toMap(v -> v.get("userid").toString(), v -> v.get("vertexId")));

			if (!vertexIds.containsKey(String.valueOf(friendid1)) || !vertexIds.containsKey(String.valueOf(friendid2))) {
				System.err.println("One or both vertices not found.");
				return SUCCESS;
			}

			Object fromVertexId = vertexIds.get(String.valueOf(friendid1));
			Object toVertexId = vertexIds.get(String.valueOf(friendid2));

			g.V(fromVertexId)
					.addE("friendship")
					.to(__.V(toVertexId))
					.property("status", "friend")
					.iterate(); // 执行 Gremlin 查询

			System.out.println(friendid1 + " -> " + friendid2 + " Friendship established successfully!");
			return SUCCESS;
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
			Object fromVertexId = g.V().hasLabel("users").has("userid", inviterID).id().tryNext().orElse(null);
			Object toVertexId = g.V().hasLabel("users").has("userid", inviteeID).id().tryNext().orElse(null);

			if (fromVertexId == null || toVertexId == null) {
				System.err.println("One or both vertices not found.");
				return SUCCESS;
			}

			// 添加 friendship 关系
			g.V(fromVertexId)
					.addE("friendship")
					.to(__.V(toVertexId))
					.property("status", "friend")
					.iterate(); // 直接执行 Gremlin 查询

			System.out.println(fromVertexId + " -> " + toVertexId + " Friendship established successfully!");
			return SUCCESS;
		} catch (Exception e) {
			e.printStackTrace();
			return ERROR;
		}
	}

	@Override
	public int rejectFriend(int inviterID, int inviteeID) {
		try {
			Object inviterVertexId = g.V().hasLabel("users").has("userid", inviterID).id().tryNext().orElse(null);
			Object inviteeVertexId = g.V().hasLabel("users").has("userid", inviteeID).id().tryNext().orElse(null);

			if (inviteeVertexId == null || inviterVertexId == null) {
				System.out.println("Invitee or inviter vertex not found.");
				return ERROR;
			}

			// 检查是否存在 "pending" 状态的 friendship 边
			Object edgeId = g.V(inviterVertexId)
					.outE("friendship").has("status", "pending")
					.where(__.inV().hasId(inviteeVertexId))
					.limit(1).id()
					.tryNext().orElse(null);

			if (edgeId == null) {
				System.out.println("RejectFriend action: No pending friendship edge found.");
				return SUCCESS;
			}

			// 更新 friendship edge 状态为 "rejected"
			g.E(edgeId).property("status", "rejected").iterate();

			System.out.println("Friend request from " + inviterID + " to " + inviteeID + " has been rejected.");
			return SUCCESS;
		} catch (Exception e) {
			e.printStackTrace();
			return ERROR;
		}
	}


	@Override
	public int viewProfile(int requesterID, int profileOwnerID,
						   HashMap<String, ByteIterator> result, boolean insertImage, boolean testMode) {

		// get all the attributes
		// todo: check using the index in the graph
		try {
			Map<String, Object> resultMap = g.V().hasLabel("users").has("userid", profileOwnerID)
					.project("profile", "pendingFriendCount", "friendCount")
					.by(__.valueMap())
					.by(__.inE("friendship").has("status", "pending").count())
					.by(__.bothE("friendship").has("status", "friend").count())
					.tryNext().orElse(null);

			if (resultMap == null) {
				return SUCCESS;
			}

			// 解析用户信息
			Map<Object, Object> valueMap = (Map<Object, Object>) resultMap.get("profile");
			valueMap.forEach((key, value) -> {
				if (key instanceof String && value instanceof List) {
					List<?> valueList = (List<?>) value;
					if (!valueList.isEmpty()) {
						result.put((String) key, new StringByteIterator(valueList.get(0).toString()));
					}
				}
			});
			long pendingFriendCount = (long) resultMap.get("pendingFriendCount");
			long friendCount = (long) resultMap.get("friendCount");

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
			// 查找 friendship 关系的 edge ID
			Object edgeId = g.E().hasLabel("friendship")
					.where(__.or(
							__.and(__.outV().has("userid", friendid1), __.inV().has("userid", friendid2)),
							__.and(__.outV().has("userid", friendid2), __.inV().has("userid", friendid1))
					))
					.has("status", "friend")
					.id()
					.tryNext().orElse(null);

			if (edgeId == null) {
				System.out.println("Friendship does not exist.");
				return ERROR;
			}

			// 删除 friendship 关系
			g.E(edgeId).drop().iterate(); // 直接执行删除操作

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
			List<Map<Object, Object>> friends = g.V().hasLabel("users").has("userid", profileOwnerID)
					.bothE("friendship").has("status", "friend") // 获取已建立好友关系的边
					.otherV()
					.valueMap()
					.toList();

			for (Map<Object, Object> friendData : friends) {
				HashMap<String, ByteIterator> friendMap = new HashMap<>();

				if (fields != null) {
					for (String field : fields) {
						if (friendData.containsKey(field)) {
							Object value = friendData.get(field);
							if (value instanceof List && !((List<?>) value).isEmpty()) {
								friendMap.put(field, new StringByteIterator(((List<?>) value).get(0).toString()));
							}
						}
					}
				} else {
					friendData.forEach((key, value) -> {
						if (key instanceof String && value instanceof List) {
							List<?> valueList = (List<?>) value;
							if (!valueList.isEmpty()) {
								friendMap.put((String) key, new StringByteIterator(valueList.get(0).toString()));
							}
						}
					});
				}
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
			List<Map<Object, Object>> pendingRequests = g.V().hasLabel("users").has("userid", profileOwnerID)
					.inE("friendship").has("status", "pending") // 获取请求加好友的入边
					.outV() // 获取发出好友请求的用户
					.valueMap() // 获取用户属性
					.toList(); // 转换为 List

			for (Map<Object, Object> friendData : pendingRequests) {
				HashMap<String, ByteIterator> friendMap = new HashMap<>();
				friendData.forEach((key, value) -> {
					if (key instanceof String && value instanceof List) {
						List<?> valueList = (List<?>) value;
						if (!valueList.isEmpty()) {
							friendMap.put((String) key, new StringByteIterator(valueList.get(0).toString()));
						}
					}
				});
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
			List<Object> pendingUserIds = g.V().hasLabel("users").has("userid", inviteeid)
					.inE("friendship").has("status", "pending") // 找到所有 "pending" 状态的好友请求边
					.outV().values("userid") // 获取请求好友的用户 ID
					.toList(); // 转换为 List

			for (Object id : pendingUserIds) {
				pendingIds.add(Integer.parseInt(id.toString())); // 转换为 Integer 并添加到列表
			}

			return SUCCESS;
		} catch (Exception e) {
			e.printStackTrace();
			return ERROR;
		}
	}

	@Override
	public int queryConfirmedFriendshipIds(int profileId, Vector<Integer> confirmedIds){
		try {
			List<Object> confirmedUserIds = g.V().hasLabel("users").has("userid", profileId)
					.inE("friendship").has("status", "friend") // 获取 "friend" 状态的入边
					.outV().values("userid") // 获取好友的用户 ID
					.toList(); // 转换为 List

			for (Object id : confirmedUserIds) {
				confirmedIds.add(Integer.parseInt(id.toString())); // 转换为 Integer 并添加到列表
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
