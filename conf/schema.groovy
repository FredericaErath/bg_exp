// 回滚当前事务（如果有）
graph.tx().rollback()

// 打开管理接口
mgmt = graph.openManagement()

// 检查复合索引 'user_id_index' 是否存在
userIndexExists = mgmt.getGraphIndex('user_id_index') != null
if (!userIndexExists) {
    println "Composite index 'user_id_index' does not exist. Creating new index..."
    userId = mgmt.getPropertyKey("userid") ?: mgmt.makePropertyKey("userid").dataType(Integer.class).make()
    users = mgmt.getVertexLabel('users') ?: mgmt.makeVertexLabel("users").make()
    mgmt.buildIndex('user_id_index', Vertex.class)
            .addKey(userId)
            .unique()
            .indexOnly(users)  // 限制索引仅适用于 users 顶点
            .buildCompositeIndex()
    mgmt.commit()

    // 等待复合索引可用
    println "Waiting for composite index 'user_id_index' to become available..."
    ManagementSystem.awaitGraphIndexStatus(graph, 'user_id_index').status(SchemaStatus.ENABLED).call()

    // 重新索引现有数据
    println "Reindexing existing data for composite index 'user_id_index'..."
    mgmt = graph.openManagement()
    mgmt.updateIndex(mgmt.getGraphIndex('user_id_index'), SchemaAction.REINDEX).get()
    mgmt.commit()
} else {
    println "Composite index 'user_id_index' already exists. Skipping creation..."
}

mgmt = graph.openManagement()
// 检查顶点中心索引 'friendship_status_index' 是否存在
friendship = mgmt.getEdgeLabel("friendship") ?: mgmt.makeEdgeLabel("friendship").directed().multiplicity(Multiplicity.SIMPLE).make()

// 获取或创建属性键 'status'
status = mgmt.getPropertyKey("status") ?: mgmt.makePropertyKey("status").dataType(String.class).cardinality(Cardinality.SINGLE).make()

// 检查顶点中心索引 'friendship_status_index' 是否存在
friendshipIndexExists = mgmt.getRelationIndex(friendship, 'friendship_status_index') != null
if (!friendshipIndexExists) {
    println "Vertex-centric index 'friendship_status_index' does not exist. Creating new index..."

    // 创建顶点中心索引
    mgmt.buildEdgeIndex(friendship, 'friendship_status_index', Direction.BOTH, Order.asc, status)
    mgmt.commit()

    // 等待顶点中心索引可用
    println "Waiting for vertex-centric index 'friendship_status_index' to become available..."
    ManagementSystem.awaitRelationIndexStatus(graph, 'friendship_status_index', 'friendship').status(SchemaStatus.ENABLED).call()

    // 重新索引现有数据
    println "Reindexing existing data for vertex-centric index 'friendship_status_index'..."
    mgmt = graph.openManagement()
    mgmt.updateIndex(mgmt.getRelationIndex(friendship, 'friendship_status_index'), SchemaAction.REINDEX).get()
    mgmt.commit()
} else {
    println "Vertex-centric index 'friendship_status_index' already exists. Skipping creation..."
}

println "Index creation and reindexing completed successfully!"