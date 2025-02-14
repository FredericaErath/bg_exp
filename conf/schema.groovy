mgmt = graph.openManagement()

// === 1. Composite Index for "userid" (唯一索引) ===
if (mgmt.getGraphIndex("user_id_index") == null) {
    println("Creating Composite Index: user_id_index")

    userIdKey = mgmt.getPropertyKey("userid") ?: mgmt.makePropertyKey("userid").dataType(Integer.class).make()

    mgmt.buildIndex("user_id_index", Vertex.class)
            .addKey(userIdKey)
            .unique()
            .indexOnly(mgmt.getVertexLabel("users"))
            .buildCompositeIndex()

} else {
    println("Composite index 'user_id_index' already exists.")
}

// === 2. Vertex-Centric Index for "friendship" on "status" ===
friendship = mgmt.getEdgeLabel("friendship") ?: mgmt.makeEdgeLabel("friendship").make()
statusKey = mgmt.getPropertyKey("status") ?: mgmt.makePropertyKey("status").dataType(String.class).make()

if (mgmt.getRelationIndex(friendship, "friendship_status_index") == null) {
    println("Creating Vertex-Centric Index: friendship_status_index")
    mgmt.buildEdgeIndex(friendship, "friendship_status_index", Direction.BOTH, Order.desc, statusKey)
} else {
    println("Vertex-centric index 'friendship_status_index' already exists.")
}

mgmt.commit()
println("Index creation process completed.")

// === 3. update ===
mgmt = graph.openManagement()

def updateIndexStatus = { indexName ->
    def index = mgmt.getGraphIndex(indexName)
    if (index != null) {
        def status = index.getIndexStatus(mgmt.getPropertyKey("userid"))
        println("Index '${indexName}' status: ${status}")

        if (status == SchemaStatus.INSTALLED) {
            println("Reindexing '${indexName}'...")
            mgmt.updateIndex(index, SchemaAction.REINDEX).get()
            println("Reindexing '${indexName}' completed.")
        } else if (status == SchemaStatus.REGISTERED) {
            println("Enabling '${indexName}'...")
            mgmt.updateIndex(index, SchemaAction.ENABLE_INDEX).get()
            println("Index '${indexName}' enabled.")
        } else {
            println("Index '${indexName}' is already active.")
        }
    } else {
        println("Index '${indexName}' not found.")
    }
}

println("\n=== Updating Index Status ===")
updateIndexStatus("user_id_index")
updateIndexStatus("friendship_status_index")

mgmt.commit()
println("Index status update completed.")